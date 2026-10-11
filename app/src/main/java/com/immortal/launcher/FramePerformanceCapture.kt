/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.view.View
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/** A primitive entry identity bound to one explicit capture; it retains no UI objects. */
internal class SettingsEntryReport internal constructor(
    internal val sessionId: Long,
    internal val entryId: Long,
    private val accept: (SettingsEntryStage) -> Boolean,
) {
  fun record(stage: SettingsEntryStage): Boolean = runCatching { accept(stage) }.getOrDefault(false)
}

/** Explicit, expiring Fleet diagnostics. No collector thread or listener while inactive. */
internal object FramePerformanceCapture {
  internal const val SETTINGS_ENTRY_SESSION = "immortal.diagnostic.settings.session"
  internal const val SETTINGS_ENTRY_ID = "immortal.diagnostic.settings.entry"
  private const val MAX_WINDOWS = 24
  private const val MAX_HOSTS = 128
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private var initialized = false
  private lateinit var application: Application
  // Main-thread owned; weak values do not retain Activity/Dream contexts after destruction.
  private val windows = ArrayList<Tracked>()
  private var sequence = 0L
  @Volatile private var current: Session? = null
  private val settingsSearchPreparationProbe = SettingsSearchPreparationProbe(
      currentMeasurement = ::settingsSearchPreparationMeasurement,
      nanoTime = SystemClock::elapsedRealtimeNanos,
  )
  private val settingsUiWorkProbe = SettingsUiWorkProbe(
      currentMeasurement = ::settingsUiWorkMeasurement,
      nanoTime = SystemClock::elapsedRealtimeNanos,
  )
  private val slideshowWorkProbe = SlideshowWorkProbe(
      currentMeasurement = ::slideshowWorkMeasurement,
      nanoTime = SystemClock::elapsedRealtimeNanos,
  )
  @PublishedApi internal val imageResolutionProbe = EngineImageResolutionProbe(::imageResolutionMeasurement)
  private data class Tracked(val window: WeakReference<Window>, val host: String,
      var attachListener: View.OnAttachStateChangeListener? = null)
  private data class Binding(val window: WeakReference<Window>, val budgetNs: Long,
      val listener: Window.OnFrameMetricsAvailableListener)
  private data class SettingsEntryWindow(val window: WeakReference<Window>, val report: SettingsEntryReport,
      val firstDraw: SettingsEntryFirstDrawObservation)
  private class Session(val id: Long, val request: FramePerformanceRequest) {
    val began = SystemClock.elapsedRealtime()
    val beganNs = SystemClock.elapsedRealtimeNanos()
    val deadline = began + request.durationSec * 1000L
    val thread = HandlerThread("immortal-perf", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    val handler = Handler(thread.looper)
    val histograms = LinkedHashMap<String, FramePerformanceHistogram>()
    val settingsSearchPreparation = SettingsSearchPreparationSamples()
    val settingsUiWork = SettingsUiWorkSamples()
    val settingsEntryTimeline = SettingsEntryTimeline()
    val settingsEntryWindows = ArrayList<SettingsEntryWindow>() // Main-owned weak windows.
    val slideshowWork = SlideshowWorkSamples()
    val slideshowStyles = SlideshowStyleSamples()
    val imageResolution = EngineImageResolutionSamples()
    // Allocated only by an explicit sampleMain opt-in. No Activity/Window references in the sampler.
    val mainSamples = if (request.sampleMain) MainThreadSampleHistogram() else null
    var sampleMainActive = false // Guarded by the session monitor, like the histograms.
    var sampler: Runnable? = null
    var nextSampleUptime = 0L // Collector-owned after the initial post.
    val bindings = ArrayList<Binding>() // Main thread only; snapshot uses attached count below.
    var attached = 0
    var rejectedHosts = 0
    var rejectedWindows = 0
    var attachFailures = 0
    var closedAt: Long? = null
    var stopRequested = false
    lateinit var expiry: Runnable
    var wakeReceiver: BroadcastReceiver? = null
  }

  fun init(app: Application) {
    check(Looper.myLooper() == Looper.getMainLooper())
    if (initialized) return
    initialized = true
    application = app
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(a: Activity, state: Bundle?) = track(a.window, a.javaClass.simpleName)
      override fun onActivityResumed(a: Activity) = track(a.window, a.javaClass.simpleName)
      override fun onActivityDestroyed(a: Activity) = untrack(a.window)
      override fun onActivityStarted(a: Activity) {}
      override fun onActivityPaused(a: Activity) {}
      override fun onActivityStopped(a: Activity) {}
      override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    })
  }

  fun track(window: Window, host: String) {
    check(Looper.myLooper() == Looper.getMainLooper())
    windows.removeAll { it.window.get() == null }
    expireOnWake()
    if (windows.none { it.window.get() === window }) {
      if (windows.size == MAX_WINDOWS) {
        current?.let { s -> synchronized(s) { s.rejectedWindows++ } }
        return
      }
      // Inactive tracking must not force decor inflation during Activity creation.
      windows.add(Tracked(WeakReference(window), host))
    }
    current?.let { s -> attach(s, window, host) }
  }

  fun untrack(window: Window) {
    check(Looper.myLooper() == Looper.getMainLooper())
    windows.filter { it.window.get() === window }.forEach { tracked ->
      tracked.attachListener?.let { window.peekDecorView()?.removeOnAttachStateChangeListener(it) }
    }
    windows.removeAll { it.window.get() == null || it.window.get() === window }
    current?.let { s ->
      val removed = s.bindings.filter { it.window.get() == null || it.window.get() === window }
      for (b in removed) b.window.get()?.let { runCatching { it.removeOnFrameMetricsAvailableListener(b.listener) } }
      s.bindings.removeAll(removed.toSet())
      synchronized(s) {
        val entries = s.settingsEntryWindows.filter { it.window.get() == null || it.window.get() === window }
        entries.forEach { it.firstDraw.dispose() }
        s.settingsEntryWindows.removeAll(entries.toSet())
        s.attached = s.bindings.size
      }
    }
  }

  fun start(request: FramePerformanceRequest): Boolean {
    require(request.action == "start")
    return onMain {
      closeCurrent()
      val s = Session(++sequence, request)
      current = s
      s.expiry = Runnable { if (current === s) closeCurrent(expired = true) }
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { expireOnWake() }
      }
      s.wakeReceiver = receiver
      try {
        ContextCompat.registerReceiver(application, receiver, IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED)
      } catch (_: RuntimeException) {
        closeCurrent()
        return@onMain false
      }
      main.postDelayed(s.expiry, request.durationSec * 1000L)
      windows.removeAll { it.window.get() == null }
      for (w in windows) w.window.get()?.let { attach(s, it, w.host) }
      startMainSampling(s)
      true
    }
  }

  fun stop(): Boolean = onMain { closeCurrent(); true }

  fun slideshowWorkEnabled(): Boolean = runCatching {
    val s = current ?: return@runCatching false
    synchronized(s) { current === s && s.closedAt == null && SystemClock.elapsedRealtime() < s.deadline }
  }.getOrDefault(false)

  /** Select at bridge receipt; a queued Main callback cannot write into a later capture. */
  fun prepareSlideshowStyleReport(host: SlideshowStyleHost, style: SlideshowStyle): SlideshowStyleReport? =
      prepareSlideshowStyleReport(host, style) { null }

  fun prepareSlideshowStyleReport(host: SlideshowStyleHost, style: SlideshowStyle,
      observeLayer: () -> SlideshowLayerObservation?): SlideshowStyleReport? {
    val s = current ?: return null
    val received = synchronized(s) {
      if (current !== s || s.closedAt != null) return null
      SystemClock.elapsedRealtime().also { if (it >= s.deadline) return null }
    }
    val accepted = AtomicBoolean(false)
    return SlideshowStyleReport {
      if (accepted.compareAndSet(false, true)) {
        synchronized(s) {
          val now = SystemClock.elapsedRealtime()
          if (current === s && s.closedAt == null && now < s.deadline &&
              Looper.myLooper() === Looper.getMainLooper()) {
            // Read the owned view only on Main and only inside this admitted capture.
            // Optional observation failure must not suppress the style readiness report.
            val layer = runCatching(observeLayer).getOrNull()
            s.slideshowStyles.record(host, style, received - s.began, now - s.began, layer)
          }
        }
      }
    }
  }

  /** Bridge-resolution counts need no Main acceptance or frame/presentation attribution. */
  inline fun <T> measureImageResolution(resolve: (EngineImageResolutionMeasurement?) -> T): T =
      imageResolutionProbe.resolve(resolve)

  private fun imageResolutionMeasurement(): EngineImageResolutionMeasurement? {
    val s = current ?: return null
    synchronized(s) {
      // No clock, token or callbacks when inactive/closed. Selection binds to this capture identity.
      if (current !== s || s.closedAt != null || SystemClock.elapsedRealtime() >= s.deadline) return null
    }
    return EngineImageResolutionMeasurement { result, requested, delivered, maximum ->
      synchronized(s) {
        if (current === s && s.closedAt == null && SystemClock.elapsedRealtime() < s.deadline) {
          s.imageResolution.record(result, requested, delivered, maximum)
        }
      }
    }
  }

  fun beginSlideshowWork(stage: SlideshowWorkStage): SlideshowWorkSpan? = slideshowWorkProbe.begin(stage)
  fun markSlideshowWork(stage: SlideshowWorkStage) = slideshowWorkProbe.mark(stage)

  private fun slideshowWorkMeasurement(stage: SlideshowWorkStage): SlideshowWorkMeasurement? {
    val s = current ?: return null
    synchronized(s) {
      if (current !== s || s.closedAt != null || SystemClock.elapsedRealtime() >= s.deadline) return null
    }
    return SlideshowWorkMeasurement { durationNs ->
      val now = SystemClock.elapsedRealtime()
      synchronized(s) {
        if (current === s && s.closedAt == null && now < s.deadline) {
          s.slideshowWork.record(stage, now - s.began, durationNs,
              Looper.myLooper() === Looper.getMainLooper())
        }
      }
    }
  }

  /**
   * Observe the actual Settings startActivity boundary, rather than intent construction.
   * Inactive navigation keeps the original intent and incurs no diagnostic clock/listener.
   */
  fun launchSettings(context: Context, intent: Intent) {
    val report = beginSettingsLaunch()
    if (report != null) runCatching {
      intent.putExtra(SETTINGS_ENTRY_SESSION, report.sessionId)
      intent.putExtra(SETTINGS_ENTRY_ID, report.entryId)
    }
    try {
      context.startActivity(intent)
    } catch (error: RuntimeException) {
      report?.record(SettingsEntryStage.LAUNCH_FAILED)
      throw error
    }
    report?.record(SettingsEntryStage.LAUNCH_RETURNED)
  }

  private fun admitsSettingsEntry(s: Session): Boolean =
      current === s && s.closedAt == null &&
          (s.request.phase == "settings" || s.request.phase == "all" || s.request.phase == "launch") &&
          SystemClock.elapsedRealtime() < s.deadline

  private fun settingsEntryReport(s: Session, entryId: Long) =
      SettingsEntryReport(s.id, entryId) { stage ->
        synchronized(s) {
          if (!admitsSettingsEntry(s) ||
              (stage != SettingsEntryStage.FIRST_FRAME_RECEIVED &&
                  Looper.myLooper() !== Looper.getMainLooper())) false
          else s.settingsEntryTimeline.record(entryId, stage, SystemClock.elapsedRealtimeNanos() - s.beganNs)
        }
      }

  private fun beginSettingsLaunch(): SettingsEntryReport? = runCatching {
    val s = current ?: return@runCatching null
    synchronized(s) {
      if (s.closedAt != null || s.request.phase !in setOf("settings", "all", "launch") ||
          Looper.myLooper() !== Looper.getMainLooper() || !admitsSettingsEntry(s)) return@runCatching null
      s.settingsEntryTimeline.launchRequested(SystemClock.elapsedRealtimeNanos() - s.beganNs)
          ?.let { settingsEntryReport(s, it) }
    }
  }.getOrNull()

  /** An old intent/callback must never be adopted by a replacement diagnostic session. */
  fun beginSettingsCreate(intent: Intent): SettingsEntryReport? = runCatching {
    val s = current ?: return@runCatching null
    synchronized(s) {
      if (s.closedAt != null || s.request.phase !in setOf("settings", "all", "launch") ||
          Looper.myLooper() !== Looper.getMainLooper() || !admitsSettingsEntry(s)) return@runCatching null
      val launchId = if (intent.hasExtra(SETTINGS_ENTRY_SESSION)) {
        if (intent.getLongExtra(SETTINGS_ENTRY_SESSION, -1L) != s.id) return@runCatching null
        intent.getLongExtra(SETTINGS_ENTRY_ID, -1L)
      } else null
      s.settingsEntryTimeline.createStarted(SystemClock.elapsedRealtimeNanos() - s.beganNs, launchId)
          ?.let { settingsEntryReport(s, it) }
    }
  }.getOrNull()

  fun bindSettingsEntry(window: Window, report: SettingsEntryReport): SettingsEntryFirstDrawObservation? =
      runCatching {
        check(Looper.myLooper() === Looper.getMainLooper())
        val s = current ?: return@runCatching null
        synchronized(s) {
          if (report.sessionId != s.id || !admitsSettingsEntry(s)) return@runCatching null
          val old = s.settingsEntryWindows.filter { it.window.get() == null || it.window.get() === window }
          old.forEach { it.firstDraw.dispose() }
          s.settingsEntryWindows.removeAll(old.toSet())
          if (s.settingsEntryWindows.size >= MAX_WINDOWS) return@runCatching null
          val decor = window.decorView
          val observer = decor.viewTreeObserver
          val observation = SettingsEntryFirstDrawObservation(decor) {
            report.record(SettingsEntryStage.FIRST_PRE_DRAW)
          }
          observer.addOnPreDrawListener(observation)
          s.settingsEntryWindows.add(SettingsEntryWindow(WeakReference(window), report, observation))
          observation
        }
      }.getOrNull()

  fun settingsUiWorkEnabled(): Boolean = runCatching {
    val s = current ?: return@runCatching false
    synchronized(s) { current === s && s.closedAt == null && SystemClock.elapsedRealtime() < s.deadline }
  }.getOrDefault(false)

  fun beginSettingsUiWork(stage: SettingsUiWorkStage): SettingsUiWorkSpan? = settingsUiWorkProbe.begin(stage)
  fun markSettingsUiWork(stage: SettingsUiWorkStage) = settingsUiWorkProbe.mark(stage)

  private fun settingsUiWorkMeasurement(stage: SettingsUiWorkStage): SettingsUiWorkMeasurement? {
    val s = current ?: return null
    synchronized(s) {
      if (current !== s || s.closedAt != null || SystemClock.elapsedRealtime() >= s.deadline) return null
    }
    return SettingsUiWorkMeasurement { durationNs ->
      val now = SystemClock.elapsedRealtime()
      synchronized(s) {
        if (current === s && s.closedAt == null && now < s.deadline) {
          s.settingsUiWork.record(stage, now - s.began, durationNs,
              Looper.myLooper() === Looper.getMainLooper())
        }
      }
    }
  }

  /** The current preparation path is measured only for an explicitly active capture. */
  fun <T> measureSettingsSearchIndex(prepare: () -> List<T>): List<T> =
      settingsSearchPreparationProbe.prepare(prepare)

  private fun settingsSearchPreparationMeasurement(): SettingsSearchPreparationMeasurement? {
    val s = current ?: return null
    synchronized(s) {
      if (current !== s || s.closedAt != null || SystemClock.elapsedRealtime() >= s.deadline) return null
    }
    return SettingsSearchPreparationMeasurement { durationNs, entryCount ->
      val now = SystemClock.elapsedRealtime()
      synchronized(s) {
        if (current === s && s.closedAt == null && now < s.deadline) {
          s.settingsSearchPreparation.record(now - s.began, durationNs, entryCount,
              Looper.myLooper() === Looper.getMainLooper())
        }
      }
    }
  }

  private fun startMainSampling(s: Session) {
    val histogram = s.mainSamples ?: return
    synchronized(s) {
      s.sampler = Runnable { sampleMain(s) }
      s.nextSampleUptime = SystemClock.uptimeMillis()
      s.sampleMainActive = true
      if (!s.handler.post(s.sampler!!)) {
        histogram.recordDroppedTicks(1)
        s.sampleMainActive = false
      }
    }
  }

  /** Stack reads happen on the existing collector Handler, never on the sampled main thread. */
  private fun sampleMain(s: Session) {
    check(Looper.myLooper() == s.handler.looper)
    val histogram = s.mainSamples ?: return
    synchronized(s) {
      if (current !== s || s.closedAt != null || !s.sampleMainActive) return
      if (SystemClock.elapsedRealtime() >= s.deadline) {
        requestExpiry(s)
        return
      }
      if (!histogram.canSample) { s.sampleMainActive = false; return }
    }
    // getStackTrace is public since API 1. The VM returns a transient array; aggregation inspects
    // at most 64 entries and retains only bounded class/method strings. Do not hold the session
    // monitor during the VM read: main-thread stop/snapshot/lifecycle work must remain unblocked.
    val readStarted = SystemClock.elapsedRealtime()
    val stack = try { Looper.getMainLooper().thread.stackTrace } catch (_: RuntimeException) { null }
    val readEnded = SystemClock.elapsedRealtime()
    synchronized(s) {
      val now = SystemClock.elapsedRealtime()
      val accept = current === s && s.closedAt == null && s.sampleMainActive && now < s.deadline
      if (stack == null) histogram.recordError()
      else if (accept) histogram.record(stack, readStarted - s.began, readEnded - s.began)
      else histogram.recordDroppedSample()
      if (!accept) {
        if (current === s && s.closedAt == null && now >= s.deadline) requestExpiry(s)
        return
      }
      if (!histogram.canSample) { s.sampleMainActive = false; return }
      val uptime = SystemClock.uptimeMillis()
      var next = s.nextSampleUptime + MainThreadSampleHistogram.INTERVAL_MS
      if (next < uptime) {
        // Skip elapsed cadence slots instead of flooding the shared FrameMetrics handler.
        val skipped = (uptime - next + MainThreadSampleHistogram.INTERVAL_MS - 1) /
            MainThreadSampleHistogram.INTERVAL_MS
        histogram.recordDroppedTicks(skipped)
        next += skipped * MainThreadSampleHistogram.INTERVAL_MS
      }
      s.nextSampleUptime = next
      if (now + (next - uptime) >= s.deadline) {
        s.sampleMainActive = false
      } else if (!s.handler.postAtTime(s.sampler!!, next)) {
        histogram.recordDroppedTicks(1)
        s.sampleMainActive = false
      }
    }
  }

  /** Caller holds the session monitor; reuse the owned expiry callback so close can remove it. */
  private fun requestExpiry(s: Session) {
    s.sampleMainActive = false
    if (!s.stopRequested) {
      s.stopRequested = true
      main.post(s.expiry)
    }
  }

  private fun attach(s: Session, window: Window, host: String) {
    if (synchronized(s) { s.closedAt != null } || SystemClock.elapsedRealtime() >= s.deadline) return
    val tracked = windows.firstOrNull { it.window.get() === window }
    if (tracked != null && tracked.attachListener == null) {
      val weak = tracked.window
      val observer = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { weak.get()?.let { track(it, host) } }
        override fun onViewDetachedFromWindow(v: View) {}
      }
      window.decorView.addOnAttachStateChangeListener(observer)
      tracked.attachListener = observer
    }
    val refresh = runCatching { window.decorView.display?.refreshRate?.toDouble() }.getOrNull()
    @Suppress("DEPRECATION") // API 24–29: the window's manager has a display before decor attachment.
    val managerRefresh = runCatching { window.windowManager?.defaultDisplay?.refreshRate?.toDouble() }.getOrNull()
    val budget = FramePerformanceBudget.nanos(refresh, managerRefresh)
    val existing = s.bindings.firstOrNull { it.window.get() === window }
    if (existing?.budgetNs == budget) return
    if (existing != null) {
      runCatching { window.removeOnFrameMetricsAvailableListener(existing.listener) }
      s.bindings.remove(existing)
      synchronized(s) { s.attached = s.bindings.size }
    }
    val key = "$host@$budget"
    val histogram = synchronized(s) {
      s.histograms[key] ?: if (s.histograms.size < MAX_HOSTS) {
        FramePerformanceHistogram(budget, s.request.slowFrameThresholdNs).also { s.histograms[key] = it }
      } else { s.rejectedHosts++; null }
    } ?: return
    // One reusable buffer per active binding; paired detail copies only a new worst frame.
    val componentBuffer = LongArray(componentIds.size)
    val listener = Window.OnFrameMetricsAvailableListener { frameWindow, metrics, dropped ->
      val now = SystemClock.elapsedRealtime()
      synchronized(s) {
        if (current !== s || s.closedAt != null) return@OnFrameMetricsAvailableListener
        if (now >= s.deadline) {
          requestExpiry(s)
          return@OnFrameMetricsAvailableListener
        }
        for (i in componentIds.indices) componentBuffer[i] = metrics.getMetric(componentIds[i])
        // Receipt time is not vsync/presentation time; each component is from this same callback.
        val firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
        histogram.recordDetailed(metrics.getMetric(FrameMetrics.TOTAL_DURATION), dropped,
            firstDraw, now - s.began, componentBuffer)
        if (firstDraw && host == ImmortalSettingsActivity::class.java.simpleName) {
          s.settingsEntryWindows.firstOrNull { it.window.get() === frameWindow }
              ?.report?.record(SettingsEntryStage.FIRST_FRAME_RECEIVED)
        }
      }
    }
    runCatching {
      window.addOnFrameMetricsAvailableListener(listener, s.handler)
      s.bindings.add(Binding(WeakReference(window), budget, listener))
      synchronized(s) { s.attached = s.bindings.size }
    }.onFailure { synchronized(s) { s.attachFailures++ } }
  }

  /** Main-thread removal, no blocking join and no shared/global renderer controls. */
  private fun closeCurrent(expired: Boolean = false) {
    val s = current ?: return
    synchronized(s) {
      if (s.closedAt != null) return
      s.closedAt = if (expired) minOf(SystemClock.elapsedRealtime(), s.deadline) else SystemClock.elapsedRealtime()
      s.sampleMainActive = false
      // Remove only this session's sampler; do not disturb other collector/profiler callbacks.
      s.sampler?.let { s.handler.removeCallbacks(it) }
      s.sampler = null
    }
    main.removeCallbacks(s.expiry)
    s.wakeReceiver?.let { runCatching { application.unregisterReceiver(it) } }
    s.wakeReceiver = null
    for (b in s.bindings) b.window.get()?.let { runCatching { it.removeOnFrameMetricsAvailableListener(b.listener) } }
    s.bindings.clear()
    synchronized(s) {
      s.settingsEntryWindows.forEach { it.firstDraw.dispose() }
      s.settingsEntryWindows.clear()
    }
    for (tracked in windows) {
      tracked.attachListener?.let { observer ->
        tracked.window.get()?.peekDecorView()?.removeOnAttachStateChangeListener(observer)
      }
      tracked.attachListener = null
    }
    synchronized(s) { s.attached = 0 }
    s.thread.quitSafely()
  }

  private fun expireOnWake() {
    current?.let { s ->
      if (SystemClock.elapsedRealtime() >= s.deadline) closeCurrent(expired = true)
    }
  }

  fun snapshot(): JSONObject = snapshot(includeMetrics = true)

  /** Live, expiring ownership state for repeated safety checks; no histograms or memory sampling. */
  fun stateSnapshot(): JSONObject = snapshot(includeMetrics = false)

  /** Same bounded promotion records as the full report, with ownership but no frame/memory reads. */
  fun readinessSnapshot(): JSONObject = snapshot(includeMetrics = false, includeReadiness = true)

  private fun snapshot(includeMetrics: Boolean, includeReadiness: Boolean = false): JSONObject {
    val s = current
    val now = SystemClock.elapsedRealtime()
    if (s != null && now >= s.deadline) synchronized(s) {
      if (s.closedAt == null && !s.stopRequested) {
        requestExpiry(s)
      }
    }
    // This immutable state read never constructs a coordinator, executor or WebView.
    val startup = WebViewStartupPreparation.snapshot()
    val result = JSONObject().put("ok", true).put("scope",
        if (includeMetrics) "Immortal Window host metrics" else "Immortal capture ownership state")
        .put("deliveredFpsMeasured", false).put("maxHosts", MAX_HOSTS).put("maxLiveWindows", MAX_WINDOWS)
        .put("webViewBackgroundStartup", JSONObject()
            .put("enabled", BuildConfig.PORTAL_WEBVIEW_BACKGROUND_STARTUP)
            .put("pending", startup.pending).put("attempted", startup.attempted)
            .put("completed", startup.completed).put("failed", startup.failed))
    if (includeMetrics || includeReadiness) {
      FilmstripRuntimeProbe.snapshot()?.let { result.put("nativeFilmstrip", it) }
    }
    if (s == null) {
      result.put("active", false).put("collectorRunning", false)
          .put("sampleMain", false).put("sampleMainActive", false).put("activeListeners", 0)
      if (includeMetrics) result.put("hosts", JSONObject())
      return result
    }
    synchronized(s) {
      result.put("sessionId", s.id).put("phase", s.request.phase)
          .put("durationSec", s.request.durationSec)
          .put("sampleMain", s.request.sampleMain)
          .put("sampleMainActive", s.sampleMainActive && s.closedAt == null && now < s.deadline)
          .put("active", s.closedAt == null && now < s.deadline)
          .put("elapsedMs", (minOf(s.closedAt ?: now, s.deadline) - s.began).coerceAtLeast(0))
          .put("remainingMs", if (s.closedAt == null) (s.deadline - now).coerceAtLeast(0) else 0)
          .put("collectorRunning", s.thread.isAlive).put("activeListeners", s.attached)
          .put("untrackedHosts", s.rejectedHosts).put("untrackedWindows", s.rejectedWindows)
          .put("attachFailures", s.attachFailures)
      if (!includeMetrics) {
        if (includeReadiness) result.put("slideshowStyles", s.slideshowStyles.snapshot())
        return result
      }
      result.put("hosts", JSONObject().apply { s.histograms.forEach { (name, h) -> put(name, h.snapshot()) } })
      result.put("settingsSearchPreparation", s.settingsSearchPreparation.snapshot())
      result.put("settingsUiWork", s.settingsUiWork.snapshot())
      result.put("settingsEntryTimeline", s.settingsEntryTimeline.snapshot())
      result.put("slideshowWork", s.slideshowWork.snapshot())
      result.put("slideshowStyles", s.slideshowStyles.snapshot())
      result.put("imageResolution", s.imageResolution.snapshot())
      s.mainSamples?.let { result.put("mainThreadSamples", it.snapshot()) }
    }
    // Sample only on explicit reads, outside the per-frame lock/handler.
    val memory = Debug.MemoryInfo()
    Debug.getMemoryInfo(memory)
    val runtime = Runtime.getRuntime()
    return result.put("memory", JSONObject().put("pssKiB", memory.totalPss)
        .put("nativeBytes", Debug.getNativeHeapAllocatedSize())
        .put("javaBytes", runtime.totalMemory() - runtime.freeMemory())
        .put("graphicsKiB", memory.getMemoryStat("summary.graphics")?.toLongOrNull()
            ?.takeIf { it >= 0 } ?: JSONObject.NULL))
  }

  private fun onMain(action: () -> Boolean): Boolean {
    if (Looper.myLooper() == Looper.getMainLooper()) return action()
    val gate = FramePerformanceCommandGate()
    val task = FutureTask { if (gate.begin()) action() else false }
    if (!main.post(task)) return false
    return try { task.get(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      if (gate.cancelBeforeBegin()) { task.cancel(false); false }
      else throw FramePerformanceCommandPending()
    } catch (_: TimeoutException) {
      if (gate.cancelBeforeBegin()) { task.cancel(false); false }
      else throw FramePerformanceCommandPending()
    } catch (_: Exception) { false }
  }

  private val componentIds = intArrayOf(FrameMetrics.UNKNOWN_DELAY_DURATION,
      FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
      FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
      FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION)
}
