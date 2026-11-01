/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.AnimatorSet
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Generated native crop completion, without photo-source or persisted-position writes. */
internal object SlideshowCommitDeviceChecks {
  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + 45_000
    val until = began + 55_000
    val prefix = "slideshow-commit-owned-${UUID.randomUUID()}"
    val name = "com.immortal.launcher.SlideshowDragPreviewActivity"
    val handler = Handler(Looper.getMainLooper())
    val app = probe.targetContext.applicationContext as Application
    val closing = AtomicBoolean(false)
    val owned = mutableListOf<Activity>()
    val checks = JSONArray()
    val samples = JSONArray()
    val artifacts = JSONArray()
    var failures = 0
    var isolated = 0
    var host: Activity? = null
    fun expect(label: String, passed: Boolean) {
      checks.put(JSONObject().put("name", label).put("passed", passed))
      if (!passed) failures++
    }
    fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val remaining = minOf(3_000L, (if (cleanup) until else workUntil) -
          SystemClock.elapsedRealtime()).coerceAtLeast(0)
      check(remaining > 0)
      val done = CountDownLatch(1)
      val cancelled = AtomicBoolean(false)
      var result: T? = null
      var failure: Throwable? = null
      handler.post {
        try { if (!cancelled.get()) result = block() }
        catch (error: Throwable) { failure = error }
        finally { done.countDown() }
      }
      if (!done.await(remaining, TimeUnit.MILLISECONDS)) {
        if (!cleanup) cancelled.set(true)
        error("owned_main_timeout")
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST") return result as T
    }
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name == name && activity.intent.getStringExtra("owned_probe") == prefix) {
          owned.add(activity)
          if (closing.get() || SystemClock.elapsedRealtime() >= workUntil) activity.finish()
        }
      }
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    }
    fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
      (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    fun settle() {
      val done = CountDownLatch(1)
      main { host!!.window.decorView.postOnAnimation {
        host!!.window.decorView.postOnAnimation { done.countDown() }
      } }
      check(done.await(3, TimeUnit.SECONDS))
    }
    fun stage(): SlideshowStage = tree(host!!.window.decorView).filterIsInstance<SlideshowStage>().single()
    fun controls(): SlideshowViewerControls =
        tree(host!!.window.decorView).filterIsInstance<SlideshowViewerControls>().single()
    fun images(): List<ImageView> = tree(stage()).filterIsInstance<ImageView>().filter { it.isShown }
    fun pose(image: ImageView): List<Float> =
        listOf(image.scaleX, image.scaleY, image.translationX, image.translationY)
    fun moves(image: ImageView): List<AnimatorSet> {
      val layer = image.parent.parent
      @Suppress("UNCHECKED_CAST")
      return layer.javaClass.getDeclaredField("moves").apply { isAccessible = true }
          .get(layer) as List<AnimatorSet>
    }
    fun click(label: String) = main {
      check(tree(controls()).filterIsInstance<TextView>().single { it.text.toString() == label }.performClick())
    }
    fun capture(label: String) {
      settle()
      val bitmap = Bitmap.createBitmap(2160, 1440, Bitmap.Config.ARGB_8888)
      val done = CountDownLatch(1)
      val release = AtomicBoolean(false)
      val recycled = AtomicBoolean(false)
      val submission = AtomicInteger(0)
      var code = -1
      fun recycle() { if (recycled.compareAndSet(false, true)) bitmap.recycle() }
      try {
        main {
          check(submission.compareAndSet(0, 1))
          try {
            PixelCopy.request(host!!.window, bitmap, {
              code = it; submission.set(2); done.countDown()
              if (release.get()) recycle()
            }, handler)
          } catch (error: Throwable) {
            submission.set(2)
            if (release.get()) recycle()
            throw error
          }
        }
        check(done.await(3, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS)
        val file = File(probe.targetContext.cacheDir, "$prefix-$label.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        artifacts.put(JSONObject().put("label", label).put("relativePath", "cache/${file.name}"))
      } finally {
        release.set(true)
        if (submission.compareAndSet(0, 3) || submission.get() == 2) recycle()
      }
    }
    val monitor = probe.addMonitor(name, null, false)
    try {
      main { app.registerActivityLifecycleCallbacks(lifecycle) }
      main { probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, name)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("owned_probe", prefix)) }
      host = monitor.waitForActivityWithTimeout(4_000)
      check(host != null)
      settle()
      expect("native_window", main {
        host!!.window.decorView.width == 2160 && host!!.window.decorView.height == 1440 })
      data class Case(val name: String, val pair: Boolean, val print: Boolean, val save: Boolean,
          val moving: Boolean = false)
      val cases = listOf(Case("pair-save", true, false, true),
          Case("pair-cancel", true, false, false), Case("moving-pair-save", true, false, true, true),
          Case("single-save", false, false, true), Case("print-save", false, true, true))
      for (case in cases) {
        main { host!!.javaClass.getMethod("configureCommit", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(host, case.pair, case.print, case.moving) }
        settle()
        click("Pause")
        if (case.moving) main {
          val animations = moves(images().last())
          check(animations.size == 2)
          animations[0].currentPlayTime = 800
          animations[1].currentPlayTime = 3200
        }
        settle()
        val priorImages = main { images() }
        val priorBitmaps = main { priorImages.map { (it.drawable as BitmapDrawable).bitmap } }
        val companionPose = main { priorImages.getOrNull(1)?.let(::pose) }
        val companionTime = if (case.moving) main { moves(priorImages.last()).last().currentPlayTime } else null
        val original = main { stage().currentPhoto!!.adjustment }
        expect("${case.name}.initial_composition", priorImages.size == if (case.pair) 2 else 1)
        if (case.name == "pair-save") capture("before")
        click("Adjust")
        settle()
        expect("${case.name}.editor_open", main { controls().isEditing })
        expect("${case.name}.editor_uses_same_bitmap", main { stage().currentPhoto!!.bitmap === priorBitmaps.first() })
        click(if (case.save) "Save position" else "Cancel")
        settle()
        val afterImages = main { images() }
        val afterBitmaps = main { afterImages.map { (it.drawable as BitmapDrawable).bitmap } }
        val companionPoseAfter = main { afterImages.getOrNull(1)?.let(::pose) }
        expect("${case.name}.editor_closed", main { !controls().isEditing })
        expect("${case.name}.composition_restored", main {
          stage().photoCount == priorBitmaps.size && afterBitmaps.size == priorBitmaps.size })
        expect("${case.name}.bitmaps_retained", afterBitmaps.size == priorBitmaps.size &&
            afterBitmaps.zip(priorBitmaps).all { (after, before) -> after === before })
        expect("${case.name}.saved_position_only_on_first", main {
          stage().currentPhoto!!.adjustment == if (case.save) SlideshowAdjustment() else original })
        expect("${case.name}.normal_presentation_restored", main {
          stage().draggedAdjustment(15f, 10f) == null })
        expect("${case.name}.saved_crop_rendered", !case.save || main {
          val image = afterImages.first()
          val holder = image.parent as ViewGroup
          val bitmap = priorBitmaps.first()
          val scale = minOf(holder.width.toFloat() / bitmap.width, holder.height.toFloat() / bitmap.height)
          kotlin.math.abs(image.scaleX - scale) < .001f &&
              kotlin.math.abs(image.scaleY - scale) < .001f &&
              kotlin.math.abs(image.translationX - (holder.width - bitmap.width * scale) / 2) < .01f &&
              kotlin.math.abs(image.translationY - (holder.height - bitmap.height * scale) / 2) < .01f
        })
        expect("${case.name}.companion_pose_retained", !case.pair ||
            (afterImages.size == 2 && companionPoseAfter == companionPose))
        expect("${case.name}.pause_retained", main {
          tree(controls()).filterIsInstance<TextView>().any { it.text.toString() == "Resume" } })
        val retainedTime = if (case.moving && afterImages.size == 2)
          main { moves(afterImages.last()).lastOrNull()?.currentPlayTime } else null
        expect("${case.name}.companion_animation_time", !case.moving || retainedTime == companionTime)
        var resumedTime: Long? = null
        if (case.moving) {
          click("Resume"); settle()
          resumedTime = if (afterImages.size == 2)
            main { moves(afterImages.last()).lastOrNull()?.currentPlayTime } else null
          expect("${case.name}.companion_resumes_from_held_time",
              resumedTime != null && companionTime != null &&
                  resumedTime > companionTime && resumedTime < companionTime + 1000)
          click("Pause"); settle()
        } else expect("${case.name}.companion_resumes_from_held_time", true)
        if (case.name == "pair-save") capture("after")
        // A second editor round must start in the same normal composition.
        click("Adjust"); settle(); click("Cancel"); settle()
        expect("${case.name}.subsequent_cancel_restores_composition", main {
          stage().photoCount == priorBitmaps.size && images().size == priorBitmaps.size })
        samples.put(JSONObject().put("case", case.name).put("beforePhotos", priorBitmaps.size)
            .put("afterPhotos", afterBitmaps.size).put("companionPoseBefore", companionPose?.let(::JSONArray))
            .put("companionTimeBeforeMs", companionTime).put("companionTimeAfterMs", retainedTime)
            .put("companionTimeResumedMs", resumedTime)
            .put("companionPoseAfter", companionPoseAfter?.let(::JSONArray)))
      }
    } catch (_: Throwable) {
      isolated++
      expect("isolated_failure", false)
    } finally {
      probe.removeMonitor(monitor)
      closing.set(true)
      val retired = runCatching {
        main(cleanup = true) {
          probe.targetContext.classLoader.loadClass(name).getMethod("cancelOwnedProbe", String::class.java)
              .invoke(null, prefix)
          owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
          app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
      }.isSuccess
      val cleanupUntil = minOf(until - 1000, SystemClock.elapsedRealtime() + 3000)
      while (SystemClock.elapsedRealtime() < cleanupUntil &&
          !runCatching { main(cleanup = true) { owned.all { it.isDestroyed } } }.getOrDefault(false))
        SystemClock.sleep(30)
      expect("owned_hosts_destroyed", retired &&
          runCatching { main(cleanup = true) { owned.all { it.isDestroyed } } }.getOrDefault(false))
    }
    expect("bounded_native_work", SystemClock.elapsedRealtime() < until)
    val passed = failures == 0 && isolated == 0 && samples.length() == 5 && artifacts.length() == 2
    val report = JSONObject().put("checks", checks).put("samples", samples).put("artifacts", artifacts)
        .put("passed", passed).put("failureCount", failures).put("isolatedFailureCount", isolated)
        .put("caseCount", samples.length()).put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("generatedOnly", true).put("providerProof", false).put("panelFpsProof", false)
    val file = File(probe.targetContext.cacheDir, "$prefix.json")
    file.writeText(report.toString(2))
    return Bundle().apply {
      putBoolean("slideshowCommitPassed", passed && SystemClock.elapsedRealtime() < until)
      putString("slideshowCommitReportRelativePath", "cache/${file.name}")
    }
  }
}
