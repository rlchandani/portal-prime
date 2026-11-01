/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Actual native resize/animation ownership with generated pixels; no source/config writes. */
internal object SlideshowResizeDeviceChecks {
  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + 35_000
    val until = began + 45_000
    val token = "slideshow-resize-owned-${UUID.randomUUID()}"
    val name = "com.immortal.launcher.SlideshowDragPreviewActivity"
    val app = probe.targetContext.applicationContext as Application
    val handler = Handler(Looper.getMainLooper())
    val closing = AtomicBoolean()
    val owned = mutableListOf<Activity>()
    val checks = JSONArray()
    val samples = JSONArray()
    var failed = false
    var isolated = false
    var host: Activity? = null
    fun expect(label: String, passed: Boolean) {
      checks.put(JSONObject().put("name", label).put("passed", passed))
      if (!passed) failed = true
    }
    fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val task = FutureTask(Callable(block))
      check(handler.post(task))
      try {
        return task.get(minOf(2000, (if (cleanup) until else workUntil) -
            SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
      } finally { handler.removeCallbacks(task); task.cancel(false) }
    }
    fun field(target: Any, key: String): Any? =
        target.javaClass.getDeclaredField(key).apply { isAccessible = true }.get(target)
    fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
      (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    fun stage(): SlideshowStage = tree(host!!.window.decorView).filterIsInstance<SlideshowStage>().single()
    fun layers(): List<FrameLayout> = (field(stage(), "layers") as Array<*>)
        .map { it as FrameLayout }.filter { it.childCount > 0 }
    fun images(layer: FrameLayout): List<ImageView> = (0 until layer.childCount).map {
      (layer.getChildAt(it) as FrameLayout).getChildAt(0) as ImageView }
    @Suppress("UNCHECKED_CAST")
    fun moves(layer: FrameLayout): List<AnimatorSet> = field(layer, "moves") as List<AnimatorSet>
    fun bitmaps(): List<Bitmap> = layers().flatMap { images(it) }
        .map { (it.drawable as BitmapDrawable).bitmap }
    fun times(): List<List<Long>> = layers().map { moves(it).map { it.currentPlayTime } }
    fun budget(): SlideshowPixelBudget = field(stage(), "budget") as SlideshowPixelBudget
    fun poll(timeout: Long, condition: () -> Boolean) {
      val end = minOf(workUntil, SystemClock.elapsedRealtime() + timeout)
      while (SystemClock.elapsedRealtime() < end) {
        if (condition()) return
        SystemClock.sleep(25)
      }
      error("bounded_resize_condition")
    }
    fun resize(width: Int, height: Int) {
      main { stage().layoutParams = FrameLayout.LayoutParams(width, height) }
      poll(2000) { main {
        stage().width == width && stage().height == height &&
            layers().all { layer -> layer.width == width && layer.height == height }
      } }
      val view = main { host!!.window.decorView }
      val drawn = CountDownLatch(1)
      val second = Runnable { drawn.countDown() }
      val first = Runnable { view.postOnAnimation(second) }
      try {
        main { view.postOnAnimation(first) }
        check(drawn.await(2000, TimeUnit.MILLISECONDS))
      } finally { main(cleanup = true) { view.removeCallbacks(first); view.removeCallbacks(second) } }
    }
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name == name && activity.intent.getStringExtra("owned_probe") == token) {
          owned.add(activity)
          if (closing.get() || SystemClock.elapsedRealtime() >= workUntil) activity.finish()
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
      main { app.registerActivityLifecycleCallbacks(lifecycle)
        probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("owned_probe", token)) }
      host = monitor.waitForActivityWithTimeout(5000)
      check(host != null)
      poll(3000) { main { stage().width == 2160 && stage().height == 1440 } }
      expect("native_window", main {
        host!!.window.decorView.width == 2160 && host!!.window.decorView.height == 1440 })
      for (case in listOf("covered-pair", "user-paused-single", "covered-paired-dissolve",
          "completed-single", "manual-first-pair", "edit-cancel-pair")) {
        val pair = case.endsWith("pair") || case == "covered-paired-dissolve"
        main {
          stage().setHostActive(true)
          host!!.javaClass.getMethod("configureCommit", Boolean::class.javaPrimitiveType,
              Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
              .invoke(host, pair, false, true)
        }
        poll(3000) { main { stage().photoCount == (if (pair) 2 else 1) &&
            layers().size == 1 && moves(layers().single()).size == (if (pair) 2 else 1) } }
        var retained: List<Bitmap> = emptyList()
        var expectedPhases: List<List<Long>> = emptyList()
        var expectedTimes: List<List<Long>> = emptyList()
        var bytes = 0L
        var peak = 0L
        var fade: ObjectAnimator? = null
        var fraction = 0f
        if (case == "completed-single") {
          main { moves(layers().single()).single().currentPlayTime =
              8000 + SlideshowStage.DISSOLVE_MS - 100 }
          poll(2000) { main { !moves(layers().single()).single().isStarted } }
          expect("$case.motion_completed_before_cover", main {
            val move = moves(layers().single()).single()
            !move.isStarted && (move.childAnimations.first() as ObjectAnimator).animatedFraction == 1f })
        }
        main {
          if (case == "user-paused-single") stage().pause() else stage().setHostActive(false)
          if (case != "completed-single") moves(layers().single()).forEachIndexed { index, move ->
            move.currentPlayTime = if (index == 0) 800 else 3200 }
          if (case == "manual-first-pair") {
            stage().adjust(SlideshowAdjustment())
            stage().finishAdjustment(save = true)
          }
          if (case == "covered-paired-dissolve") {
            val photos = (0..1).map { index ->
              val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
              bitmap.eraseColor(if (index == 0) Color.GREEN else Color.YELLOW)
              SlideshowPhoto("generated-resize-$index", bitmap,
                  regions = listOf(SlideshowGeometry.Region(.4f, .4f, .6f, .6f)))
            }
            stage().show(photos, true, true, 8000)
            fade = field(stage(), "fade") as ObjectAnimator
            fade!!.currentPlayTime = 300
            fraction = fade!!.animatedFraction
            val current = (field(stage(), "layers") as Array<*>)[field(stage(), "current") as Int]
                as FrameLayout
            moves(current).forEachIndexed { index, move ->
              move.currentPlayTime = if (index == 0) 1000 else 2400
            }
          }
          retained = bitmaps()
          expectedTimes = if (case == "completed-single")
            listOf(listOf(8000 + SlideshowStage.DISSOLVE_MS)) else times()
          expectedPhases = layers().map { layer ->
            @Suppress("UNCHECKED_CAST")
            val indexes = field(layer, "movePhotos") as List<Int>
            val byPhoto = indexes.zip(moves(layer)).associate { (index, move) ->
              index to if (case == "completed-single") 8000 + SlideshowStage.DISSOLVE_MS
                else move.currentPlayTime }
            images(layer).indices.map { byPhoto[it] ?: 0L }
          }
          bytes = budget().used(); peak = budget().peak
          expect("$case.setup_motion", layers().all { moves(it).isNotEmpty() &&
              moves(it).all { move -> if (case == "completed-single") !move.isStarted
                else move.isPaused && move.currentPlayTime > 0 } })
        }
        if (case == "edit-cancel-pair") main { stage().adjust(SlideshowAdjustment()) }
        for ((step, size) in listOf(1200 to 800, 64 to 1440, 1440 to 1000, 2160 to 1440).withIndex()) {
          val (width, height) = size
          resize(width, height)
          if (case == "edit-cancel-pair" && step == 0) {
            main { stage().finishAdjustment(save = false) }
            poll(2000) { main { layers().single().childCount == 2 &&
                images(layers().single()).all { (it.parent as FrameLayout).width > 0 } } }
          }
          main {
            val label = "$case.${width}x$height"
            expect("$label.bitmaps_retained", bitmaps().size == retained.size &&
                bitmaps().zip(retained).all { (a, b) -> a === b && !a.isRecycled })
            val plannedTimes = layers().mapIndexed { layerIndex, layer ->
              val margin = if (pair) (20 * layer.resources.displayMetrics.density).toInt() else 0
              val tileWidth = (width - margin) / if (pair) 2 else 1
              images(layer).indices.filter { index ->
                val left = index * (tileWidth + margin)
                val tile = if (pair && index == 1) width - left else tileWidth
                val path = SlideshowGeometry.plan(600, 800, tile, height,
                    listOf(SlideshowGeometry.Region(.4f, .4f, .6f, .6f)), true, true, index == 1)
                !(case == "manual-first-pair" && index == 0) && path.start != path.end
              }.map { expectedPhases[layerIndex][it] }
            }
            expect("$label.animation_times_retained", times() == plannedTimes)
            expect("$label.animations_stay_paused", layers().all {
                moves(it).all { move -> move.isPaused } })
            expect("$label.pixel_budget_unchanged", budget().used() == bytes && budget().peak == peak)
            expect("$label.resized_images_measured", layers().flatMap(::images).all {
              it.width == 600 && it.height == 800 &&
                  (it.parent as FrameLayout).width > 0 && (it.parent as FrameLayout).height > 0 })
            var correct = true
            layers().forEachIndexed { layerIndex, layer ->
              val margin = if (pair) (20 * layer.resources.displayMetrics.density).toInt() else 0
              val tileWidth = (width - margin) / if (pair) 2 else 1
              images(layer).forEachIndexed { index, image ->
                val holder = image.parent as FrameLayout
                val left = index * (tileWidth + margin)
                val tile = if (pair && index == 1) width - left else tileWidth
                val path = SlideshowGeometry.plan(600, 800, tile, height,
                    listOf(SlideshowGeometry.Region(.4f, .4f, .6f, .6f)), true, true, index == 1)
                val expected = if (case == "manual-first-pair" && index == 0)
                  SlideshowAdjustment().transform(600, 800, tile, height) else
                    path.at(expectedPhases[layerIndex][index] /
                        (8000f + SlideshowStage.DISSOLVE_MS))
                correct = correct && holder.width == tile && holder.height == height &&
                    kotlin.math.abs(image.scaleX - expected.scale) < .001f &&
                    kotlin.math.abs(image.translationX - expected.x) < .1f &&
                    kotlin.math.abs(image.translationY - expected.y) < .1f
              }
            }
            expect("$label.new_viewport_at_retained_progress", correct)
            if (fade != null) expect("$label.retained_dissolve",
                field(stage(), "fade") === fade && fade!!.isPaused &&
                    fade!!.animatedFraction == fraction && layers().size == 2)
            samples.put(JSONObject().put("case", case).put("width", width).put("height", height)
                .put("beforeTimesMs", JSONArray(expectedTimes.map(::JSONArray)))
                .put("afterTimesMs", JSONArray(times().map(::JSONArray)))
                .put("holders", JSONArray(layers().flatMap(::images).map {
                  val holder = it.parent as FrameLayout
                  listOf(holder.width, holder.height, it.scaleX, it.translationX, it.translationY) }))
                .put("retainedPixelBytes", budget().used()).put("peakPixelBytes", budget().peak))
          }
        }
        main {
          if (case == "user-paused-single") stage().resume() else stage().setHostActive(true)
          expect("$case.resume_enabled", layers().all { moves(it).all { move -> !move.isPaused } })
        }
        SystemClock.sleep(250)
        main {
          if (case == "completed-single") {
            expect("$case.completed_motion_does_not_restart", layers().all {
              moves(it).all { move -> !move.isStarted &&
                  (move.childAnimations.first() as ObjectAnimator).animatedFraction == 1f } })
          } else expect("$case.motion_continues_from_retained_times",
              times().zip(expectedTimes).all { (after, before) ->
                after.zip(before).all { (a, b) -> a - b in 100..700 } })
          if (fade != null) expect("$case.dissolve_continues", fade!!.animatedFraction > fraction)
        }
        if (fade != null) {
          poll(2000) { main { field(stage(), "fade") == null } }
          expect("$case.only_outgoing_retired", main { layers().size == 1 &&
              stage().currentPhoto!!.key == "generated-resize-0" })
        }
      }
    } catch (error: Throwable) {
      isolated = true
      expect("isolated_failure_${error.javaClass.simpleName}_${error.stackTrace.firstOrNull()?.lineNumber}", false)
    } finally {
      closing.set(true)
      try {
        main(cleanup = true) {
          Class.forName(name).getMethod("cancelOwnedProbe", String::class.java).invoke(null, token)
          owned.forEach { it.finish() }
        }
        val end = minOf(until, SystemClock.elapsedRealtime() + 3000)
        while (SystemClock.elapsedRealtime() < end &&
            !main(cleanup = true) { owned.all { it.isDestroyed } }) SystemClock.sleep(25)
        expect("owned_activity_destroyed", main(cleanup = true) {
          owned.isNotEmpty() && owned.all { it.isDestroyed } })
        main(cleanup = true) { app.unregisterActivityLifecycleCallbacks(lifecycle) }
        probe.removeMonitor(monitor)
      } catch (_: Throwable) { expect("cleanup_completed", false) }
    }
    expect("bounded_work", SystemClock.elapsedRealtime() < until)
    val passed = !failed && !isolated && samples.length() == 24
    val file = File(probe.targetContext.cacheDir, "$token.json")
    file.writeText(JSONObject().put("passed", passed).put("checks", checks).put("samples", samples)
        .put("caseCount", 6).put("isolatedFailure", isolated)
        .put("generatedOnly", true).put("panelFpsProof", false)
        .put("elapsedMs", SystemClock.elapsedRealtime() - began).toString(2))
    return Bundle().apply {
      putBoolean("slideshowResizePassed", passed)
      putString("slideshowResizeReportRelativePath", "cache/${file.name}")
    }
  }
}
