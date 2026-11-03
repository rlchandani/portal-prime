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
 * [listens][startListening] (TCP → speaker). Raw 16-bit mono PCM at 16 kHz over a
 * plain TCP socket on the local network — no signalling server, no GMS, no library.
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

  private val sessions = LanAudioSessions()

  val isActive: Boolean get() = sessions.isActive

  /** Capture the mic and stream it to every device that connects to this port.
   * [onState] reports whether the listening socket bound, so the UI doesn't claim
   * it's broadcasting when the port couldn't be opened (e.g. already in use). */
  fun startBroadcast(onState: (Boolean) -> Unit = {}) {
    val session = sessions.start()
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
            // Each listener and microphone belongs to this exact broadcast session.
            Thread {
              try {
                if (session.isActive) pumpMicTo(session, socket.getOutputStream())
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

  /** Connect to [host] and play whatever audio it streams. [onState] reports connect
   * success/failure so the UI can show status. */
  fun startListening(host: String, onState: (Boolean) -> Unit) {
    val session = sessions.start()
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
          val ok = runCatching {
            if (!session.isActive) return@Thread
            socket.connect(InetSocketAddress(host, PORT), 5000)
            true
          }.getOrDefault(false)
          if (!session.isActive) return@Thread
          if (!ok) sessions.finish(session)
          onState(ok)
          if (ok && session.isActive) runCatching { playFrom(session, socket.getInputStream()) }
              .onFailure { if (session.isActive) Log.w(TAG, "listen ended", it) }
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
