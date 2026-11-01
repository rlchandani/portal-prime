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
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Own generated debug windows only; native draw/scroll proof, without provider or audio claims. */
internal object MusicRepeatedItemsDeviceChecks {
  private const val RUN_MS = 55_000L
  private const val HOST = "com.immortal.launcher.MusicDesignPreviewActivity"
  private data class Label(val text: String, val x: Float, val y: Float)

  fun run(probe: Instrumentation): Bundle {
    val checks = JSONArray()
    val cases = JSONArray()
    val artifacts = JSONArray()
    var failures = 0
    var isolated = 0
    var renders = 0
    var completedCases = 0
    val token = UUID.randomUUID().toString()
    val prefix = "music-repeated-items-owned-$token"
    val reportFile = File(probe.targetContext.cacheDir, "$prefix.json")
    val began = SystemClock.elapsedRealtime()
    val deadline = began + RUN_MS
    val owned = mutableListOf<Activity>()
    val application = probe.targetContext.applicationContext as Application
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name == HOST &&
            activity.intent.getStringExtra("owned_probe") == token) owned.add(activity)
      }
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    }

    fun expect(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failures++
    }
    fun remaining(limit: Long = 4_000): Long =
        minOf(limit, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun <T> main(block: () -> T): T {
      var value: T? = null
      var error: Throwable? = null
      probe.runOnMainSync {
        try { value = block() } catch (failure: Throwable) { error = failure }
      }
      error?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return value as T
    }
    fun frame(activity: Activity) {
      val drawn = CountDownLatch(1)
      var drawCount = 0
      var preDrawCount = 0
      val decor = main { activity.window.decorView }
      val before = ViewTreeObserver.OnPreDrawListener { preDrawCount++; true }
      val onDraw = ViewTreeObserver.OnDrawListener {
        drawCount++
        if (drawCount >= 2 && preDrawCount > 0) drawn.countDown()
        else decor.postInvalidateOnAnimation()
      }
      main {
        check(!activity.isDestroyed && activity.intent.getStringExtra("owned_probe") == token)
        decor.viewTreeObserver.addOnPreDrawListener(before)
        decor.viewTreeObserver.addOnDrawListener(onDraw)
        decor.postInvalidateOnAnimation()
      }
      try { check(drawn.await(remaining(), TimeUnit.MILLISECONDS)) }
      finally {
        main {
          if (decor.viewTreeObserver.isAlive) {
            decor.viewTreeObserver.removeOnPreDrawListener(before)
            decor.viewTreeObserver.removeOnDrawListener(onDraw)
          }
        }
      }
    }
    fun labels(activity: Activity): List<Label> = main {
      val decor = activity.window.decorView
      semantics(decor).flatMap { node ->
        val config = node.config
        if (SemanticsProperties.Text !in config) emptyList()
        else {
          val bounds = node.boundsInWindow
          if (bounds.width <= 0 || bounds.height <= 0 ||
              bounds.right <= 0 || bounds.bottom <= 0 ||
              bounds.left >= decor.width || bounds.top >= decor.height) emptyList()
          else config[SemanticsProperties.Text].map {
            Label(it.text, bounds.center.x, bounds.center.y)
          }
        }
      }
    }
    fun waitLabel(activity: Activity, text: String): Label {
      repeat(4) {
        labels(activity).firstOrNull { it.text == text }?.let { return it }
        check(remaining() > 0)
        frame(activity)
      }
      error("generated_label_unavailable")
    }
    fun touch(activity: Activity, x: Float, from: Float, to: Float) {
      val down = SystemClock.uptimeMillis()
      for (step in 0..7) {
        check(remaining() > 0)
        val action = when (step) {
          0 -> MotionEvent.ACTION_DOWN
          7 -> MotionEvent.ACTION_UP
          else -> MotionEvent.ACTION_MOVE
        }
        main {
          val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
              x, from + (to - from) * step / 7f, 0)
          event.source = InputDevice.SOURCE_TOUCHSCREEN
          try { activity.window.decorView.dispatchTouchEvent(event) }
          finally { event.recycle() }
        }
        if (step < 7) SystemClock.sleep(24)
      }
    }
    fun capture(activity: Activity, list: String, phase: String): String {
      frame(activity)
      val dimensions = main {
        activity.window.decorView.let { it.width to it.height }
      }
      val (width, height) = dimensions
      check(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608L)
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      try {
        val copied = CountDownLatch(1)
        var status = -1
        main {
          PixelCopy.request(activity.window, bitmap, {
            status = it
            copied.countDown()
          }, Handler(Looper.getMainLooper()))
        }
        check(copied.await(remaining(), TimeUnit.MILLISECONDS) && status == PixelCopy.SUCCESS)
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(width)
        val bytes = ByteBuffer.allocate(width * 4)
        var differingPixels = 0
        val background = bitmap.getPixel(0, 0)
        for (y in 0 until height) {
          bitmap.getPixels(row, 0, width, 0, y, width, 1)
          bytes.clear()
          row.forEach {
            if (it != background) differingPixels++
            bytes.putInt(it)
          }
          digest.update(bytes.array())
        }
        expect("$list.$phase.native_pixels_present", differingPixels > 100)
        val file = File(probe.targetContext.cacheDir, "$prefix-$list-$phase.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(file.length() > 0)
        artifacts.put(JSONObject().put("list", list).put("phase", phase)
            .put("relativePath", "cache/${file.name}").put("path", file.absolutePath)
            .put("width", width).put("height", height).put("nativePixels", differingPixels))
        renders++
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
      } finally { bitmap.recycle() }
    }

    expect("worker_off_main", Looper.myLooper() != Looper.getMainLooper())
    try {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main { application.registerActivityLifecycleCallbacks(lifecycle) }
      for (list in listOf("tracks", "collections", "queue", "rooms")) {
        val row = JSONObject().put("list", list)
        cases.put(row)
        var activity: Activity? = null
        val monitor = probe.addMonitor(HOST, null, false)
        try {
          check(remaining() > 0)
          main {
            probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, HOST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("screen", "repeated-items").putExtra("list", list)
                .putExtra("owned_probe", token))
          }
          val host = monitor.waitForActivityWithTimeout(remaining(5_000).coerceAtLeast(1))
          check(host != null)
          activity = host
          check(main { host.intent.getStringExtra("owned_probe") == token })
          check(main { owned.any { it === host } })
          frame(host)
          waitLabel(host, "Repeat one")
          waitLabel(host, "Repeat two")
          val first = labels(host)
          expect("$list.first_two_repeat_labels_visible",
              first.any { it.text == "Repeat one" } && first.any { it.text == "Repeat two" })
          val topHash = capture(host, list, "top")
          val anchor = first.first { it.text == "Repeat one" }
          val decorHeight = main { host.window.decorView.height.toFloat() }
          // Start inside the actual first row; move upwards past touch slop using local events.
          touch(host, anchor.x, minOf(decorHeight - 8f, anchor.y + 80f),
              maxOf(8f, anchor.y - 180f))
          frame(host)
          val afterGesture = labels(host)
          val moved = afterGesture.none { it.text == "Repeat one" } ||
              afterGesture.first { it.text == "Repeat one" }.y != anchor.y
          expect("$list.local_touch_scroll_observed", moved)
          row.put("localTouchScrollObserved", moved)
          // The shipped lazy list's action reaches the end without unbounded swipe loops.
          val lastIndex = when (list) {
            "tracks" -> 67 // Hero + heading + 66 tracks.
            "queue" -> 65
            else -> 66 // Heading/instructions + 66 entries.
          }
          val accepted = main {
            semantics(host.window.decorView).firstOrNull {
              SemanticsActions.ScrollToIndex in it.config
            }?.config?.get(SemanticsActions.ScrollToIndex)?.action?.invoke(lastIndex) == true
          }
          expect("$list.real_lazy_scroll_to_end_accepted", accepted)
          check(accepted)
          frame(host)
          waitLabel(host, "Generated row 66")
          expect("$list.final_generated_row_visible",
              labels(host).any { it.text == "Generated row 66" })
          val bottomHash = capture(host, list, "bottom")
          expect("$list.top_bottom_native_pixels_differ", topHash != bottomHash)
          row.put("bottomViaRealLazySemantics", true).put("renderCount", 2)
          completedCases++
        } catch (_: Throwable) {
          isolated++
          expect("$list.isolated_failure", false)
        } finally {
          probe.removeMonitor(monitor)
          activity?.let { host ->
            try { main { if (!host.isDestroyed) host.finish() } }
            catch (_: Throwable) { expect("$list.owned_activity_finish", false) }
          }
        }
      }
    }
    } finally {
      try {
        // Only UUID-owned creations are tracked; this also catches a late startup after timeout.
        main { owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() } }
        val cleanupUntil = minOf(deadline, SystemClock.elapsedRealtime() + 3_000)
        while (SystemClock.elapsedRealtime() < cleanupUntil && !main { owned.all { it.isDestroyed } })
          SystemClock.sleep(40)
        expect("owned_activities_destroyed", main { owned.all { it.isDestroyed } })
      } finally {
        main { application.unregisterActivityLifecycleCallbacks(lifecycle) }
      }
    }
    val passed = failures == 0 && completedCases == 4 && renders == 8
    val report = JSONObject().put("checks", checks).put("cases", cases).put("artifacts", artifacts)
        .put("caseCount", completedCases).put("renderCount", renders).put("checkCount", checks.length())
        .put("failureCount", failures).put("isolatedFailureCount", isolated).put("passed", passed)
        .put("generatedOnly", true).put("providerProof", false).put("groupControlProof", false)
        .put("audioProof", false).put("fpsProof", false)
        .put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("relativeReportPath", "cache/${reportFile.name}")
    var written = false
    try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
    catch (_: Throwable) { /* No exception diagnostics leave this generated-only branch. */ }
    return Bundle().apply {
      putBoolean("musicRepeatedItemsCompleted", true)
      putBoolean("musicRepeatedItemsPassed", passed && written)
      putBoolean("musicRepeatedItemsNativePassed", passed && written)
      putInt("musicRepeatedItemsCaseCount", completedCases)
      putInt("musicRepeatedItemsRenderCount", renders)
      putInt("musicRepeatedItemsCheckCount", checks.length())
      putInt("musicRepeatedItemsFailureCount", failures)
      putInt("musicRepeatedItemsIsolatedFailureCount", isolated)
      putString("musicRepeatedItemsChecksJson", checks.toString())
      putString("musicRepeatedItemsReportRelativePath", "cache/${reportFile.name}")
      putString("musicRepeatedItemsReportPath", reportFile.absolutePath)
      putString("musicRepeatedItemsArtifactPaths", artifacts.toString())
    }
  }

  /** Traverse only this owned decor's Compose semantics; never connect to accessibility services. */
  private fun semantics(view: View): List<SemanticsNode> {
    val owner = (view as? RootForTest)?.semanticsOwner
    if (owner != null) {
      val nodes = mutableListOf<SemanticsNode>()
      fun visit(node: SemanticsNode) {
        nodes.add(node)
        node.children.forEach(::visit)
      }
      visit(owner.unmergedRootSemanticsNode)
      return nodes
    }
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { semantics(view.getChildAt(it)) }
  }
}
