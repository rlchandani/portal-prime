/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Real detached overlay/worker admission only; generated producer, no window or network proof. */
internal object FaceWeatherAdmissionDeviceChecks {
  private const val WORK_MS = 12_000L
  private const val TOTAL_MS = 15_000L
  private const val REFRESH_MS = 30 * 60_000L

  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + WORK_MS
    val totalUntil = began + TOTAL_MS
    val token = UUID.randomUUID().toString()
    val prefix = "face-weather-admission-owned-$token"
    val prefsName = prefix
    val reportFile = File(probe.targetContext.cacheDir, "$prefix.json")
    val checks = JSONArray()
    val cases = JSONArray()
    val owned = ConcurrentLinkedQueue<FaceRenderer>()
    val closing = AtomicBoolean(false)
    val handler = Handler(Looper.getMainLooper())
    var failures = 0
    var isolated = 0
    var completed = 0
    val reportLock = Any()
    var recording = true
    fun expect(name: String, passed: Boolean) = synchronized(reportLock) {
      if (recording) {
        checks.put(JSONObject().put("name", name).put("passed", passed))
        if (!passed) failures++
      }
    }
    fun remaining(cleanup: Boolean = false, limit: Long = 1_500L): Long =
        minOf(limit, (if (cleanup) totalUntil else workUntil) -
            SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val done = CountDownLatch(1)
      val cancelled = AtomicBoolean(false)
      var value: T? = null
      var failure: Throwable? = null
      check(remaining(cleanup) > 0)
      handler.post {
        try {
          if (!cancelled.get() && (cleanup || !closing.get())) value = block()
        } catch (thrown: Throwable) { failure = thrown }
        finally { done.countDown() }
      }
      if (!done.await(remaining(cleanup), TimeUnit.MILLISECONDS)) {
        cancelled.set(true)
        error("owned_main_timeout")
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return value as T
    }
    fun executor(renderer: FaceRenderer) = field(renderer, "io") as ExecutorService
    fun drain(renderer: FaceRenderer) {
      val barrier = executor(renderer).submit {}
      try { barrier.get(remaining(), TimeUnit.MILLISECONDS) }
      finally { if (!barrier.isDone) barrier.cancel(true) }
      // Producer completion posts its UI result before the worker barrier executes.
      main { Unit }
    }
    fun invokeRefresh(renderer: FaceRenderer) {
      main { (field(renderer, "refreshWeather") as Runnable).run() }
      drain(renderer)
    }
    fun verifyAdmission(renderer: FaceRenderer, name: String, enabled: Boolean,
        active: Boolean = true) {
      main {
        expect("$name.weather_view_matches_spec", (field(renderer, "weatherView") != null) == enabled)
        expect("$name.no_now_playing_listener", field(renderer, "npListener") == null)
        expect("$name.no_now_playing_card", field(renderer, "nowPlayingCard") == null)
        expect("$name.anti_burn_in_disabled", field(renderer, "antiBurnInOn") == false)
        expect("$name.detached_overlay",
            !renderer.view.isAttachedToWindow && renderer.view.parent == null)
        if (Build.VERSION.SDK_INT >= 29) {
          val ui = field(renderer, "ui") as Handler
          val refresh = field(renderer, "refreshWeather") as Runnable
          expect("$name.weather_callback_matches_spec", ui.hasCallbacks(refresh) == (enabled && active))
        }
      }
    }
    val prefs = probe.targetContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val scoped = object : ContextWrapper(probe.targetContext) {
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
      override fun getApplicationContext(): Context = this
    }
    expect("preflight.worker_off_main", Looper.myLooper() != Looper.getMainLooper())
    expect("preflight.owned_preferences_initialized", prefs.edit().clear()
        .putBoolean("show_now_playing", false).putBoolean("anti_burn_in", false).commit())
    try {
      if (Looper.myLooper() != Looper.getMainLooper()) {
        for ((id, enabled) in listOf("big" to false, "bold" to false,
            "minimal" to false, "enabled-control" to true)) {
          if (remaining() <= 0) break
          val row = JSONObject().put("name", id).put("weatherEnabled", enabled)
          cases.put(row)
          val calls = AtomicInteger()
          val producerContextsMatch = AtomicBoolean(true)
          val producerOffMain = AtomicBoolean(true)
          var renderer: FaceRenderer? = null
          try {
            val made = main {
              val actual = if (enabled) {
                FaceCatalog.entryFor("minimal").build(scoped).copy(
                    id = "generated-weather-enabled",
                    weather = WeatherSpec(enabled = true))
              } else FaceCatalog.entryFor(id).build(scoped)
              expect("$id.actual_catalog_spec", actual.weather.enabled == enabled &&
                  (enabled || actual.id == id))
              val host = FaceRenderer(scoped, REFRESH_MS) { received ->
                if (received !== scoped) producerContextsMatch.set(false)
                if (Looper.myLooper() == Looper.getMainLooper()) producerOffMain.set(false)
                "Generated weather 42 / ${calls.incrementAndGet()}"
              }
              owned.add(host)
              if (closing.get()) host.stop() else host.start(actual)
              host
            }
            renderer = made
            val overlay = main { made.view }
            val clock = main { field(made, "clockFace") }
            val weatherView = main { field(made, "weatherView") }
            drain(made)
            val startCalls = calls.get()
            row.put("startCalls", startCalls)
            expect("$id.start_fetch_count", startCalls == if (enabled) 1 else 0)
            verifyAdmission(made, "$id.start", enabled)
            if (!enabled) {
              invokeRefresh(made)
              expect("$id.direct_active_refresh_fetch_count", calls.get() == 0)
              verifyAdmission(made, "$id.direct-refresh", false)
            }
            val beforeHideCalls = calls.get()
            main { made.setHostActive(false) }
            invokeRefresh(made)
            expect("$id.hidden_fetch_count_unchanged", calls.get() == beforeHideCalls)
            verifyAdmission(made, "$id.hidden", enabled, active = false)
            // Hidden admission has no callback, but the actual weather view remains retained.
            main {
              expect("$id.hidden_weather_view_retained", field(made, "weatherView") === weatherView)
            }
            main { made.setHostActive(true) }
            drain(made)
            val resumeCalls = calls.get()
            row.put("resumeCalls", resumeCalls)
            expect("$id.resume_fetch_count", resumeCalls == if (enabled) 2 else 0)
            verifyAdmission(made, "$id.resume", enabled)
            main {
              expect("$id.overlay_identity_retained", made.view === overlay)
              expect("$id.clock_identity_retained", field(made, "clockFace") === clock)
              expect("$id.weather_view_identity_retained", field(made, "weatherView") === weatherView)
              if (enabled) expect("$id.latest_generated_completion",
                  field(made, "weatherText") == "Generated weather 42 / 2")
            }
            expect("$id.producer_context_is_scoped", producerContextsMatch.get())
            expect("$id.producer_off_main", producerOffMain.get())
            main { made.stop() }
            val stoppedCalls = calls.get()
            main {
              made.setHostActive(true)
              (field(made, "refreshWeather") as Runnable).run()
            }
            expect("$id.stopped_fetch_count_unchanged", calls.get() == stoppedCalls)
            expect("$id.worker_terminated",
                executor(made).awaitTermination(remaining(cleanup = true), TimeUnit.MILLISECONDS))
            main {
              expect("$id.stop_releases_receiver", field(made, "batteryReceiver") == null)
              expect("$id.stop_releases_listener", field(made, "npListener") == null)
              expect("$id.stop_clears_weather_request", field(made, "weatherRequest") == null)
              if (Build.VERSION.SDK_INT >= 29) expect("$id.stop_removes_weather_callback",
                  !(field(made, "ui") as Handler).hasCallbacks(field(made, "refreshWeather") as Runnable))
            }
            row.put("completed", true).put("producerCalls", calls.get())
            completed++
          } catch (_: Throwable) {
            isolated++
            expect("$id.isolated_failure", false)
          } finally {
            renderer?.let { host ->
              runCatching { main(cleanup = true) { host.stop() } }
                  .onFailure { expect("$id.final_stop", false) }
            }
          }
        }
      }
    } finally {
      closing.set(true)
      try {
        main(cleanup = true) { owned.forEach { it.stop() } }
        expect("cleanup.all_workers_terminated", owned.all {
          executor(it).awaitTermination(remaining(cleanup = true), TimeUnit.MILLISECONDS)
        })
      } catch (_: Throwable) {
        expect("cleanup.owned_renderers_stopped", false)
        // One owned cleanup action handles a late main task without creating an observer.
        handler.post { owned.forEach { it.stop() } }
      }
      val safe = prefsName.matches(Regex("face-weather-admission-owned-[0-9a-f-]{36}"))
      expect("cleanup.owned_preferences_removed",
          safe && probe.targetContext.deleteSharedPreferences(prefsName))
    }
    expect("budget.within_15_seconds", SystemClock.elapsedRealtime() - began <= TOTAL_MS)
    synchronized(reportLock) { recording = false }
    val passed = failures == 0 && isolated == 0 && completed == 4
    val report = JSONObject().put("checks", checks).put("cases", cases)
        .put("caseCount", completed).put("checkCount", checks.length()).put("failureCount", failures)
        .put("isolatedFailureCount", isolated).put("passed", passed)
        .put("scope", "detached_renderer_producer_admission").put("generatedProducer", true)
        .put("networkProof", false).put("windowProof", false).put("fpsProof", false)
        .put("audioProof", false).put("workBudgetMs", WORK_MS).put("totalBudgetMs", TOTAL_MS)
        .put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("relativeReportPath", "cache/${reportFile.name}")
    var written = false
    try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
    catch (_: Throwable) { /* Fixed labels/counts only; no exception text or private diagnostics. */ }
    return Bundle().apply {
      putBoolean("faceWeatherAdmissionCompleted", true)
      putBoolean("faceWeatherAdmissionPassed", passed && written)
      putInt("faceWeatherAdmissionCaseCount", completed)
      putInt("faceWeatherAdmissionCheckCount", checks.length())
      putInt("faceWeatherAdmissionFailureCount", failures)
      putInt("faceWeatherAdmissionIsolatedFailureCount", isolated)
      putString("faceWeatherAdmissionChecksJson", checks.toString())
      putString("faceWeatherAdmissionReportRelativePath", "cache/${reportFile.name}")
    }
  }

  private fun field(renderer: FaceRenderer, name: String): Any? =
      FaceRenderer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
}
