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
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Generated silent native lists; checks viewport ownership without UiAutomation or a provider. */
internal object MusicViewportDeviceChecks {
  private const val HOST = "com.immortal.launcher.MusicDesignPreviewActivity"

  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + 45_000
    val until = began + 55_000
    val token = UUID.randomUUID().toString()
    val prefix = "music-viewport-owned-$token"
    val checks = JSONArray()
    val artifacts = JSONArray()
    var failures = 0
    var isolated = 0
    var completed = 0
    val handler = Handler(Looper.getMainLooper())
    val owned = mutableListOf<Activity>()
    val closing = AtomicBoolean(false)
    val app = probe.targetContext.applicationContext as Application
    fun expect(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failures++
    }
    fun remaining(cleanup: Boolean = false) =
        minOf(3_000L, (if (cleanup) until else workUntil) -
            SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val done = CountDownLatch(1)
      val cancelled = AtomicBoolean(false)
      var result: T? = null
      var failure: Throwable? = null
      check(remaining(cleanup) > 0)
      handler.post {
        try { if (!cancelled.get()) result = block() }
        catch (error: Throwable) { failure = error }
        finally { done.countDown() }
      }
      if (!done.await(remaining(cleanup), TimeUnit.MILLISECONDS)) {
        cancelled.set(true)
        error("owned_main_timeout")
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name == HOST &&
            activity.intent.getStringExtra("owned_probe") == token) {
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
    fun frame(activity: Activity) {
      val done = CountDownLatch(1)
      var draws = 0
      val decor = main { activity.window.decorView }
      val listener = ViewTreeObserver.OnDrawListener {
        if (++draws >= 2) done.countDown() else decor.postInvalidateOnAnimation()
      }
      main {
        check(!activity.isDestroyed)
        decor.viewTreeObserver.addOnDrawListener(listener)
        decor.postInvalidateOnAnimation()
      }
      try { check(done.await(remaining(), TimeUnit.MILLISECONDS)) }
      finally {
        main(cleanup = true) {
          if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
        }
      }
    }
    fun rows(activity: Activity, list: String): List<Pair<String, Int>> = main {
      val decor = activity.window.decorView
      semantics(decor).flatMap { node ->
        val bounds = node.boundsInWindow
        if (SemanticsProperties.Text !in node.config ||
            bounds.right <= 0 || bounds.bottom <= 0 ||
            bounds.left >= decor.width || bounds.top >= decor.height) emptyList()
        else node.config[SemanticsProperties.Text].mapNotNull {
          if (it.text.startsWith("Generated $list row ")) it.text to bounds.top.toInt() else null
        }
      }.distinct().sortedBy { it.second }
    }
    fun settled(activity: Activity, list: String): List<Pair<String, Int>> {
      var prior = emptyList<Pair<String, Int>>()
      repeat(5) {
        frame(activity)
        val current = rows(activity, list)
        if (current.isNotEmpty() && current == prior) return current
        prior = current
      }
      check(prior.isNotEmpty())
      return prior
    }
    fun click(activity: Activity, label: String): Boolean = main {
      semantics(activity.window.decorView).firstOrNull {
        SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config &&
            containsText(it, label)
      }?.config?.get(SemanticsActions.OnClick)?.action?.invoke() == true
    }
    fun scroll(activity: Activity, index: Int): Boolean = main {
      semantics(activity.window.decorView).firstOrNull {
        SemanticsActions.ScrollToIndex in it.config
      }?.config?.get(SemanticsActions.ScrollToIndex)?.action?.invoke(index) == true
    }
    fun layout(activity: Activity, layout: String) = main {
      activity.javaClass.getMethod("updateMusicViewportLayout", String::class.java)
          .invoke(activity, layout)
    }
    fun binding(activity: Activity, source: String, queue: String, revision: Long) = main {
      activity.javaClass.getMethod("updateMusicViewportBinding", String::class.java,
          String::class.java, Long::class.javaPrimitiveType).invoke(activity, source, queue, revision)
    }
    fun capture(activity: Activity, palette: String, list: String) {
      frame(activity)
      val (width, height) = main { activity.window.decorView.let { it.width to it.height } }
      check(width == 2160 && height == 1440)
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val done = CountDownLatch(1)
      val released = AtomicBoolean(false)
      var status = -1
      try {
        main {
          PixelCopy.request(activity.window, bitmap, {
            status = it
            done.countDown()
            if (released.get() && !bitmap.isRecycled) bitmap.recycle()
          }, handler)
        }
        check(done.await(remaining(), TimeUnit.MILLISECONDS) && status == PixelCopy.SUCCESS)
        val file = File(probe.targetContext.cacheDir, "$prefix-$palette-$list.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        artifacts.put(JSONObject().put("relativePath", "cache/${file.name}")
            .put("palette", palette).put("list", list)
            .put("visibleRows", JSONArray(settled(activity, list).map {
              JSONObject().put("text", it.first).put("top", it.second)
            })))
      } finally {
        released.set(true)
        if (done.count == 0L && !bitmap.isRecycled) bitmap.recycle()
      }
    }
    var registered = false
    expect("worker_off_main", Looper.myLooper() != Looper.getMainLooper())
    try {
      main { app.registerActivityLifecycleCallbacks(lifecycle); registered = true }
      for (palette in listOf("dark", "light")) {
        val monitor = probe.addMonitor(HOST, null, false)
        var host: Activity? = null
        try {
          main {
            probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, HOST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("screen", "viewport")
                .putExtra("theme", palette).putExtra("owned_probe", token)
                .putExtra("owned_deadline_elapsed_ms", workUntil))
          }
          val activity = monitor.waitForActivityWithTimeout(remaining().coerceAtLeast(1))
          check(activity != null)
          host = activity
          check(main { owned.any { it === activity } })
          for ((list, tab, alternate) in listOf(
              Triple("queue", "Queue", "Players"), Triple("player", "Players", "Queue"))) {
            settled(activity, if (list == "queue") "queue" else "queue")
            expect("$palette.$list.tab_open", click(activity, tab))
            settled(activity, list)
            expect("$palette.$list.scroll_action", scroll(activity, 30))
            val before = settled(activity, list)
            expect("$palette.$list.off_top", before.none { it.first.endsWith(" row 0") })
            expect("$palette.$list.alternate_tab", click(activity, alternate))
            settled(activity, if (list == "queue") "player" else "queue")
            expect("$palette.$list.return_tab", click(activity, tab))
            expect("$palette.$list.tab_viewport_retained", settled(activity, list) == before)
            for (mode in listOf("COMPACT", "PHOTOS")) {
              layout(activity, mode); frame(activity); layout(activity, "FULL")
              expect("$palette.$list.$mode.viewport_retained", settled(activity, list) == before)
            }
            main { activity.javaClass.getMethod("updateMusicViewportMetadata").invoke(activity) }
            expect("$palette.$list.metadata_viewport_retained", settled(activity, list) == before)
            capture(activity, palette, list)
            if (list == "queue") {
              binding(activity, "ma:generated-viewport", "generated-queue-b", 2)
              expect("$palette.queue.new_queue_starts_at_top",
                  settled(activity, list).any { it.first == "Generated queue row 0" })
            } else {
              binding(activity, "ma:generated-viewport", "generated-queue-c", 3)
              expect("$palette.player.queue_change_preserves_viewport",
                  settled(activity, list) == before)
              binding(activity, "ma:generated-other", "generated-queue-c", 4)
              expect("$palette.player.new_source_starts_at_top",
                  settled(activity, list).any { it.first == "Generated player row 0" })
              expect("$palette.queue.tab_after_source_change", click(activity, "Queue"))
              expect("$palette.queue.new_source_starts_at_top",
                  settled(activity, "queue").any { it.first == "Generated queue row 0" })
            }
          }
          completed++
        } catch (_: Throwable) {
          isolated++
          expect("$palette.isolated_failure", false)
        } finally {
          probe.removeMonitor(monitor)
          host?.let { activity ->
            runCatching { main(cleanup = true) { if (!activity.isDestroyed) activity.finish() } }
          }
        }
      }
    } finally {
      closing.set(true)
      if (registered) {
        main(cleanup = true) {
          owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
          app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
        while (remaining(cleanup = true) > 0 &&
            !main(cleanup = true) { owned.all { it.isDestroyed } }) SystemClock.sleep(30)
        expect("owned_activities_destroyed", main(cleanup = true) { owned.all { it.isDestroyed } })
      }
    }
    expect("bounded_work", SystemClock.elapsedRealtime() <= until)
    val passed = failures == 0 && completed == 2 && artifacts.length() == 4
    val report = JSONObject().put("checks", checks).put("artifacts", artifacts)
        .put("failureCount", failures).put("isolatedFailureCount", isolated)
        .put("caseCount", completed).put("renderCount", artifacts.length())
        .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("passed", passed)
        .put("generatedOnly", true).put("providerProof", false).put("audioProof", false)
    val file = File(probe.targetContext.cacheDir, "$prefix.json")
    file.writeText(report.toString(2))
    return Bundle().apply {
      putBoolean("musicViewportCompleted", true)
      putBoolean("musicViewportPassed", passed)
      putString("musicViewportReportRelativePath", "cache/${file.name}")
    }
  }

  private fun semantics(view: View): List<SemanticsNode> {
    val owner = (view as? RootForTest)?.semanticsOwner
    if (owner != null) {
      val nodes = mutableListOf<SemanticsNode>()
      fun visit(node: SemanticsNode) {
        if (!placed(node)) return
        nodes.add(node)
        node.children.forEach(::visit)
      }
      visit(owner.unmergedRootSemanticsNode)
      return nodes
    }
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { semantics(view.getChildAt(it)) }
  }

  private fun placed(node: SemanticsNode): Boolean {
    var info = node.layoutInfo
    while (true) {
      if (!info.isAttached || !info.isPlaced || info.isDeactivated) return false
      info = info.parentInfo ?: return true
    }
  }

  private fun containsText(node: SemanticsNode, text: String): Boolean =
      placed(node) && ((SemanticsProperties.Text in node.config &&
          node.config[SemanticsProperties.Text].any { it.text == text }) ||
          node.children.any { containsText(it, text) })
}
