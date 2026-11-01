/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Shortest-interval real controller playback; no hardware sensor/brightness stimulus. */
internal object NightPhotoPauseDeviceChecks {
  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    var deadline = began + 70_000
    val token = "night-photo-owned-${UUID.randomUUID()}"
    val name = "com.immortal.launcher.NightPhotoPreviewActivity"
    val app = probe.targetContext.applicationContext as Application
    val handler = Handler(Looper.getMainLooper())
    val closing = AtomicBoolean()
    val owned = mutableListOf<Activity>()
    val checks = JSONArray()
    val measurements = JSONObject()
    var failed = false
    var host: Activity? = null
    fun expect(label: String, value: Boolean) {
      checks.put(JSONObject().put("name", label).put("passed", value))
      if (!value) failed = true
    }
    fun <T> main(block: () -> T): T {
      val task = FutureTask(Callable(block))
      check(handler.post(task))
      try { return task.get(minOf(2000, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) }
      finally { handler.removeCallbacks(task); task.cancel(false) }
    }
    fun field(target: Any, key: String): Any? =
        target.javaClass.getDeclaredField(key).apply { isAccessible = true }.get(target)
    fun controller(): PhotoFrameController = field(host!!, "frame") as PhotoFrameController
    fun stage(): SlideshowStage = field(controller(), "nativeStage") as SlideshowStage
    fun boolean(key: String, value: Boolean) {
      controller().javaClass.getDeclaredMethod(key, Boolean::class.javaPrimitiveType)
          .apply { isAccessible = true }.invoke(controller(), value)
    }
    fun poll(timeout: Long, condition: () -> Boolean) {
      val end = minOf(deadline, SystemClock.elapsedRealtime() + timeout)
      while (SystemClock.elapsedRealtime() < end) {
        if (condition()) return
        SystemClock.sleep(50)
      }
      error("bounded condition expired")
    }
    fun settle(night: Boolean) = poll(5000) { main {
      val transition = field(controller(), "nightTransition") as NightClockTransition
      transition.reveal == if (night) 1f else 0f
    } }
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name == name && activity.intent.getStringExtra("owned_probe") == token) {
          owned.add(activity)
          if (closing.get()) activity.finish()
        }
      }
      override fun onActivityStarted(a: Activity) = Unit
      override fun onActivityResumed(a: Activity) = Unit
      override fun onActivityPaused(a: Activity) = Unit
      override fun onActivityStopped(a: Activity) = Unit
      override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
      override fun onActivityDestroyed(a: Activity) = Unit
    }
    val monitor = probe.addMonitor(name, null, false)
    try {
      main { app.registerActivityLifecycleCallbacks(lifecycle) }
      main { probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, name)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("owned_probe", token)) }
      host = monitor.waitForActivityWithTimeout(5000)
      check(host != null)
      poll(8000) { main {
        @Suppress("UNCHECKED_CAST")
        val deck = field(controller(), "localDeckKeys") as List<String>
        val pause = field(controller(), "photoPause") as SlideshowPhotoPause<*>
        deck.size == 3 && field(controller(), "localMode") == true && !pause.pending &&
            stage().currentPhoto?.sourceKey == deck.first() &&
            (field(controller(), "holdDeadline") as Long) > SystemClock.uptimeMillis()
      } }
      expect("native_window", main {
        host!!.window.decorView.width == 2160 && host!!.window.decorView.height == 1440 })
      var retained: Bitmap? = null
      var source: String? = null
      var expectedNext: String? = null
      var generation = 0
      var remaining = 0L
      // Enter partway through a dwell: restarting a complete dwell must fail this check.
      SystemClock.sleep(2000)
      main {
        retained = stage().currentPhoto!!.bitmap
        source = stage().currentPhoto!!.sourceKey
        remaining = (field(controller(), "holdDeadline") as Long) - SystemClock.uptimeMillis()
        expect("shortest_interval_selected", ScreensaverConfig.load(stage().context).intervalSec == 5)
        expect("remaining_partial_dwell", remaining in 2000..3500)
        @Suppress("UNCHECKED_CAST")
        val deck = field(controller(), "localDeckKeys") as List<String>
        val admission = field(controller(), "photoPause") as SlideshowPhotoPause<*>
        val ready = source == deck.first() && !admission.pending &&
            field(controller(), "localMode") == true
        expect("entry_generated_first_photo_ready", ready)
        check(ready)
        expectedNext = deck[1]
        boolean("setAmbientNight", true)
        generation = field(controller(), "gen") as Int
        expect("entry_immediately_suspends_photos", !(field(controller(), "hostActive") as Boolean))
      }
      settle(true)
      val nightBegan = SystemClock.elapsedRealtime()
      var samples = 0
      while (SystemClock.elapsedRealtime() - nightBegan < 10_250) {
        main {
          expect("night_sample_${samples++}", stage().currentPhoto!!.bitmap === retained &&
              stage().currentPhoto!!.sourceKey == source && !retained!!.isRecycled &&
              field(controller(), "gen") == generation &&
              !(field(controller(), "hostActive") as Boolean) &&
              !(field(controller(), "paused") as Boolean))
        }
        SystemClock.sleep(250)
      }
      measurements.put("nightHoldMs", SystemClock.elapsedRealtime() - nightBegan)
          .put("nightSamples", samples).put("remainingDwellAtEntryMs", remaining)
      main { boolean("setAmbientNight", false) }
      var returnSamples = 0
      poll(5000) { main {
        expect("return_sample_${returnSamples++}", stage().currentPhoto!!.bitmap === retained &&
            field(controller(), "gen") == generation)
        field(controller(), "hostActive") == true
      } }
      val resumed = SystemClock.elapsedRealtime()
      measurements.put("returnSamples", returnSamples)
      while (SystemClock.elapsedRealtime() - resumed < 2000) {
        main { expect("retained_dwell", stage().currentPhoto!!.bitmap === retained) }
        SystemClock.sleep(250)
      }
      var requestAt = 0L
      poll(4000) { main {
        if (requestAt == 0L && field(controller(), "gen") != generation)
          requestAt = SystemClock.elapsedRealtime()
        stage().currentPhoto!!.bitmap !== retained
      } }
      val advanceMs = SystemClock.elapsedRealtime() - resumed
      measurements.put("firstAdvanceAfterRevealMs", advanceMs)
          .put("firstRequestAfterRevealMs", requestAt - resumed)
      expect("request_remainder_preserved", requestAt - resumed in (remaining - 200)..(remaining + 200))
      expect("dwell_remainder_preserved", advanceMs in (remaining - 200)..(remaining + 600))
      expect("ordinary_autoplay_resumed_without_skipping", main {
        stage().currentPhoto!!.sourceKey == expectedNext })
      main { boolean("setPaused", true); retained = stage().currentPhoto!!.bitmap
        boolean("setAmbientNight", true) }
      settle(true)
      main { controller().setHostActive(false); controller().setHostActive(true)
        expect("external_cover_does_not_resume_night", field(controller(), "hostActive") == false)
        boolean("setAmbientNight", false) }
      settle(false)
      main { expect("user_pause_survives_night", field(controller(), "paused") == true &&
          stage().currentPhoto!!.bitmap === retained) }
      SystemClock.sleep(5500)
      main {
        expect("user_paused_beyond_interval", stage().currentPhoto!!.bitmap === retained)
        boolean("setAmbientNight", true)
      }
      settle(true)
      main { boolean("setPaused", false)
        expect("resume_under_night_keeps_cover", field(controller(), "hostActive") == false) }
      SystemClock.sleep(5500)
      main { expect("resume_under_night_keeps_photo", stage().currentPhoto!!.bitmap === retained)
        boolean("setAmbientNight", false) }
      settle(false)
      expect("resume_after_cover_keeps_same_photo", main { stage().currentPhoto!!.bitmap === retained })
      poll(6000) { main { stage().currentPhoto!!.bitmap !== retained } }
      expect("autoplay_after_resume_under_cover", main {
        @Suppress("UNCHECKED_CAST")
        val deck = field(controller(), "localDeckKeys") as List<String>
        stage().currentPhoto!!.sourceKey == deck[2]
      })
    } catch (error: Throwable) {
      failed = true
      measurements.put("failureClass", error.javaClass.simpleName)
    } finally {
      deadline = began + 80_000
      try {
        closing.set(true)
        main {
          Class.forName(name).getMethod("cancelOwnedProbe", String::class.java).invoke(null, token)
          owned.forEach { it.finish() }
        }
        poll(5000) { main { owned.all { it.isDestroyed } } }
        expect("owned_activity_destroyed", main { owned.isNotEmpty() && owned.all { it.isDestroyed } })
        expect("generated_files_removed", !File(probe.targetContext.cacheDir, token).exists())
        val prefs = File(probe.targetContext.applicationInfo.dataDir, "shared_prefs")
        expect("isolated_preferences_removed", prefs.listFiles().orEmpty().none { it.name.startsWith(token) })
      } catch (_: Throwable) { expect("cleanup_completed", false) }
      try { main { app.unregisterActivityLifecycleCallbacks(lifecycle) }; probe.removeMonitor(monitor) }
      catch (_: Throwable) { expect("observer_removed", false) }
    }
    measurements.put("elapsedMs", SystemClock.elapsedRealtime() - began)
    val file = File(probe.targetContext.cacheDir, "$token.json")
    file.writeText(JSONObject().put("passed", !failed).put("checks", checks)
        .put("measurements", measurements).put("sensorStimulated", false)
        .put("hardwareBrightnessTested", false).put("productionChanged", false).toString(2))
    return Bundle().apply {
      putBoolean("nightPhotoPausePassed", !failed && SystemClock.elapsedRealtime() <= deadline)
      putString("nightPhotoPauseReportRelativePath", "cache/${file.name}")
    }
  }
}
