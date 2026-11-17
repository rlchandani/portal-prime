/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A dependency-free, server-less LAN audio link for the Portal-to-Portal intercom /
 * baby monitor. One device [broadcasts][startBroadcast] (mic → TCP), another
 * [listens][startListening] (TCP → speaker). 16-bit mono PCM at 16 kHz over TCP on the local
 * network — no signalling server, no GMS, no library. Every connection first passes the mutual
 * household-key [IntercomHandshake]; audio then flows only as AES-GCM frames under that
 * session's key ([IntercomSealingOutputStream]). Unproven peers get no audio and play nothing.
 *
 * Uses the handset mic (RECORD_AUDIO); the far-field array needs a Meta-signed
 * permission Immortal can't hold, but the handset mic is fine for a monitor.
 */
class LanAudio {
  private val TAG = "ImmortalIntercom"
  // Distinct from FleetConfig.DEFAULT_PORT (8723): on provisioned/remote-paired
  // devices FleetAgentService already binds that port at launch, so reusing it
  // here would make ServerSocket(PORT) in startBroadcast() fail to bind.
  val PORT = 8724
  private val SAMPLE_RATE = 16000
  private val CONNECT_TIMEOUT_MS = 5000
  /** Concurrent authenticated listeners per broadcast (each opens its own mic capture). */
  private val MAX_LISTENERS = 3
  /** Concurrent unauthenticated handshakes; each is bounded by [IntercomHandshake.TIMEOUT_MS]. */
  private val MAX_PENDING_HANDSHAKES = 4

  private val sessions = LanAudioSessions()

  val isActive: Boolean get() = sessions.isActive

  /** Capture the mic and stream it to every household Portal (up to [MAX_LISTENERS]) that connects.
   * [onState] reports whether the listening socket bound, so the UI doesn't claim
   * it's broadcasting when the port couldn't be opened (e.g. already in use). */
  internal fun startBroadcast(credentials: IntercomCredentials, onState: (Boolean) -> Unit = {}) {
    val session = sessions.start()
    val handshake = IntercomHandshake(credentials)
    val replay = HouseholdMessage.ReplayGuard()
    val slots = IntercomSlots(MAX_PENDING_HANDSHAKES, MAX_LISTENERS)
    Thread {
      try {
        if (!session.isActive) return@Thread
        val server = runCatching { ServerSocket() }
            .onFailure {
              if (session.isActive) Log.w(TAG, "broadcast bind failed on :$PORT", it)
            }.getOrNull()
        if (server == null) {
          if (session.isActive) {
            sessions.finish(session)
            onState(false)
          }
          return@Thread
        }
        // Own the unbound socket first: a retired worker must never claim the new session's port.
        val ownedServer = session.own { server.close() } ?: return@Thread
        try {
          val bound = runCatching {
            ownedServer.start { server.bind(InetSocketAddress(PORT)) }
          }.onFailure {
            if (session.isActive) Log.w(TAG, "broadcast bind failed on :$PORT", it)
          }.getOrDefault(false)
          if (!bound) {
            if (session.isActive) {
              sessions.finish(session)
              onState(false)
            }
            return@Thread
          }
          if (!session.isActive) return@Thread
          onState(true)
          server.soTimeout = 0
          while (session.isActive) {
            val socket = server.accept()
            val ownedSocket = session.own { socket.close() } ?: break
            if (!slots.tryBeginHandshake()) {
              Log.w(TAG, "intercom connection refused: too many pending handshakes")
              ownedSocket.close()
              continue
            }
            // Each listener and microphone belongs to this exact broadcast session.
            Thread {
              try {
                serveListener(session, socket, handshake, replay, slots)
              } catch (error: Throwable) {
                if (session.isActive) Log.w(TAG, "mic pump ended", error)
              } finally {
                ownedSocket.close()
              }
            }.apply { isDaemon = true; start() }
          }
        } finally {
          ownedServer.close()
        }
      } catch (error: Throwable) {
        if (session.isActive) Log.w(TAG, "broadcast failed", error)
      } finally {
        sessions.finish(session)
      }
    }.apply { isDaemon = true; start() }
  }

  private fun serveListener(
      session: LanAudioSessions.Session,
      socket: Socket,
      handshake: IntercomHandshake,
      replay: HouseholdMessage.ReplayGuard,
      slots: IntercomSlots,
  ) {
    val result = try {
      if (!session.isActive) return
      handshake.asBroadcaster(socket, replay)
    } finally {
      slots.endHandshake()
    }
    if (result !is IntercomHandshake.Result.Accepted) {
      if (session.isActive) logRejected(result, socket)
      return
    }
    if (!slots.tryBeginListener()) {
      Log.w(TAG, "intercom listener refused: $MAX_LISTENERS already connected")
      return
    }
    try {
      if (session.isActive) {
        pumpMicTo(session, IntercomSealingOutputStream(socket.getOutputStream(), result.sessionKey))
      }
    } finally {
      slots.endListener()
    }
  }

  private fun logRejected(result: IntercomHandshake.Result, socket: Socket) {
    val reason = (result as? IntercomHandshake.Result.Rejected)?.reason
    Log.w(TAG, "intercom handshake rejected ($reason) from ${socket.inetAddress?.hostAddress}")
  }

  private fun pumpMicTo(session: LanAudioSessions.Session, out: OutputStream) {
    if (!session.isActive) return
    val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
    val ownedMic = session.own {
      runCatching { rec.stop() }
      runCatching { rec.release() }
    } ?: return
    try {
      if (!ownedMic.start { rec.startRecording() }) return
      val buf = ByteArray(2048)
      while (session.isActive) {
        val n = rec.read(buf, 0, buf.size)
        if (n > 0 && session.isActive) out.write(buf, 0, n) else break
      }
    } finally {
      ownedMic.close()
    }
  }

  /** Connect to [host] and, once both sides prove the household key, play its sealed audio.
   * [onState] reports connect+handshake success/failure so the UI can show status. */
  internal fun startListening(host: String, credentials: IntercomCredentials, onState: (Boolean) -> Unit) {
    val session = sessions.start()
    val handshake = IntercomHandshake(credentials)
    Thread {
      try {
        if (!session.isActive) return@Thread
        val socket = runCatching { Socket() }.getOrNull()
        if (socket == null) {
          if (session.isActive) {
            sessions.finish(session)
            onState(false)
          }
          return@Thread
        }
        // Own the pending socket before connect, so stop can interrupt its blocking IO.
        val ownedSocket = session.own { socket.close() } ?: return@Thread
        try {
          val connected = runCatching {
            if (!session.isActive) return@Thread
            socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
            true
          }.getOrDefault(false)
          if (!session.isActive) return@Thread
          // Nothing is played until the broadcaster proves it holds the household key.
          val result = if (connected) handshake.asListener(socket) else null
          if (!session.isActive) return@Thread
          if (result != null && result !is IntercomHandshake.Result.Accepted) logRejected(result, socket)
          val accepted = result as? IntercomHandshake.Result.Accepted
          val ok = accepted != null
          if (!ok) sessions.finish(session)
          onState(ok)
          if (accepted != null && session.isActive) runCatching {
            playFrom(session, IntercomOpeningInputStream(socket.getInputStream(), accepted.sessionKey))
          }.onFailure { if (session.isActive) Log.w(TAG, "listen ended", it) }
        } finally {
          ownedSocket.close()
        }
      } finally {
        sessions.finish(session)
      }
    }.apply { isDaemon = true; start() }
  }

  private fun playFrom(session: LanAudioSessions.Session, input: InputStream) {
    if (!session.isActive) return
    val minBuf = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(minBuf, 4096))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    val ownedPlayback = session.own {
      runCatching { track.stop() }
      runCatching { track.release() }
    } ?: return
    try {
      if (!ownedPlayback.start { track.play() }) return
      LanPcmStream.copy16BitMono(input, { session.isActive }) { buffer, offset, count ->
        track.write(buffer, offset, count)
      }
    } finally {
      ownedPlayback.close()
    }
  }

  /** Stop broadcasting/listening and release sockets (including streams) and audio. Idempotent. */
  fun stop() {
    sessions.stop()
  }
}
