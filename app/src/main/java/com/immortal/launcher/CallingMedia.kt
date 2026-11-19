/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One call's WebRTC media: camera 0 (Meta's AI-framed virtual camera) through Camera2Capturer,
 * VOICE_COMMUNICATION mic with WebRTC's software AEC3/NS/AGC (the Portal exposes no platform
 * effects), HW H.264 first and VP8 as fallback, DTLS-SRTP, LAN host candidates only.
 *
 * Everything WebRTC runs on one media thread. [release] tears down capturer, tracks, sources,
 * the peer connection, the factory, the audio module and the EGL context, in that order; nothing
 * outlives the call. Nothing is ever recorded or saved.
 */
internal class CallMedia(
    context: Context,
    private val quality: CallQuality,
    private val events: Events,
) {
  interface Events {
    fun onConnected()
    fun onDisconnected()
    fun onFailed()
    fun onCameraIssue(issue: CameraIssue)
    fun onStats(stats: CallStats)
  }

  private val app = context.applicationContext
  private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hearthpane-call-media") }
  val egl: EglBase = EglBase.create()
  /** The self view and the other room; the UI points these at its renderers. */
  val local = CallVideoSink()
  val remote = CallVideoSink()
  private val shutter = CallShutterProbe { events.onCameraIssue(CameraIssue.SHUTTER) }

  private var adm: JavaAudioDeviceModule? = null
  private var factory: PeerConnectionFactory? = null
  private var pc: PeerConnection? = null
  private var videoSource: org.webrtc.VideoSource? = null
  private var audioSource: org.webrtc.AudioSource? = null
  private var videoTrack: VideoTrack? = null
  private var audioTrack: org.webrtc.AudioTrack? = null
  private var capturer: CameraVideoCapturer? = null
  private var captureHelper: SurfaceTextureHelper? = null
  private var gathered: (() -> Unit)? = null
  @Volatile private var previousStats: CallStats? = null
  private var degraded = quality == CallQuality.LOW_MEMORY
  @Volatile private var released = false

  init {
    post(::setUp)
  }

  fun camera(on: Boolean) = post { if (on) startCamera() else stopCamera() }

  fun mic(on: Boolean) = post { audioTrack?.setEnabled(on) }

  /** Callee: build the offer (all LAN candidates gathered, non-trickle). Null on failure. */
  fun createOffer(done: (String?) -> Unit) = post {
    val peer = ensurePeer(offerer = true) ?: return@post done(null)
    peer.createOffer(CallSdp.created({ desc -> setLocal(peer, desc, done) }, { done(null) }), MediaConstraints())
  }

  /** Caller: apply the verified offer and build the answer. Null on failure. */
  fun answer(offerSdp: String, done: (String?) -> Unit) = post {
    val peer = ensurePeer(offerer = false) ?: return@post done(null)
    peer.setRemoteDescription(CallSdp.set({
      post {
        attachAnswererTracks(peer)
        peer.createAnswer(CallSdp.created({ desc -> setLocal(peer, desc, done) }, { done(null) }), MediaConstraints())
      }
    }, { done(null) }), SessionDescription(SessionDescription.Type.OFFER, offerSdp))
  }

  /** Callee: apply the verified answer. */
  fun applyAnswer(sdp: String, done: (Boolean) -> Unit) = post {
    val peer = pc ?: return@post done(false)
    peer.setRemoteDescription(CallSdp.set({ done(true) }, { done(false) }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
  }

  /** Low memory or a busy CPU: step down to 360p and a smaller bitrate for the rest of the call. */
  fun degrade() = post {
    if (degraded) return@post
    degraded = true
    val q = CallQuality.LOW_MEMORY
    runCatching { capturer?.changeCaptureFormat(q.width, q.height, q.fps) }
    applyEncoding()
    Log.i(TAG, "degraded to ${q.width}x${q.height}")
  }

  fun release() {
    if (released) return
    released = true
    local.target = null
    remote.target = null
    thread.execute {
      runCatching { stopCamera() }.onFailure { Log.w(TAG, "camera stop failed", it) }
      runCatching { pc?.dispose() }.onFailure { Log.w(TAG, "pc dispose failed", it) }
      runCatching { videoTrack?.dispose(); audioTrack?.dispose() }
      runCatching { videoSource?.dispose(); audioSource?.dispose() }
      runCatching { factory?.dispose() }
      runCatching { adm?.release() }
      runCatching { egl.release() }
      pc = null; videoTrack = null; audioTrack = null; videoSource = null; audioSource = null; factory = null; adm = null
      Log.i(TAG, "media released")
    }
    thread.shutdown()
  }

  private fun post(block: () -> Unit) {
    if (released) return
    runCatching { thread.execute { if (!released) runCatching(block).onFailure { Log.w(TAG, "media task failed", it) } } }
  }

  private fun setUp() {
    CallMediaRuntime.initialize(app)
    val module = JavaAudioDeviceModule.builder(app)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        // The Portal's platform AEC/NS/AGC are not available to apps; WebRTC's software path runs instead.
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .createAudioDeviceModule()
    adm = module
    val f = PeerConnectionFactory.builder()
        .setAudioDeviceModule(module)
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        .createPeerConnectionFactory()
    factory = f
    val vs = f.createVideoSource(false)
    videoSource = vs
    videoTrack = f.createVideoTrack(VIDEO_ID, vs).also { it.addSink(local); it.setEnabled(false) }
    val audio = f.createAudioSource(CallSdp.audioConstraints())
    audioSource = audio
    audioTrack = f.createAudioTrack(AUDIO_ID, audio)
    thread.scheduleWithFixedDelay({ if (!released) pollStats() }, STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
  }

  private fun startCamera() {
    if (capturer != null) return
    if (app.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      events.onCameraIssue(CameraIssue.PERMISSION); return
    }
    val enumerator = Camera2Enumerator(app)
    val names = runCatching { enumerator.deviceNames.toList() }.getOrDefault(emptyList())
    // Camera 0 is Meta's virtual, AI-framed camera; it's the only one third-party apps can open.
    val name = names.firstOrNull { it == CAMERA_ID } ?: names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
    val source = videoSource
    if (name == null || source == null) { events.onCameraIssue(CameraIssue.BUSY); return }
    val cam = enumerator.createCapturer(name, cameraEvents) ?: run { events.onCameraIssue(CameraIssue.BUSY); return }
    val helper = SurfaceTextureHelper.create("hearthpane-call-capture", egl.eglBaseContext)
    cam.initialize(helper, app, source.capturerObserver)
    val q = if (degraded) CallQuality.LOW_MEMORY else quality
    cam.startCapture(q.width, q.height, q.fps)
    capturer = cam
    captureHelper = helper
    shutter.reset()
    local.probe = shutter
    videoTrack?.setEnabled(true)
  }

  private fun stopCamera() {
    videoTrack?.setEnabled(false)
    local.probe = null
    val cam = capturer ?: return
    capturer = null
    runCatching { cam.stopCapture() }
    runCatching { cam.dispose() }
    runCatching { captureHelper?.dispose() }
    captureHelper = null
  }

  private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
    override fun onCameraError(error: String?) {
      Log.w(TAG, "camera error: $error")
      val shut = error?.contains("disabled", ignoreCase = true) == true
      events.onCameraIssue(if (shut) CameraIssue.SHUTTER else CameraIssue.BUSY)
      post(::stopCamera)
    }
    override fun onCameraDisconnected() { events.onCameraIssue(CameraIssue.BUSY); post(::stopCamera) }
    override fun onCameraFreezed(error: String?) { Log.w(TAG, "camera froze: $error"); events.onCameraIssue(CameraIssue.BUSY); post(::stopCamera) }
    override fun onCameraOpening(cameraName: String?) { Log.i(TAG, "camera $cameraName opening") }
    override fun onFirstFrameAvailable() { Log.i(TAG, "camera first frame") }
    override fun onCameraClosed() { Log.i(TAG, "camera closed") }
  }

  private fun ensurePeer(offerer: Boolean): PeerConnection? {
    pc?.let { return it }
    val f = factory ?: return null
    val peer = f.createPeerConnection(CallSdp.lanConfig(), observer) ?: return null
    pc = peer
    if (offerer) {
      peer.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)))
      val video = peer.addTransceiver(videoTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)))
      CallSdp.preferHardwareCodecs(f, video)
      applyEncoding()
    }
    return peer
  }

  /** The answerer's transceivers come from the offer; give them our tracks and codec order. */
  private fun attachAnswererTracks(peer: PeerConnection) {
    val f = factory ?: return
    peer.transceivers.forEach { t ->
      t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
      when (t.mediaType) {
        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> t.sender.setTrack(audioTrack, false)
        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> { t.sender.setTrack(videoTrack, false); CallSdp.preferHardwareCodecs(f, t) }
        else -> Unit
      }
    }
    applyEncoding()
  }

  private fun applyEncoding() {
    val q = if (degraded) CallQuality.LOW_MEMORY else quality
    pc?.transceivers?.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.forEach { t ->
      val params = t.sender.parameters
      params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
      params.encodings.forEach { it.maxBitrateBps = q.maxKbps * KBPS }
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
          thread.schedule({ gathered?.let { gathered = null; it() } }, GATHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
      }
    }, { done(null) }), desc)
  }

  private val observer = object : CallPeerObserver() {
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
      if (state == PeerConnection.IceGatheringState.COMPLETE) post { gathered?.let { gathered = null; it() } }
    }
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
      Log.i(TAG, "connection $newState")
      when (newState) {
        PeerConnection.PeerConnectionState.CONNECTED -> events.onConnected()
        PeerConnection.PeerConnectionState.DISCONNECTED -> events.onDisconnected()
        PeerConnection.PeerConnectionState.FAILED -> events.onFailed()
        else -> Unit
      }
    }
    override fun onTrack(transceiver: RtpTransceiver?) {
      (transceiver?.receiver?.track() as? VideoTrack)?.addSink(remote)
    }
  }

  private fun pollStats() {
    pc?.getStats { report ->
      val raw = report.statsMap.values.map { RawStat(it.type, it.id, it.members) }
      val stats = CallStatsParser.parse(raw, System.currentTimeMillis(), previousStats)
      previousStats = stats
      events.onStats(stats)
    }
  }

  companion object {
    private const val TAG = "HearthpaneCallMedia"
    const val CAMERA_ID = "0"
    private const val VIDEO_ID = "hp-video"
    private const val AUDIO_ID = "hp-audio"
    private const val STREAM_ID = "hp"
    private const val KBPS = 1_000
    private const val STATS_MS = 2_000L
    private const val GATHER_TIMEOUT_MS = 2_500L
  }
}
