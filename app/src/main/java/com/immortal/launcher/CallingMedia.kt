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
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One call's WebRTC media: camera 0 (Meta's AI-framed virtual camera) through Camera2Capturer,
 * VOICE_COMMUNICATION mic with WebRTC's software AEC3/NS/AGC (the Portal exposes no platform
 * effects), HW H.264 first and VP8 as fallback, DTLS-SRTP, LAN host candidates only.
 *
 * One engine per call: one capture, one mic, one factory, and one [CallMediaLink] (peer
 * connection) per other member, so a group call is a mesh of up to three links sharing the same
 * tracks. Everything WebRTC runs on one media thread. [release] tears down capturer, links,
 * tracks, sources, the factory, the audio module and the EGL context, in that order; nothing
 * outlives the call. Nothing is ever recorded or saved.
 */
internal class CallMedia(
    context: Context,
    private val quality: CallQuality,
    private val events: Events,
    /** Measurement builds only: generated frames instead of the camera (never a room). */
    private val testFrames: Boolean = false,
) {
  interface Events {
    fun onConnected(peer: String)
    fun onDisconnected(peer: String)
    fun onFailed(peer: String)
    fun onCameraIssue(issue: CameraIssue)
    fun onStats(peer: String, stats: CallStats)
  }

  private val app = context.applicationContext
  private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hearthpane-call-media") }
  val egl: EglBase = EglBase.create()
  /** The self view; the UI points it at its renderer. */
  val local = CallVideoSink()
  private val sinks = ConcurrentHashMap<String, CallVideoSink>()
  private val links = HashMap<String, CallMediaLink>()
  private val shutter = CallShutterProbe { events.onCameraIssue(CameraIssue.SHUTTER) }

  private var adm: JavaAudioDeviceModule? = null
  private var factory: PeerConnectionFactory? = null
  private var videoSource: org.webrtc.VideoSource? = null
  private var audioSource: org.webrtc.AudioSource? = null
  private var videoTrack: VideoTrack? = null
  private var audioTrack: org.webrtc.AudioTrack? = null
  private var capturer: CameraVideoCapturer? = null
  private var captureHelper: SurfaceTextureHelper? = null
  private var generator: CallTestFrames? = null
  private var degraded = quality == CallQuality.LOW_MEMORY
  private var people = 2
  private var probe: Probe? = null

  /** Measurement-only 3rd member: an in-process peer sending generated frames ([CallPerfProbe]). */
  private class Probe(val link: CallMediaLink, val source: org.webrtc.VideoSource, val track: VideoTrack, val frames: CallTestFrames)
  @Volatile private var released = false

  init {
    post(::setUp)
  }

  /** The remote picture of [peer] (created on first ask, so the UI can bind before the link exists). */
  fun sink(peer: String): CallVideoSink = sinks.getOrPut(peer) { CallVideoSink() }

  /** 1:1 compatibility (the loopback probe): the only remote. */
  val remote: CallVideoSink get() = sink(SOLO)

  fun camera(on: Boolean) = post { if (on) startCamera() else stopCamera() }

  fun mic(on: Boolean) = post { audioTrack?.setEnabled(on) }

  /** Offer to [peer] (all LAN candidates gathered, non-trickle). Null on failure. */
  fun createOffer(peer: String = SOLO, done: (String?) -> Unit) = post {
    val link = link(peer) ?: return@post done(null)
    link.createOffer(profile(), done)
  }

  /** Apply [peer]'s verified offer and build our answer. Null on failure. */
  fun answer(peer: String = SOLO, offerSdp: String, done: (String?) -> Unit) = post {
    val link = link(peer) ?: return@post done(null)
    link.answer(profile(), offerSdp, done)
  }

  /** Apply [peer]'s verified answer. */
  fun applyAnswer(peer: String = SOLO, sdp: String, done: (Boolean) -> Unit) = post {
    val link = links[peer] ?: return@post done(false)
    link.applyAnswer(sdp, done)
  }

  /** [peer] left the call: close its link only (camera and mic stay for the others). */
  fun close(peer: String) = post {
    links.remove(peer)?.close()
    sinks.remove(peer)?.target = null
  }

  /** The number of people in the call changed: 3+ capture at 360p and each link sends less. */
  fun groupSize(count: Int) = post {
    if (count == people) return@post
    people = count
    applyProfile("group of $count")
  }

  /**
   * Measurement builds: add (or remove) an in-process extra member. Our side runs a real link
   * named [PROBE] (encode, DTLS-SRTP over loopback, decode, render); the far side sends generated
   * frames. It costs one more encode + decode than a real member, so it is an upper bound.
   */
  fun probe(on: Boolean) = post { if (on) startProbe() else stopProbe() }

  /** Low memory or a busy CPU: step down to 360p and a smaller bitrate for the rest of the call. */
  fun degrade() = post {
    if (degraded) return@post
    degraded = true
    applyProfile("degraded")
  }

  fun release() {
    if (released) return
    released = true
    local.target = null
    sinks.values.forEach { it.target = null }
    thread.execute {
      runCatching { stopCamera() }.onFailure { Log.w(TAG, "camera stop failed", it) }
      runCatching { stopProbe() }
      links.values.forEach { runCatching { it.close() } }
      links.clear()
      runCatching { videoTrack?.dispose(); audioTrack?.dispose() }
      runCatching { videoSource?.dispose(); audioSource?.dispose() }
      runCatching { factory?.dispose() }
      runCatching { adm?.release() }
      runCatching { egl.release() }
      videoTrack = null; audioTrack = null; videoSource = null; audioSource = null; factory = null; adm = null
      Log.i(TAG, "media released")
    }
    thread.shutdown()
  }

  private fun post(block: () -> Unit) {
    if (released) return
    runCatching { thread.execute { if (!released) runCatching(block).onFailure { Log.w(TAG, "media task failed", it) } } }
  }

  private fun profile(): SendProfile = CallGroupQuality.profile(quality, people + if (probe != null) 1 else 0, degraded)

  private fun startProbe() {
    if (probe != null) return
    val f = factory ?: return
    val source = f.createVideoSource(false)
    val track = f.createVideoTrack(PROBE_VIDEO_ID, source)
    val frames = CallTestFrames(source.capturerObserver, PROBE_W, PROBE_H, PROBE_FPS).also { it.start() }
    val far = CallMediaLink(PROBE_FAR, f, null, track, thread, CallVideoSink(), probeEvents, ::post)
    probe = Probe(far, source, track, frames)
    applyProfile("probe on")
    val near = link(PROBE) ?: return
    val p = profile()
    near.createOffer(p) { offer ->
      if (offer == null) { Log.w(TAG, "probe: no offer"); return@createOffer }
      far.answer(p, offer) { answer -> if (answer != null) near.applyAnswer(answer) { ok -> Log.i(TAG, "probe negotiated=$ok") } }
    }
  }

  private fun stopProbe() {
    val p = probe ?: return
    probe = null
    links.remove(PROBE)?.close()
    sinks.remove(PROBE)?.target = null
    p.link.close()
    p.frames.stop()
    runCatching { p.track.dispose(); p.source.dispose() }
    applyProfile("probe off")
  }

  private val probeEvents = object : Events {
    override fun onConnected(peer: String) = Unit
    override fun onDisconnected(peer: String) = Unit
    override fun onFailed(peer: String) = Unit
    override fun onCameraIssue(issue: CameraIssue) = Unit
    override fun onStats(peer: String, stats: CallStats) = Unit
  }

  private fun applyProfile(why: String) {
    val p = profile()
    runCatching { capturer?.changeCaptureFormat(p.width, p.height, p.fps) }
    generator?.format(p.width, p.height, p.fps)
    links.values.forEach { it.apply(p) }
    Log.i(TAG, "send profile ${p.width}x${p.height}@${p.fps} ${p.maxKbps} kbps per peer ($why)")
  }

  private fun link(peer: String): CallMediaLink? {
    links[peer]?.let { return it }
    val f = factory ?: return null
    return CallMediaLink(peer, f, audioTrack, videoTrack, thread, sink(peer), events, ::post).also { links[peer] = it }
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
    thread.scheduleWithFixedDelay({ if (!released) post { links.values.forEach(CallMediaLink::pollStats) } },
        STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
  }

  private fun startCamera() {
    if (capturer != null || generator != null) return
    val source = videoSource ?: return
    val p = profile()
    if (testFrames) {
      generator = CallTestFrames(source.capturerObserver, p.width, p.height, p.fps).also { it.start() }
      videoTrack?.setEnabled(true)
      return
    }
    if (app.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      events.onCameraIssue(CameraIssue.PERMISSION); return
    }
    val enumerator = Camera2Enumerator(app)
    val names = runCatching { enumerator.deviceNames.toList() }.getOrDefault(emptyList())
    // Camera 0 is Meta's virtual, AI-framed camera; it's the only one third-party apps can open.
    val name = names.firstOrNull { it == CAMERA_ID } ?: names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
    if (name == null) { events.onCameraIssue(CameraIssue.BUSY); return }
    val cam = enumerator.createCapturer(name, cameraEvents) ?: run { events.onCameraIssue(CameraIssue.BUSY); return }
    val helper = SurfaceTextureHelper.create("hearthpane-call-capture", egl.eglBaseContext)
    cam.initialize(helper, app, source.capturerObserver)
    cam.startCapture(p.width, p.height, p.fps)
    capturer = cam
    captureHelper = helper
    shutter.reset()
    local.probe = shutter
    videoTrack?.setEnabled(true)
  }

  private fun stopCamera() {
    videoTrack?.setEnabled(false)
    local.probe = null
    generator?.stop()
    generator = null
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

  companion object {
    private const val TAG = "HearthpaneCallMedia"
    const val CAMERA_ID = "0"
    /** The link name for a 1:1 media object that doesn't name its peer (the loopback probe). */
    const val SOLO = "solo"
    /** The measurement probe's member name (never a device id: device ids have no '~'). */
    const val PROBE = "probe~near"
    private const val PROBE_FAR = "probe~far"
    private const val PROBE_VIDEO_ID = "hp-probe"
    private const val PROBE_W = 640
    private const val PROBE_H = 360
    private const val PROBE_FPS = 30
    private const val VIDEO_ID = "hp-video"
    private const val AUDIO_ID = "hp-audio"
    private const val STATS_MS = 2_000L
  }
}
