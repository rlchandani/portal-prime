/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the one live [CallMedia] and enforces the capture rules from the camera spike:
 * - camera and mic open only while the call screen is in the foreground (screen awake);
 * - the callee opens nothing before Accept (or a permitted drop-in's chime countdown);
 * - everything is released the moment the call ends or the screen goes away.
 *
 * All methods run on the main thread except [answerBlocking], which the HTTP route calls.
 */
internal object CallMediaHub {
  private const val TAG = "HearthpaneCallMedia"
  private const val ANSWER_WAIT_MS = 8_000L
  private const val STATS_LOG_EVERY = 5

  private val main = Handler(Looper.getMainLooper())
  private val mutableMedia = MutableStateFlow<CallMedia?>(null)
  /** The UI binds its renderers to this media's sinks and EGL context. */
  val media: StateFlow<CallMedia?> = mutableMedia.asStateFlow()
  private val mutableStats = MutableStateFlow<CallStats?>(null)
  val stats: StateFlow<CallStats?> = mutableStats.asStateFlow()

  private var mediaCallId: String? = null
  private var foreground = false
  private var queued: (() -> Unit)? = null
  private var audio: SavedAudio? = null
  private var statsCount = 0

  private data class SavedAudio(val mode: Int, val speaker: Boolean)

  /** The call screen started (true) or stopped (false). Queued media work waits for true. */
  fun foreground(on: Boolean) {
    foreground = on
    if (on) queued?.let { queued = null; it() }
  }

  /** Caller: show or hide the self preview while it rings. Never opens the mic. */
  fun preview(context: Context, call: ActiveCall, camera: Boolean) = whenForeground {
    val m = if (camera) ensure(context, call.id) else current(call.id) ?: return@whenForeground
    m.camera(camera)
  }

  /** Callee after Accept: open camera + mic, then send the signed offer to the caller. */
  fun start(context: Context, call: ActiveCall) = whenForeground {
    val m = ensure(context, call.id)
    m.camera(call.cameraOn)
    m.mic(call.micOn)
    m.createOffer { sdp ->
      if (sdp == null || !CallSignalAuth.hasLanCandidate(sdp)) fail(call.id, "no offer / no LAN candidate")
      else CallSignalClient.sendOffer(context.applicationContext, call, sdp) { answer ->
        if (answer == null) fail(call.id, "no verified answer")
        else m.applyAnswer(answer) { ok -> if (!ok) fail(call.id, "answer rejected") }
      }
    }
  }

  /**
   * Caller, on the HTTP thread: build the answer to a verified offer. Null when this Portal can't
   * take it (screen not in front, media failure, timeout).
   */
  fun answerBlocking(context: Context, call: ActiveCall, offerSdp: String): String? {
    val latch = CountDownLatch(1)
    var result: String? = null
    main.post {
      if (!foreground) { latch.countDown(); return@post }
      val m = ensure(context, call.id)
      m.camera(call.cameraOn)
      m.mic(call.micOn)
      m.answer(offerSdp) { sdp -> result = sdp?.takeIf(CallSignalAuth::hasLanCandidate); latch.countDown() }
    }
    if (!latch.await(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS)) return null
    return result
  }

  fun camera(on: Boolean) { mutableMedia.value?.camera(on) }
  fun mic(on: Boolean) { mutableMedia.value?.mic(on) }
  fun degrade() { mutableMedia.value?.degrade() }

  /** Ends all capture now: camera, mic, peer connection, EGL. Safe to call repeatedly. */
  fun stop(context: Context) {
    queued = null
    val m = mutableMedia.value ?: return
    mutableMedia.value = null
    mutableStats.value = null
    mediaCallId = null
    m.release()
    restoreAudio(context)
  }

  private fun whenForeground(block: () -> Unit) {
    if (foreground) block() else queued = block
  }

  private fun current(callId: String): CallMedia? = mutableMedia.value?.takeIf { mediaCallId == callId }

  private fun ensure(context: Context, callId: String): CallMedia {
    current(callId)?.let { return it }
    stop(context)
    val quality = CallingQualityPolicy.startQuality(context, CallingConfig.load(context).callQuality)
    val m = CallMedia(context, quality, events(callId))
    mediaCallId = callId
    mutableMedia.value = m
    useCommunicationAudio(context)
    return m
  }

  private fun events(callId: String) = object : CallMedia.Events {
    override fun onConnected() = Calling.dispatch(CallEvent.MediaUp(callId, Calling.now()))
    override fun onDisconnected() = Calling.dispatch(CallEvent.MediaDown(callId, Calling.now()))
    override fun onFailed() = Calling.dispatch(CallEvent.MediaDown(callId, Calling.now()))
    override fun onCameraIssue(issue: CameraIssue) = Calling.dispatch(CallEvent.CameraProblem(issue))
    override fun onStats(stats: CallStats) {
      mutableStats.value = stats
      if (statsCount++ % STATS_LOG_EVERY == 0) Log.i("HearthpaneCallStats", stats.logLine())
      if (CallingQualityPolicy.shouldDegrade(stats)) main.post(::degrade)
    }
  }

  private fun fail(callId: String, why: String) {
    Log.w(TAG, "call setup failed: $why")
    Calling.dispatch(CallEvent.MediaFailed(callId, CallEndReason.FAILED, Calling.now()))
  }

  private fun useCommunicationAudio(context: Context) {
    val am = context.getSystemService(AudioManager::class.java) ?: return
    if (audio == null) audio = SavedAudio(am.mode, am.isSpeakerphoneOn)
    runCatching {
      am.mode = AudioManager.MODE_IN_COMMUNICATION
      @Suppress("DEPRECATION") am.isSpeakerphoneOn = true
    }.onFailure { Log.w(TAG, "audio mode not set", it) }
  }

  private fun restoreAudio(context: Context) {
    val saved = audio ?: return
    audio = null
    val am = context.getSystemService(AudioManager::class.java) ?: return
    runCatching {
      am.mode = saved.mode
      @Suppress("DEPRECATION") am.isSpeakerphoneOn = saved.speaker
    }.onFailure { Log.w(TAG, "audio mode not restored", it) }
  }
}

/** When to start lower and when to step down (pure apart from the memory read). */
internal object CallingQualityPolicy {
  /** Below this much available memory a call starts at 360p (the Portal has ~340 MB with swap full). */
  const val LOW_MEMORY_BYTES = 220L * 1024 * 1024

  fun startQuality(context: Context, chosen: CallQuality): CallQuality {
    val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return chosen
    val info = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
    return pick(chosen, info.availMem, info.lowMemory)
  }

  fun pick(chosen: CallQuality, availBytes: Long, lowMemory: Boolean): CallQuality =
      if (lowMemory || availBytes < LOW_MEMORY_BYTES) CallQuality.LOW_MEMORY else chosen

  /** The encoder is CPU-limited and already below 20 fps: step down rather than stutter. */
  fun shouldDegrade(stats: CallStats): Boolean =
      stats.limitedBy == "cpu" && stats.sendFps in 1.0..MIN_SMOOTH_FPS

  private const val MIN_SMOOTH_FPS = 20.0
}
