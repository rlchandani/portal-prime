/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide WebRTC setup, done once, and whether this Portal can run calls at all. */
internal object CallMediaRuntime {
  private const val TAG = "HearthpaneCallMedia"
  private const val ARM64 = "arm64-v8a"
  @Volatile private var initialized = false

  /** Calling ships the arm64 libwebrtc only; a 32-bit process reports calling as unavailable. */
  fun available(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains(ARM64) && android.os.Process.is64Bit()

  @Synchronized fun initialize(context: Context) {
    if (initialized) return
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
        .setEnableInternalTracer(false).createInitializationOptions())
    initialized = true
    Log.i(TAG, "webrtc initialized")
  }
}

/** A sink the UI can point at a renderer (or nothing) at any time; frames are never stored. */
internal class CallVideoSink : VideoSink {
  @Volatile var target: VideoSink? = null
  @Volatile var probe: ((VideoFrame) -> Unit)? = null
  private val count = AtomicInteger()
  /** Frames seen since the call started (the UI hides the avatar after the first one). */
  val frames: Int get() = count.get()

  override fun onFrame(frame: VideoFrame) {
    count.incrementAndGet()
    probe?.invoke(frame)
    target?.onFrame(frame)
  }
}

/**
 * Detects a closed privacy shutter from the first second of camera frames: a few frames are
 * read for their average brightness only (a 16×9 grid of luma samples, in memory, never kept).
 * All-black means the shutter is closed; the call then goes audio-only and says so.
 */
internal class CallShutterProbe(private val onShutter: () -> Unit) : (VideoFrame) -> Unit {
  private var seen = 0
  private var samples = 0
  private var brightest = 0.0
  @Volatile private var done = false

  fun reset() { seen = 0; samples = 0; brightest = 0.0; done = false }

  override fun invoke(frame: VideoFrame) {
    if (done) return
    seen++
    if (seen < FIRST_SAMPLE || (seen - FIRST_SAMPLE) % EVERY != 0) return
    val luma = runCatching { meanLuma(frame) }.getOrNull() ?: return
    brightest = maxOf(brightest, luma)
    samples++
    if (samples >= SAMPLES) {
      done = true
      if (CallShutter.isClosed(brightest)) onShutter()
    }
  }

  private fun meanLuma(frame: VideoFrame): Double {
    val i420 = frame.buffer.toI420() ?: return BRIGHT
    try {
      val y = i420.dataY
      val stride = i420.strideY
      var sum = 0L
      for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
        val px = (gx * 2 + 1) * i420.width / (GRID_W * 2)
        val py = (gy * 2 + 1) * i420.height / (GRID_H * 2)
        sum += y.get(py * stride + px).toInt() and BYTE
      }
      return sum.toDouble() / (GRID_W * GRID_H)
    } finally {
      i420.release()
    }
  }

  private companion object {
    const val FIRST_SAMPLE = 12
    const val EVERY = 6
    const val SAMPLES = 5
    const val GRID_W = 16
    const val GRID_H = 9
    const val BYTE = 0xFF
    const val BRIGHT = 255.0
  }
}

/** The shutter rule, pure: even a dark room keeps some luma; a closed shutter is all black. */
internal object CallShutter {
  const val BLACK_LUMA = 12.0
  fun isClosed(brightestMeanLuma: Double): Boolean = brightestMeanLuma < BLACK_LUMA
}

/** SDP helpers: LAN config, observers, audio processing constraints, hardware codec order. */
internal object CallSdp {
  private const val TAG = "HearthpaneCallMedia"
  private val PREFERRED = listOf("H264", "VP8")

  fun lanConfig(): PeerConnection.RTCConfiguration = PeerConnection.RTCConfiguration(emptyList()).apply {
    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
    candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST
    keyType = PeerConnection.KeyType.ECDSA
  }

  /** WebRTC's software echo cancelling, noise suppression and gain control, all on. */
  fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
    listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter").forEach {
      mandatory.add(MediaConstraints.KeyValuePair(it, "true"))
    }
  }

  /** H.264 (QCOM hardware) first, VP8 (also hardware) next, then everything else (RTX, RED, FEC). */
  fun preferHardwareCodecs(factory: PeerConnectionFactory, transceiver: RtpTransceiver) {
    runCatching {
      val codecs = factory.getRtpReceiverCapabilities(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
      val ordered = codecs.sortedBy { c -> PREFERRED.indexOf(c.name.uppercase()).let { if (it < 0) PREFERRED.size else it } }
      transceiver.setCodecPreferences(ordered)
    }.onFailure { Log.w(TAG, "codec preferences not applied", it) }
  }

  fun created(ok: (SessionDescription) -> Unit, fail: () -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = ok(desc)
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) { Log.w(TAG, "sdp create failed: $error"); fail() }
    override fun onSetFailure(error: String?) { Log.w(TAG, "sdp set failed: $error"); fail() }
  }

  fun set(ok: () -> Unit, fail: () -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = Unit
    override fun onSetSuccess() = ok()
    override fun onCreateFailure(error: String?) { Log.w(TAG, "sdp create failed: $error"); fail() }
    override fun onSetFailure(error: String?) { Log.w(TAG, "sdp set failed: $error"); fail() }
  }
}

/** PeerConnection.Observer with no-op defaults, so [CallMedia] overrides only what it uses. */
internal abstract class CallPeerObserver : PeerConnection.Observer {
  override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
  override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
  override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
  override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
  override fun onIceCandidate(candidate: IceCandidate?) = Unit
  override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
  override fun onAddStream(stream: MediaStream?) = Unit
  override fun onRemoveStream(stream: MediaStream?) = Unit
  override fun onDataChannel(channel: DataChannel?) = Unit
  override fun onRenegotiationNeeded() = Unit
}
