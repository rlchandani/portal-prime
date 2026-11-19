/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.immortal.launcher.BriefingPolicy
import com.immortal.launcher.Calling
import com.immortal.launcher.ChimeConfig
import com.immortal.launcher.JarvisHandoff
import com.immortal.launcher.PhotoFramePreviewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * "Hey Jarvis" while a Hearthpane screen is in front (D6). On these Android 10 Portals only the top app hears the
 * mic, so Jarvis can't listen behind Hearthpane; Hearthpane listens instead and, on a wake, opens Jarvis's floating
 * card straight into listening. The Android shell around [WakePolicy]: it gathers the facts, starts or stops the
 * [WakeMic] on every change, and hands a wake to [JarvisHandoff]. Main thread only (callbacks hop to it).
 *
 * Stops are immediate and starts wait [START_DELAY_MS], so moving between Hearthpane screens, or Jarvis's card
 * closing, never churns the mic or races Jarvis for it.
 */
internal object HearthpaneWake {
  private const val TAG = "HearthpaneWake"
  private const val START_DELAY_MS = 300L
  private const val RETRY_MS = 2_000L
  private const val MAX_RETRY_MS = 60_000L
  private const val MINUTE_MS = 60_000L

  private val main = Handler(Looper.getMainLooper())
  private val loader = Executors.newSingleThreadExecutor { Thread(it, "hearthpane-wake-load").apply { isDaemon = true } }
  private lateinit var app: Context
  private lateinit var signals: WakeSignals
  private var resumed: WeakReference<Activity>? = null
  private var callActive = false
  private var handoff = WakeHandoff()
  private var model: WakeWordModel? = null
  private var modelState = ModelState.UNLOADED
  private var mic: WakeMic? = null
  private var lastDecision: WakePolicy.Decision? = null
  private var retryMs = RETRY_MS
  private var startPending = false
  private val startRunnable = Runnable { startPending = false; startNow() }
  private val minuteTick = Runnable { evaluate() }

  private enum class ModelState { UNLOADED, LOADING, READY, FAILED }

  fun install(application: Application) {
    app = application.applicationContext
    signals = WakeSignals(app, main) { evaluate() }
    signals.register()
    application.registerActivityLifecycleCallbacks(Lifecycle)
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
      Calling.state.map { it.busy }.distinctUntilChanged().collect { busy ->
        callActive = busy
        evaluate()
      }
    }
  }

  /** A setting changed (Settings › Jarvis, or the phone remote): decide again. Any thread. */
  fun refresh(@Suppress("UNUSED_PARAMETER") context: Context) {
    main.post { if (::app.isInitialized) evaluate() }
  }

  private fun facts(now: Long): WakePolicy.Facts {
    val host = resumed?.get()
    return WakePolicy.Facts(
        enabled = JarvisWakeConfig.load(app).wakeOnScreens,
        micPermission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        jarvisInstalled = jarvisInstalled(),
        modelReady = modelState != ModelState.FAILED,
        screenInteractive = app.getSystemService(PowerManager::class.java)?.isInteractive != false,
        dreaming = signals.dreaming,
        hostResumed = host != null,
        hostEligible = host != null && host !is PhotoFramePreviewActivity,
        callActive = callActive,
        cameraInUse = signals.cameraInUse,
        otherRecording = signals.otherRecording,
        quietHours = ChimeConfig.isQuietNow(app, BriefingPolicy.minuteOfDay(System.currentTimeMillis(), TimeZone.getDefault())),
        handingOff = handoff.active(now),
    )
  }

  private fun evaluate() {
    val now = SystemClock.uptimeMillis()
    val decision = WakePolicy.decide(facts(now))
    if (decision != lastDecision) {
      Log.i(TAG, if (decision is WakePolicy.Decision.Pause) "paused: ${decision.reason.log}" else "listening")
      lastDecision = decision
    }
    main.removeCallbacks(minuteTick)
    if (resumed?.get() != null) main.postDelayed(minuteTick, MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
    if (decision is WakePolicy.Decision.Pause) {
      cancelStart()
      mic?.stop()
      if (decision.reason == WakePolicy.Reason.HANDING_OFF) main.postDelayed(minuteTick, WakeHandoff.EXPIRY_MS)
      return
    }
    if (mic?.active != true) scheduleStart(START_DELAY_MS)
  }

  private fun scheduleStart(delayMs: Long) {
    if (startPending) return
    startPending = true
    main.postDelayed(startRunnable, delayMs)
  }

  private fun cancelStart() {
    startPending = false
    main.removeCallbacks(startRunnable)
  }

  private fun startNow() {
    if (WakePolicy.decide(facts(SystemClock.uptimeMillis())) != WakePolicy.Decision.Listen) return
    val loaded = model
    if (loaded == null) return loadModel()
    val current = mic ?: WakeMic(loaded, MicEvents).also { mic = it }
    current.start()
  }

  private fun loadModel() {
    if (modelState == ModelState.LOADING) return
    modelState = ModelState.LOADING
    loader.execute {
      val loaded = WakeWordModel.load(app)
      main.post {
        model = loaded
        modelState = if (loaded != null) ModelState.READY else ModelState.FAILED
        evaluate()
      }
    }
  }

  private fun jarvisInstalled(): Boolean = runCatching {
    app.packageManager.getPackageInfo(JarvisHandoff.PACKAGE, 0)
    true
  }.getOrDefault(false)

  private fun onWake(heardAtMs: Long, score: Float) {
    signals.ownSession = 0
    retryMs = RETRY_MS
    handoff = handoff.woke(SystemClock.uptimeMillis())
    evaluate()
    val host = resumed?.get()
    if (host == null || host.isFinishing) {
      Log.i(TAG, "heard Hey Jarvis but no Hearthpane screen is in front any more")
      return
    }
    Log.i(TAG, "heard Hey Jarvis (score ${"%.2f".format(score)}); opening Jarvis " +
        "${SystemClock.uptimeMillis() - heardAtMs} ms later")
    if (!JarvisHandoff.launchFromWake(host, heardAtMs)) Log.w(TAG, "Jarvis could not be opened from a wake")
  }

  private fun onEnded(reason: String) {
    signals.ownSession = 0
    if (reason == "stopped") return
    Log.w(TAG, "listening ended: $reason; retrying in ${retryMs / 1000} s")
    cancelStart()
    scheduleStart(retryMs)
    retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
  }

  private object MicEvents : WakeMic.Events {
    override fun onWake(uptimeMs: Long, score: Float) {
      main.post { onWake(uptimeMs, score) }
    }

    override fun onOpened(sessionId: Int) {
      signals.ownSession = sessionId
    }

    override fun onEnded(reason: String) {
      main.post { onEnded(reason) }
    }
  }

  private object Lifecycle : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
      resumed = WeakReference(activity)
      handoff = handoff.hostResumed()
      evaluate()
    }

    override fun onActivityPaused(activity: Activity) {
      if (resumed?.get() === activity) resumed = null
      handoff = handoff.hostPaused()
      evaluate()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
  }
}
