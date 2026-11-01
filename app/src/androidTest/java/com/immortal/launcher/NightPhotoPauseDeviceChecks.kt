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
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
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
  fun run(probe: Instrumentation, motion: Boolean = false): Bundle {
    val began = SystemClock.elapsedRealtime()
    var deadline = began + if (motion) 85_000 else 70_000
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
    fun layers(): List<FrameLayout> = (field(stage(), "layers") as Array<*>)
        .map { it as FrameLayout }
    @Suppress("UNCHECKED_CAST")
    fun moves(): List<AnimatorSet> = layers().flatMap {
      field(it, "moves") as List<AnimatorSet>
    }
    fun pose(): List<Number> = layers().flatMap { layer ->
      buildList {
        add(layer.alpha)
        for (index in 0 until layer.childCount) {
          val image = (layer.getChildAt(index) as FrameLayout).getChildAt(0) as ImageView
          add(image.scaleX); add(image.scaleY)
          add(image.translationX); add(image.translationY)
        }
        @Suppress("UNCHECKED_CAST")
        val animations = field(layer, "moves") as List<AnimatorSet>
        animations.forEach { add(it.currentPlayTime) }
      }
    }
    fun freeze(label: String, reference: List<Number>, covered: Boolean = true) {
      expect(label, pose() == reference && moves().isNotEmpty() &&
          moves().all { it.isPaused } && (if (covered)
            field(controller(), "hostActive") == false else field(controller(), "paused") == true))
    }
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
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("owned_probe", token)
          .putExtra("photo_motion", motion)) }
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
      var retainedPose: List<Number> = emptyList()
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
        if (motion) {
          expect("motion_enabled_in_isolated_profile", ScreensaverConfig.load(stage().context).photoMotion)
          expect("photo_motion_running_before_cover", moves().isNotEmpty() &&
              moves().all { it.isStarted && !it.isPaused && it.currentPlayTime > 1000 })
          measurements.put("motionBeforeCoverMs", moves().firstOrNull()?.currentPlayTime)
        }
        expectedNext = deck[1]
        boolean("setAmbientNight", true)
        generation = field(controller(), "gen") as Int
        expect("entry_immediately_suspends_photos", !(field(controller(), "hostActive") as Boolean))
        if (motion) {
          retainedPose = pose()
          freeze("motion_paused_at_entry", retainedPose)
        }
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
          if (motion) freeze("motion_night_sample_$samples", retainedPose)
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
        if (motion && field(controller(), "hostActive") == false)
          freeze("motion_return_sample_$returnSamples", retainedPose)
        field(controller(), "hostActive") == true
      } }
      val resumed = SystemClock.elapsedRealtime()
      measurements.put("returnSamples", returnSamples)
      if (motion) main {
        expect("motion_resumed_after_reveal", moves().isNotEmpty() &&
            moves().all { !it.isPaused })
        measurements.put("motionAtResumeMs", moves().firstOrNull()?.currentPlayTime)
        expect("motion_time_retained_at_resume",
            (moves().first().currentPlayTime -
                measurements.getLong("motionBeforeCoverMs")) in 0..150)
      }
      if (motion) {
        val resumedPose = main { pose() }
        val resumedTime = main { moves().first().currentPlayTime }
        SystemClock.sleep(250)
        main {
          expect("motion_continues_before_next_photo", pose() != resumedPose &&
              moves().first().currentPlayTime - resumedTime in 100..600 &&
              stage().currentPhoto!!.bitmap === retained)
          measurements.put("motionContinuationMs", moves().first().currentPlayTime - resumedTime)
        }
      }
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
      if (motion) {
        var blend: ObjectAnimator? = null
        var blendPose: List<Number> = emptyList()
        var blendTime = 0L
        var blendFraction = 0f
        main {
          blend = field(stage(), "fade") as? ObjectAnimator
          val activeBlend = blend
          expect("automatic_dissolve_observed", activeBlend != null &&
              activeBlend.animatedFraction < .95f && layers().all { it.childCount > 0 })
          check(activeBlend != null && activeBlend.animatedFraction < .95f)
          boolean("setAmbientNight", true)
          blendTime = activeBlend.currentPlayTime
          blendFraction = activeBlend.animatedFraction
          blendPose = pose()
          expect("dissolve_paused_at_cover", activeBlend.isPaused)
          measurements.put("dissolvePausedAtMs", blendTime)
              .put("dissolvePausedFraction", blendFraction)
        }
        settle(true)
        repeat(6) { sample ->
          main {
            freeze("dissolve_composition_frozen_$sample", blendPose)
            // API 29 ValueAnimator's currentPlayTime getter follows wall time while paused.
            // Its retained fraction and actual layer alpha establish the frozen blend.
            expect("dissolve_fraction_frozen_$sample", field(stage(), "fade") === blend &&
                blend!!.animatedFraction == blendFraction && blend!!.isPaused &&
                layers().all { it.childCount > 0 })
          }
          SystemClock.sleep(250)
        }
        main {
          boolean("setPaused", true); boolean("setPaused", false); boolean("setPaused", true)
          controller().setHostActive(false); controller().setHostActive(true)
          freeze("user_toggle_and_external_cover_keep_dissolve_frozen", blendPose)
          expect("user_toggle_and_external_cover_keep_blend_frozen",
              field(stage(), "fade") === blend && blend!!.isPaused &&
                  blend!!.animatedFraction == blendFraction)
          boolean("setAmbientNight", false)
        }
        poll(5000) { main {
          if (field(controller(), "hostActive") == false) {
            freeze("dissolve_reveal_composition_frozen", blendPose)
            expect("dissolve_reveal_fraction_frozen", blend!!.animatedFraction == blendFraction &&
                field(stage(), "fade") === blend && blend!!.isPaused)
          }
          field(controller(), "hostActive") == true
        } }
        main {
          freeze("user_pause_keeps_revealed_dissolve_frozen", blendPose, covered = false)
          expect("user_pause_keeps_revealed_blend_frozen", blend!!.isPaused &&
              blend!!.animatedFraction == blendFraction && field(stage(), "fade") === blend)
        }
        SystemClock.sleep(250)
        main {
          freeze("user_pause_dissolve_stays_frozen", blendPose, covered = false)
          expect("user_pause_blend_stays_frozen", blend!!.animatedFraction == blendFraction)
          boolean("setPaused", false)
          expect("dissolve_resumes_from_retained_fraction", !blend!!.isPaused &&
              blend!!.animatedFraction == blendFraction)
        }
        poll(1000) { main { blend!!.animatedFraction > blendFraction } }
        expect("dissolve_continues_after_explicit_resume", main {
          blend!!.animatedFraction > blendFraction })
        poll(2000) { main { field(stage(), "fade") == null } }
        main {
          expect("dissolve_retires_only_outgoing_layer",
              layers().count { it.childCount > 0 } == 1 &&
                  stage().currentPhoto!!.sourceKey == expectedNext)
        }
      }
      main { boolean("setPaused", true); retained = stage().currentPhoto!!.bitmap
        boolean("setAmbientNight", true) }
      settle(true)
      main { controller().setHostActive(false); controller().setHostActive(true)
        expect("external_cover_does_not_resume_night", field(controller(), "hostActive") == false)
        boolean("setAmbientNight", false) }
      settle(false)
      main { expect("user_pause_survives_night", field(controller(), "paused") == true &&
          stage().currentPhoto!!.bitmap === retained)
        if (motion) expect("user_paused_motion_survives_reveal",
            moves().isNotEmpty() && moves().all { it.isPaused }) }
      SystemClock.sleep(5500)
      main {
        expect("user_paused_beyond_interval", stage().currentPhoto!!.bitmap === retained)
        boolean("setAmbientNight", true)
      }
      settle(true)
      main { boolean("setPaused", false)
        expect("resume_under_night_keeps_cover", field(controller(), "hostActive") == false)
        if (motion) {
          retainedPose = pose()
          freeze("resume_under_night_keeps_motion_frozen", retainedPose)
        } }
      SystemClock.sleep(5500)
      main { expect("resume_under_night_keeps_photo", stage().currentPhoto!!.bitmap === retained)
        if (motion) freeze("resume_under_night_motion_after_interval", retainedPose)
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
          .put("failureLine", error.stackTrace.firstOrNull()?.lineNumber)
    } finally {
      deadline = began + if (motion) 95_000 else 80_000
      try {
        closing.set(true)
        main {
          Class.forName(name).getMethod("cancelOwnedProbe", String::class.java).invoke(null, token)
          owned.forEach { it.finish() }
        }
        poll(5000) { main { owned.all { it.isDestroyed } } }
        expect("owned_activity_destroyed", main { owned.isNotEmpty() && owned.all { it.isDestroyed } })
        if (motion) {
          // Closing a face session queues a final cache write. Wait for that writer before
          // retiring the generated tree; deletion alone could otherwise race its mkdir.
          val worker = Class.forName("com.immortal.launcher.SlideshowFaceStore\$Worker")
              .getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
          val lock = field(worker, "lock")!!
          poll(5000) {
            synchronized(lock) {
              field(worker, "pumping") == false && field(worker, "copying") == false &&
                  field(worker, "flight") == null && field(worker, "flushRequested") == false
            }
          }
          expect("owned_face_writer_quiescent", true)
          File(probe.targetContext.cacheDir, token).deleteRecursively()
        }
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
        .put("hardwareBrightnessTested", false).put("productionChanged", false)
        .put("photoMotion", motion).toString(2))
    return Bundle().apply {
      putBoolean("nightPhotoPausePassed", !failed && SystemClock.elapsedRealtime() <= deadline)
      putString("nightPhotoPauseReportRelativePath", "cache/${file.name}")
    }
  }
}
