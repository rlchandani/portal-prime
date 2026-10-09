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
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/** Explicit, expiring Fleet diagnostics. No collector thread or listener while inactive. */
internal object FramePerformanceCapture {
  private const val MAX_WINDOWS = 24
  private const val MAX_HOSTS = 128
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private var initialized = false
  private lateinit var application: Application
  // Main-thread owned; weak values do not retain Activity/Dream contexts after destruction.
  private val windows = ArrayList<Tracked>()
  private var sequence = 0L
  @Volatile private var current: Session? = null
  private data class Tracked(val window: WeakReference<Window>, val host: String,
      var attachListener: View.OnAttachStateChangeListener? = null)
  private data class Binding(val window: WeakReference<Window>, val budgetNs: Long,
      val listener: Window.OnFrameMetricsAvailableListener)
  private class Session(val id: Long, val request: FramePerformanceRequest) {
    val began = SystemClock.elapsedRealtime()
    val deadline = began + request.durationSec * 1000L
    val thread = HandlerThread("immortal-perf", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    val handler = Handler(thread.looper)
    val histograms = LinkedHashMap<String, FramePerformanceHistogram>()
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
      synchronized(s) { s.attached = s.bindings.size }
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
      true
    }
  }

  fun stop(): Boolean = onMain { closeCurrent(); true }

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
        FramePerformanceHistogram(budget).also { s.histograms[key] = it }
      } else { s.rejectedHosts++; null }
    } ?: return
    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
      val now = SystemClock.elapsedRealtime()
      synchronized(s) {
        if (current !== s || s.closedAt != null) return@OnFrameMetricsAvailableListener
        if (now >= s.deadline) {
          if (!s.stopRequested) {
            s.stopRequested = true
            main.post { if (current === s) closeCurrent(expired = true) }
          }
          return@OnFrameMetricsAvailableListener
        }
        if (histogram.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION), dropped,
            metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)) {
          for (i in componentIds.indices) histogram.component(i, metrics.getMetric(componentIds[i]))
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
    }
    main.removeCallbacks(s.expiry)
    s.wakeReceiver?.let { runCatching { application.unregisterReceiver(it) } }
    s.wakeReceiver = null
    for (b in s.bindings) b.window.get()?.let { runCatching { it.removeOnFrameMetricsAvailableListener(b.listener) } }
    s.bindings.clear()
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

  fun snapshot(): JSONObject {
    val s = current
    val now = SystemClock.elapsedRealtime()
    if (s != null && now >= s.deadline) synchronized(s) {
      if (s.closedAt == null && !s.stopRequested) {
        s.stopRequested = true
        main.post { if (current === s) closeCurrent(expired = true) }
      }
    }
    val result = JSONObject().put("ok", true).put("scope", "Immortal Window host metrics")
        .put("deliveredFpsMeasured", false).put("maxHosts", MAX_HOSTS).put("maxLiveWindows", MAX_WINDOWS)
    if (s == null) return result.put("active", false).put("collectorRunning", false)
        .put("activeListeners", 0).put("hosts", JSONObject())
    synchronized(s) {
      result.put("sessionId", s.id).put("phase", s.request.phase)
          .put("durationSec", s.request.durationSec)
          .put("active", s.closedAt == null && now < s.deadline)
          .put("elapsedMs", (minOf(s.closedAt ?: now, s.deadline) - s.began).coerceAtLeast(0))
          .put("remainingMs", if (s.closedAt == null) (s.deadline - now).coerceAtLeast(0) else 0)
          .put("collectorRunning", s.thread.isAlive).put("activeListeners", s.attached)
          .put("untrackedHosts", s.rejectedHosts).put("untrackedWindows", s.rejectedWindows)
          .put("attachFailures", s.attachFailures)
          .put("hosts", JSONObject().apply { s.histograms.forEach { (name, h) -> put(name, h.snapshot()) } })
    }
    // Sample only on explicit reads, outside the per-frame lock/handler.
    val memory = Debug.MemoryInfo()
    Debug.getMemoryInfo(memory)
    val runtime = Runtime.getRuntime()
    return result.put("memory", JSONObject().put("pssKiB", memory.totalPss)
        .put("nativeBytes", Debug.getNativeHeapAllocatedSize())
        .put("javaBytes", runtime.totalMemory() - runtime.freeMemory()))
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
