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
import android.view.MotionEvent
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
import kotlin.math.abs

/** Bounded generated native window check; no UiAutomation, sources or framing-store writes. */
internal object SlideshowDragDeviceChecks {
  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + 45_000
    val until = began + 55_000
    val prefix = "slideshow-drag-owned-${UUID.randomUUID()}"
    val checks = JSONArray()
    val samples = JSONArray()
    val artifacts = JSONArray()
    var failures = 0
    var isolated = false
    var host: Activity? = null
    val handler = Handler(Looper.getMainLooper())
    val owned = mutableListOf<Activity>()
    val closing = AtomicBoolean(false)
    val app = probe.targetContext.applicationContext as Application
    val name = "com.immortal.launcher.SlideshowDragPreviewActivity"
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
    fun demand(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
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
        // Teardown stays queued so a recovering UI thread still retires owned work.
        if (!cleanup) cancelled.set(true)
        error("owned_main_timeout")
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }
    fun tree(view: View): List<View> =
        listOf(view) + if (view is ViewGroup)
          (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    fun settle() {
      val done = CountDownLatch(1)
      main { host!!.window.decorView.postOnAnimation {
        host!!.window.decorView.postOnAnimation { done.countDown() }
      } }
      check(done.await(3, TimeUnit.SECONDS))
    }
    fun capture(label: String) {
      settle()
      val activity = host!!
      val bitmap = Bitmap.createBitmap(2160, 1440, Bitmap.Config.ARGB_8888)
      val done = CountDownLatch(1)
      val released = AtomicBoolean(false)
      val recycled = AtomicBoolean(false)
      // Pending -> submitted -> completed; retirement can win before submission.
      val submission = AtomicInteger(0)
      fun recycle() { if (recycled.compareAndSet(false, true)) bitmap.recycle() }
      var code = -1
      try {
        main {
          check(submission.compareAndSet(0, 1))
          try {
            PixelCopy.request(activity.window, bitmap, {
              code = it
              submission.set(2)
              done.countDown()
              if (released.get()) recycle()
            }, handler)
          } catch (error: Throwable) {
            submission.set(2)
            if (released.get()) recycle()
            throw error
          }
        }
        check(done.await(3, TimeUnit.SECONDS) && code == PixelCopy.SUCCESS)
        val file = File(probe.targetContext.cacheDir, "$prefix-$label.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        artifacts.put(JSONObject().put("relativePath", "cache/${file.name}").put("label", label))
      } finally {
        released.set(true)
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
      demand("physical_window_2160_1440", main {
        host!!.window.decorView.width == 2160 && host!!.window.decorView.height == 1440 })
      data class Case(val name: String, val width: Int, val height: Int, val framed: Boolean, val zoom: Float)
      val cases = listOf(
          Case("full-2x", 2160, 1440, false, 2f),
          Case("print-2x", 2160, 1440, true, 2f),
          Case("print-3x", 2160, 1440, true, 3f),
          Case("small-print-2x", 1080, 900, true, 2f),
          Case("whole-print", 2160, 1440, true, 1f))
      for (case in cases) {
        main { host!!.javaClass.getMethod("configureDrag", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(host, case.width, case.height, case.framed, case.zoom) }
        settle()
        main {
          check(tree(host!!.window.decorView).filterIsInstance<TextView>()
              .first { it.text.toString() == "Adjust" }.performClick())
        }
        settle()
        val stage = main { tree(host!!.window.decorView).filterIsInstance<SlideshowStage>().single() }
        val image = main {
          tree(stage).filterIsInstance<ImageView>().single { it.isShown && it.parent is ViewGroup } }
        val holder = main { image.parent as ViewGroup }
        val bitmap = main { stage.currentPhoto!!.bitmap }
        val controls = main { tree(host!!.window.decorView).filterIsInstance<SlideshowViewerControls>().single() }
        demand("${case.name}.editor_open", main { controls.isEditing })
        demand("${case.name}.drag_starts_inside_stage_outside_toolbar", main {
          800 < stage.width && 400 < stage.height &&
              !(800 >= controls.left && 800 < controls.right &&
                  400 >= controls.top && 400 < controls.bottom) })
        if (case.name == "print-2x") capture("before")
        for ((direction, delta) in listOf("forward" to (80f to 60f), "reverse" to (-80f to -60f),
            "edge" to (1200f to 1000f), "reverse-after-edge" to (-80f to -60f))) {
          val before = main { image.translationX to image.translationY }
          val expected = main {
            fun axis(position: Float, move: Float, length: Float, view: Int) =
                if (length <= view) position else (position + move).coerceIn(view - length, 0f)
            axis(before.first, delta.first, bitmap.width * image.scaleX, holder.layoutParams.width) to
                axis(before.second, delta.second, bitmap.height * image.scaleY, holder.layoutParams.height)
          }
          main {
            val down = SystemClock.uptimeMillis()
            for ((action, point) in listOf(MotionEvent.ACTION_DOWN to (800f to 400f),
                MotionEvent.ACTION_MOVE to (800f + delta.first to 400f + delta.second),
                MotionEvent.ACTION_UP to (800f + delta.first to 400f + delta.second))) {
              val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                  point.first, point.second, 0)
              try { host!!.dispatchTouchEvent(event) } finally { event.recycle() }
            }
          }
          settle()
          val after = main { image.translationX to image.translationY }
          demand("${case.name}.$direction.x_tracks_finger", abs(after.first - expected.first) < .05f)
          demand("${case.name}.$direction.y_tracks_finger", abs(after.second - expected.second) < .05f)
          demand("${case.name}.$direction.same_view_and_bitmap", main {
            tree(stage).filterIsInstance<ImageView>().single { it.isShown } === image &&
                stage.currentPhoto!!.bitmap === bitmap && !bitmap.isRecycled })
          samples.put(JSONObject().put("case", case.name).put("direction", direction)
              .put("requestedX", delta.first).put("requestedY", delta.second)
              .put("actualX", after.first - before.first).put("actualY", after.second - before.second)
              .put("expectedX", expected.first - before.first).put("expectedY", expected.second - before.second))
          if (case.name == "print-2x" && direction == "forward") capture("after")
        }
      }
    } catch (_: Throwable) {
      isolated = true
      demand("isolated_failure", false)
    } finally {
      closing.set(true)
      probe.removeMonitor(monitor)
      val teardown = runCatching {
        main(cleanup = true) {
          probe.targetContext.classLoader.loadClass(name).getMethod("cancelOwnedProbe", String::class.java)
              .invoke(null, prefix)
          owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
          app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
      }.isSuccess
      val cleanupUntil = minOf(until - 1_000, SystemClock.elapsedRealtime() + 3_000)
      while (SystemClock.elapsedRealtime() < cleanupUntil &&
          !runCatching { main(cleanup = true) { owned.all { it.isDestroyed } } }.getOrDefault(false))
        SystemClock.sleep(30)
      demand("owned_hosts_destroyed", teardown &&
          runCatching { main(cleanup = true) { owned.all { it.isDestroyed } } }.getOrDefault(false))
    }
    demand("bounded_native_work", SystemClock.elapsedRealtime() < until)
    val passed = failures == 0 && !isolated && samples.length() == 20 && artifacts.length() == 2
    val report = JSONObject().put("checks", checks).put("samples", samples).put("artifacts", artifacts)
        .put("failureCount", failures).put("isolatedFailureCount", if (isolated) 1 else 0)
        .put("caseCount", samples.length() / 4).put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("passed", passed).put("generatedOnly", true).put("physicalPanelFpsProof", false)
    val file = File(probe.targetContext.cacheDir, "$prefix.json")
    file.writeText(report.toString(2))
    return Bundle().apply {
      putBoolean("slideshowDragPassed", passed)
      putString("slideshowDragReportRelativePath", "cache/${file.name}")
    }
  }
}
