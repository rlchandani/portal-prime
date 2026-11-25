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
import java.util.concurrent.atomic.AtomicBoolean

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
  /** A newcomer may reach a member before the vouching `call_join` does: retry the offer briefly. */
  private const val LEG_OFFER_TRIES = 5
  private const val LEG_RETRY_MS = 700L

  private val main = Handler(Looper.getMainLooper())
  private val mutableMedia = MutableStateFlow<CallMedia?>(null)
  /** The UI binds its renderers to this media's sinks and EGL context. */
  val media: StateFlow<CallMedia?> = mutableMedia.asStateFlow()
  private val mutableStats = MutableStateFlow<Map<String, CallStats>>(emptyMap())
  /** Latest stats per member (device id). */
  val stats: StateFlow<Map<String, CallStats>> = mutableStats.asStateFlow()

  private var mediaCallId: String? = null
  /** Kept so offerer-link ICE-restart re-offers can be sent without a screen in front. */
  private var appContext: Context? = null
  @Volatile private var foreground = false
  private val queued = ArrayList<() -> Unit>()
  private var audio: SavedAudio? = null
  private var statsCount = 0

  private data class SavedAudio(val mode: Int, val speaker: Boolean)

  /**
   * A call screen started (true) or stopped (false). Counted, because the last call's screen can stop
   * after the next one started. Queued media work waits until one is in front.
   */
  fun foreground(on: Boolean) {
    started = (started + if (on) 1 else -1).coerceAtLeast(0)
    foreground = started > 0
    if (foreground && queued.isNotEmpty()) { val work = queued.toList(); queued.clear(); work.forEach { it() } }
  }

  private var started = 0

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
    offer(context, m, call.id, call.peer.deviceId, call.peerHost, call.peerPort, tries = 1)
  }

  /** Group newcomer: offer to one more member (camera and mic are already open for the call). */
  fun startLeg(context: Context, callId: String, leg: CallLeg) = whenForeground {
    val call = Calling.state.value.call?.takeIf { it.id == callId } ?: return@whenForeground
    val m = ensure(context, callId)
    m.camera(call.cameraOn)
    m.mic(call.micOn)
    offer(context, m, callId, leg.peer.deviceId, leg.host, leg.port, tries = LEG_OFFER_TRIES)
  }

  private fun offer(context: Context, m: CallMedia, callId: String, peer: String, host: String?, port: Int, tries: Int) {
    m.createOffer(peer) { sdp ->
      if (sdp == null || !CallSignalAuth.hasLanCandidate(sdp)) { fail(callId, peer, "no offer / no LAN candidate"); return@createOffer }
      exchange(context, m, callId, peer, host, port, sdp, tries)
    }
  }

  private fun exchange(context: Context, m: CallMedia, callId: String, peer: String, host: String?, port: Int, sdp: String, left: Int) {
    CallSignalClient.sendOffer(context.applicationContext, callId, peer, host, port, sdp) { result ->
      val stillHere = Calling.state.value.call?.let { it.id == callId && it.isMember(peer) } == true
      when {
        !stillHere || mutableMedia.value !== m -> Unit
        result is CallSignalClient.Result.Answer -> m.applyAnswer(peer, result.sdp) { ok -> if (!ok) fail(callId, peer, "answer rejected") }
        result == CallSignalClient.Result.NotYet && left > 1 ->
          main.postDelayed({ exchange(context, m, callId, peer, host, port, sdp, left - 1) }, LEG_RETRY_MS)
        else -> fail(callId, peer, "no verified answer")
      }
    }
  }

  /**
   * On the HTTP thread: build the answer to [from]'s verified offer. Null when this Portal can't
   * take it (screen not in front, media failure, timeout).
   */
  fun answerBlocking(context: Context, call: ActiveCall, from: String, offerSdp: String): String? {
    val latch = CountDownLatch(1)
    val abandoned = AtomicBoolean(false)
    var result: String? = null
    main.post {
      // Re-check on the main thread: the screen is still in front, this is still the call and a
      // member we may answer (the initial offer while setting up, or a signed ICE-restart re-offer
      // once up — [CallAnswerGate]), and the HTTP side hasn't given up. Otherwise nothing opens.
      val current = Calling.state.value.call
      if (!foreground || abandoned.get() || current == null || !CallAnswerGate.accepts(current, call.id, from)) {
        latch.countDown(); return@post
      }
      val m = ensure(context, call.id)
      m.camera(current.cameraOn)
      m.mic(current.micOn)
      m.groupSize(current.people)
      m.answer(from, offerSdp) { sdp -> result = sdp?.takeIf(CallSignalAuth::hasLanCandidate); latch.countDown() }
    }
    if (!latch.await(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS)) { abandoned.set(true); return null }
    return result
  }

  fun camera(on: Boolean) { mutableMedia.value?.camera(on) }
  fun mic(on: Boolean) { mutableMedia.value?.mic(on) }
  fun degrade() { mutableMedia.value?.degrade() }

  /** A member left: close only its link. */
  fun closePeer(peer: String) {
    mutableMedia.value?.close(peer)
    mutableStats.value = mutableStats.value - peer
  }

  private val mutableProbe = MutableStateFlow(false)
  /** Measurement builds: the in-process 3rd-member load probe is on (the UI shows its tile). */
  val probeOn: StateFlow<Boolean> = mutableProbe.asStateFlow()

  fun probe(on: Boolean): Boolean {
    if (!BuildConfig.PORTAL_CALL_LOOPBACK) return false
    val m = mutableMedia.value ?: return false
    m.probe(on)
    mutableProbe.value = on
    return true
  }

  /** Keeps the send profile in step with the number of people (360p at 3+). */
  fun groupSize(people: Int) { mutableMedia.value?.groupSize(people) }

  /** Ends all capture now: camera, mic, every peer connection, EGL. Safe to call repeatedly. */
  fun stop(context: Context) {
    queued.clear()
    val m = mutableMedia.value ?: return
    mutableMedia.value = null
    mutableStats.value = emptyMap()
    mutableProbe.value = false
    mediaCallId = null
    appContext = null
    m.release()
    restoreAudio(context)
  }

  private fun whenForeground(block: () -> Unit) {
    if (foreground) block() else queued.add(block)
  }

  private fun current(callId: String): CallMedia? = mutableMedia.value?.takeIf { mediaCallId == callId }

  private fun ensure(context: Context, callId: String): CallMedia {
    current(callId)?.let { return it }
    stop(context)
    val quality = CallingQualityPolicy.startQuality(context, CallingConfig.load(context).callQuality)
    appContext = context.applicationContext
    val m = CallMedia(context, quality, events(callId), testFrames = CallTestMode.frames)
    mediaCallId = callId
    mutableMedia.value = m
    Calling.state.value.call?.takeIf { it.id == callId }?.let { m.groupSize(it.people) }
    useCommunicationAudio(context)
    return m
  }

  private fun events(callId: String) = object : CallMedia.Events {
    override fun onConnected(peer: String) = Calling.dispatch(CallEvent.MediaUp(callId, Calling.now(), peer))
    override fun onDisconnected(peer: String) = Calling.dispatch(CallEvent.MediaDown(callId, Calling.now(), peer))
    override fun onFailed(peer: String) = Calling.dispatch(CallEvent.MediaDown(callId, Calling.now(), peer))
    override fun onCameraIssue(issue: CameraIssue) = Calling.dispatch(CallEvent.CameraProblem(issue))
    override fun onStats(peer: String, stats: CallStats) {
      mutableStats.value = mutableStats.value + (peer to stats)
      if (statsCount++ % STATS_LOG_EVERY == 0) Log.i("HearthpaneCallStats", "${peer.take(PEER_TAG)} ${stats.logLine()}")
      if (CallingQualityPolicy.shouldDegrade(stats)) main.post(::degrade)
    }
    override fun onReoffer(peer: String, sdp: String) { main.post { sendReoffer(callId, peer, sdp) } }
  }

  /**
   * Send an offerer link's ICE-restart re-offer over the existing signed path, exactly as the
   * initial offer goes (so `lanOnly` + fingerprint + household signing all still apply). A no-op
   * unless this is still the live call and member. Only ever reached in measurement builds today
   * (the trigger in [CallMediaLink] is gated); shipping calls are untouched.
   */
  private fun sendReoffer(callId: String, peer: String, sdp: String) {
    val ctx = appContext ?: return
    val call = Calling.state.value.call?.takeIf { it.id == callId && it.isMember(peer) } ?: return
    val m = current(callId) ?: return
    if (!CallSignalAuth.hasLanCandidate(sdp)) return
    val (host, port) = addressOf(call, peer)
    Log.i(TAG, "re-offer ${peer.take(PEER_TAG)} (ICE restart)")
    exchange(ctx, m, callId, peer, host, port, sdp, left = 1)
  }

  private fun addressOf(call: ActiveCall, peer: String): Pair<String?, Int> =
      if (call.peer.deviceId == peer) call.peerHost to call.peerPort
      else call.leg(peer)?.let { it.host to it.port } ?: (null to 0)

  private fun fail(callId: String, peer: String, why: String) {
    Log.w(TAG, "call setup failed: $why")
    Calling.dispatch(CallEvent.MediaFailed(callId, CallEndReason.FAILED, Calling.now(), peer))
  }

  private const val PEER_TAG = 8

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
