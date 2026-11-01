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
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Silent native held gestures; target changes use the production rendered-command guard. */
internal object MusicSeekBindingDeviceChecks {
  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + 45_000
    val until = began + 55_000
    val prefix = "music-seek-binding-owned-${UUID.randomUUID()}"
    val name = "com.immortal.launcher.MusicDesignPreviewActivity"
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
    fun frame() {
      val done = CountDownLatch(1)
      main { host!!.window.decorView.postOnAnimation {
        host!!.window.decorView.postOnAnimation { done.countDown() }
      } }
      check(done.await(3, TimeUnit.SECONDS))
    }
    fun slider(): SemanticsNode = semantics(host!!.window.decorView).single {
      SemanticsProperties.ProgressBarRangeInfo in it.config &&
          SemanticsProperties.ContentDescription in it.config &&
          it.config[SemanticsProperties.ContentDescription].contains("Track position")
    }
    fun fraction(): Float = main { slider().config[SemanticsProperties.ProgressBarRangeInfo].current }
    fun bind(player: String, queue: String, revision: Long, title: String,
        duration: Long = 240_000) = main {
      host!!.javaClass.getMethod("updateMusicSeekBinding", String::class.java,
          String::class.java, Long::class.javaPrimitiveType, String::class.java,
          Long::class.javaPrimitiveType)
          .invoke(host, player, queue, revision, title, duration)
    }
    fun receipts(): JSONArray = main {
      JSONArray(host!!.javaClass.getMethod("musicSeekBindingReceipts").invoke(host) as String)
    }
    fun accepted() = receipts().let { array ->
      (0 until array.length()).count { array.getJSONObject(it).getBoolean("accepted") }
    }
    fun gesture(action: Int, x: Float, y: Float, down: Long): Boolean = main {
      val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
      try { host!!.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    fun capture(palette: String, phase: String) {
      frame()
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
        val file = File(probe.targetContext.cacheDir, "$prefix-$palette-$phase.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val bounds = main { slider().boundsInWindow }
        artifacts.put(JSONObject().put("palette", palette).put("phase", phase)
            .put("sliderBounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("relativePath", "cache/${file.name}"))
      } finally {
        release.set(true)
        if (submission.compareAndSet(0, 3) || submission.get() == 2) recycle()
      }
    }
    main { app.registerActivityLifecycleCallbacks(lifecycle) }
    try {
      for (palette in listOf("dark", "light")) {
        val monitor = probe.addMonitor(name, null, false)
        try {
          main { probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, name)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("screen", "seekbinding")
              .putExtra("theme", palette).putExtra("owned_probe", prefix)
              .putExtra("owned_deadline_elapsed_ms", workUntil)) }
          host = monitor.waitForActivityWithTimeout(4_000)
          check(host != null)
          frame()
          expect("$palette.native_window", main {
            host!!.window.decorView.width == 2160 && host!!.window.decorView.height == 1440 })
          for (case in listOf("unchanged", "metadata", "player", "queue", "duration")) {
            bind("ma:generated-player-a", "generated-queue-a", 1, "Generated paused track")
            frame()
            val prior = accepted()
            val bounds = main { slider().boundsInWindow }
            val down = SystemClock.uptimeMillis()
            val x0 = bounds.left + bounds.width * .125f
            val x1 = bounds.left + bounds.width * .65f
            val y = bounds.center.y
            expect("$palette.$case.down_routed", gesture(MotionEvent.ACTION_DOWN, x0, y, down))
            for (step in 1..8) gesture(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * step / 8, y, down)
            frame()
            val held = fraction()
            expect("$palette.$case.held_draft_visible", held > .5f && held < .8f)
            expect("$palette.$case.no_action_while_held", accepted() == prior)
            val changed = case in listOf("player", "queue", "duration")
            val player = if (case == "player") "ma:generated-player-b" else "ma:generated-player-a"
            val queue = if (case == "queue") "generated-queue-b" else "generated-queue-a"
            if (case == "player") capture(palette, "held")
            val duration = if (case == "duration") 300_000L else 240_000L
            bind(player, queue, if (case == "player" || case == "queue") 2 else 1,
                if (case == "metadata") "Updated generated title" else "Generated paused track", duration)
            frame()
            val rebound = fraction()
            expect("$palette.$case.draft_binding",
                if (changed) kotlin.math.abs(rebound - 30_000f / duration) < .001f
                else kotlin.math.abs(rebound - held) < .001f)
            val continuedX = bounds.left + bounds.width * .75f
            gesture(MotionEvent.ACTION_MOVE, continuedX, y, down)
            frame()
            val continued = fraction()
            expect("$palette.$case.old_stream_binding",
                if (changed) kotlin.math.abs(continued - 30_000f / duration) < .001f
                else continued > held + .05f)
            if (case == "player") capture(palette, "rebound")
            gesture(MotionEvent.ACTION_UP, continuedX, y, down)
            frame()
            val released = accepted() - prior
            expect("$palette.$case.release_owned",
                released == if (changed) 0 else 1)
            val beforeFresh = accepted()
            val freshBounds = main { slider().boundsInWindow }
            val freshDown = SystemClock.uptimeMillis()
            val freshX = freshBounds.left + freshBounds.width * .4f
            gesture(MotionEvent.ACTION_DOWN, freshBounds.left + freshBounds.width * .125f,
                freshBounds.center.y, freshDown)
            gesture(MotionEvent.ACTION_MOVE, freshX, freshBounds.center.y, freshDown)
            gesture(MotionEvent.ACTION_UP, freshX, freshBounds.center.y, freshDown)
            frame()
            expect("$palette.$case.fresh_gesture_accepted", accepted() == beforeFresh + 1)
            samples.put(JSONObject().put("palette", palette).put("case", case)
                .put("heldFraction", held).put("reboundFraction", rebound).put("continuedFraction", continued)
                .put("acceptedFromOldRelease", released).put("receipts", receipts()))
          }
        } catch (_: Throwable) {
          isolated++
          expect("$palette.isolated_failure", false)
        } finally {
          probe.removeMonitor(monitor)
          host?.let { activity -> runCatching { main(cleanup = true) { activity.finish() } } }
          host = null
        }
      }
    } finally {
      closing.set(true)
      val retired = runCatching {
        main(cleanup = true) {
          probe.targetContext.classLoader.loadClass(name).getMethod("cancelOwnedSeekProbe", String::class.java)
              .invoke(null, prefix)
          owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
          app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
      }.isSuccess
      val cleanupUntil = minOf(until - 1_000, SystemClock.elapsedRealtime() + 3_000)
      while (SystemClock.elapsedRealtime() < cleanupUntil &&
          !runCatching { main(cleanup = true) { owned.all { it.isDestroyed } } }.getOrDefault(false))
        SystemClock.sleep(30)
      expect("owned_hosts_destroyed", retired &&
          runCatching { main(cleanup = true) { owned.all { it.isDestroyed } } }.getOrDefault(false))
    }
    expect("bounded_native_work", SystemClock.elapsedRealtime() < until)
    val passed = failures == 0 && isolated == 0 && samples.length() == 10 && artifacts.length() == 4
    val report = JSONObject().put("checks", checks).put("samples", samples).put("artifacts", artifacts)
        .put("passed", passed).put("failureCount", failures).put("isolatedFailureCount", isolated)
        .put("caseCount", samples.length()).put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("generatedOnly", true).put("providerProof", false).put("panelFpsProof", false)
    val file = File(probe.targetContext.cacheDir, "$prefix.json")
    file.writeText(report.toString(2))
    return Bundle().apply {
      putBoolean("musicSeekBindingPassed", passed)
      putString("musicSeekBindingReportRelativePath", "cache/${file.name}")
    }
  }

  private fun semantics(view: View): List<SemanticsNode> {
    val owner = (view as? RootForTest)?.semanticsOwner
    if (owner != null) {
      val nodes = mutableListOf<SemanticsNode>()
      fun visit(node: SemanticsNode) {
        var layout = node.layoutInfo
        while (true) {
          if (!layout.isAttached || !layout.isPlaced || layout.isDeactivated) return
          layout = layout.parentInfo ?: break
        }
        nodes.add(node)
        node.children.forEach(::visit)
      }
      visit(owner.unmergedRootSemanticsNode)
      return nodes
    }
    return if (view is ViewGroup)
      (0 until view.childCount).flatMap { semantics(view.getChildAt(it)) } else emptyList()
  }
}
