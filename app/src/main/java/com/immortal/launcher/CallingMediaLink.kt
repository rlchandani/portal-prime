/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One peer connection to one other member: a 1:1 call has one, a group call one per member (a
 * mesh). Shares the engine's tracks, factory and media thread ([CallMedia]); everything here runs
 * on that thread. LAN host candidates only, non-trickle, DTLS fingerprint checked by the caller.
 */
internal class CallMediaLink(
    val peer: String,
    private val factory: PeerConnectionFactory,
    private val audio: AudioTrack?,
    private val video: VideoTrack?,
    private val thread: ScheduledExecutorService,
    /** The remote picture for this member; the UI binds a renderer to it. */
    val remote: CallVideoSink,
    private val events: CallMedia.Events,
    private val post: (() -> Unit) -> Unit,
) {
  private var pc: PeerConnection? = null
  private var gathered: (() -> Unit)? = null
  private var previousStats: CallStats? = null
  private var bitrate = CallLinkBitrate.State(0)
  private var profile: SendProfile? = null
  @Volatile private var closed = false

  fun createOffer(profile: SendProfile, done: (String?) -> Unit) {
    val peer = ensure(offerer = true, profile) ?: return done(null)
    peer.createOffer(CallSdp.created({ desc -> setLocal(peer, desc, done) }, { done(null) }), MediaConstraints())
  }

  fun answer(profile: SendProfile, offerSdp: String, done: (String?) -> Unit) {
    val peer = ensure(offerer = false, profile) ?: return done(null)
    peer.setRemoteDescription(CallSdp.set({
      post {
        attachAnswererTracks(peer)
        peer.createAnswer(CallSdp.created({ desc -> setLocal(peer, desc, done) }, { done(null) }), MediaConstraints())
      }
    }, { done(null) }), SessionDescription(SessionDescription.Type.OFFER, offerSdp))
  }

  fun applyAnswer(sdp: String, done: (Boolean) -> Unit) {
    val peer = pc ?: return done(false)
    peer.setRemoteDescription(CallSdp.set({ done(true) }, { done(false) }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
  }

  /** The call's send profile changed (group size, degrade): apply this link's share of it. */
  fun apply(next: SendProfile) {
    profile = next
    bitrate = CallLinkBitrate.State(minOf(bitrate.kbps.takeIf { it > 0 } ?: next.maxKbps, next.maxKbps))
    applyEncoding()
  }

  fun pollStats() {
    pc?.getStats { report ->
      val raw = report.statsMap.values.map { RawStat(it.type, it.id, it.members) }
      val stats = CallStatsParser.parse(raw, System.currentTimeMillis(), previousStats)
      previousStats = stats
      post { adapt(stats) }
      events.onStats(peer, stats)
    }
  }

  fun close() {
    if (closed) return
    closed = true
    remote.target = null
    runCatching { pc?.dispose() }.onFailure { Log.w(TAG, "pc dispose failed", it) }
    pc = null
  }

  private fun adapt(stats: CallStats) {
    val target = profile?.maxKbps ?: return
    val next = CallLinkBitrate.next(bitrate, target, stats)
    if (next.kbps != bitrate.kbps) { bitrate = next; applyEncoding(); Log.i(TAG, "$peer ceiling ${next.kbps} kbps") }
    else bitrate = next
  }

  private fun ensure(offerer: Boolean, initial: SendProfile): PeerConnection? {
    pc?.let { return it }
    if (closed) return null
    val peer = factory.createPeerConnection(CallSdp.lanConfig(), observer) ?: return null
    pc = peer
    profile = initial
    bitrate = CallLinkBitrate.State(initial.maxKbps)
    if (offerer) {
      peer.addTransceiver(audio, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)))
      val v = peer.addTransceiver(video, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)))
      CallSdp.preferHardwareCodecs(factory, v)
      applyEncoding()
    }
    return peer
  }

  private fun attachAnswererTracks(peer: PeerConnection) {
    peer.transceivers.forEach { t ->
      t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
      when (t.mediaType) {
        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> t.sender.setTrack(audio, false)
        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> { t.sender.setTrack(video, false); CallSdp.preferHardwareCodecs(factory, t) }
        else -> Unit
      }
    }
    applyEncoding()
  }

  private fun applyEncoding() {
    val p = profile ?: return
    val ceiling = bitrate.kbps.takeIf { it > 0 } ?: p.maxKbps
    // LAN: open the estimate near the budget ([CallLinkBitrate.startEstimateKbps]) so the picture
    // comes up sharp instead of ramping from a low default.
    runCatching { pc?.setBitrate(MIN_KBPS * KBPS, CallLinkBitrate.startEstimateKbps(ceiling) * KBPS, ceiling * KBPS) }
    pc?.transceivers?.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.forEach { t ->
      val params = t.sender.parameters
      params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
      params.encodings.forEach { it.maxBitrateBps = ceiling * KBPS; it.maxFramerate = p.fps }
      t.sender.parameters = params
    }
  }

  private fun setLocal(peer: PeerConnection, desc: SessionDescription, done: (String?) -> Unit) {
    peer.setLocalDescription(CallSdp.set({
      post {
        val finish = { done(peer.localDescription?.description?.let(CallSignalAuth::lanOnly)) }
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) finish()
        else {
          gathered = finish
          thread.schedule({ post { gathered?.let { gathered = null; it() } } }, GATHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
      }
    }, { done(null) }), desc)
  }

  private val observer = object : CallPeerObserver() {
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
      if (state == PeerConnection.IceGatheringState.COMPLETE) post { gathered?.let { gathered = null; it() } }
    }
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
      if (closed) return
      Log.i(TAG, "$peer connection $newState")
      when (newState) {
        PeerConnection.PeerConnectionState.CONNECTED -> events.onConnected(peer)
        PeerConnection.PeerConnectionState.DISCONNECTED -> events.onDisconnected(peer)
        PeerConnection.PeerConnectionState.FAILED -> events.onFailed(peer)
        else -> Unit
      }
    }
    override fun onTrack(transceiver: RtpTransceiver?) {
      (transceiver?.receiver?.track() as? VideoTrack)?.addSink(remote)
    }
  }

  private companion object {
    const val TAG = "HearthpaneCallMedia"
    const val STREAM_ID = "hp"
    const val KBPS = 1_000
    const val MIN_KBPS = 150
    const val GATHER_TIMEOUT_MS = 2_500L
  }
}
