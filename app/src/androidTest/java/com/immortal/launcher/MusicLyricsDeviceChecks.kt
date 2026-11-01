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
import androidx.compose.ui.geometry.Rect
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

/** Generated paused metadata in owned native windows; no controller, provider or audio. */
internal object MusicLyricsDeviceChecks {
  private const val HOST = "com.immortal.launcher.MusicDesignPreviewActivity"
  private const val WORK_MS = 40_000L
  private const val TOTAL_MS = 55_000L
  private data class Label(val text: String, val bounds: Rect, val selected: Boolean)

  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val workUntil = began + WORK_MS
    val totalUntil = began + TOTAL_MS
    val token = UUID.randomUUID().toString()
    val prefix = "music-lyrics-owned-$token"
    val reportFile = File(probe.targetContext.cacheDir, "$prefix.json")
    val checks = JSONArray()
    val cases = JSONArray()
    val artifacts = JSONArray()
    var failures = 0
    var isolated = 0
    var renders = 0
    var completed = 0
    var workFinishedAt = began
    val handler = Handler(Looper.getMainLooper())
    val closing = AtomicBoolean(false)
    val owned = mutableListOf<Activity>()
    val application = probe.targetContext.applicationContext as Application
    fun expect(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failures++
    }
    fun remaining(limit: Long = 2_000, cleanup: Boolean = false): Long =
        minOf(limit, (if (cleanup) totalUntil else workUntil) -
            SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun <T> main(cleanup: Boolean = false, block: () -> T): T {
      val done = CountDownLatch(1)
      val cancelled = AtomicBoolean(false)
      var value: T? = null
      var failure: Throwable? = null
      check(remaining(cleanup = cleanup) > 0)
      handler.post {
        try {
          if (!cancelled.get()) value = block()
        } catch (thrown: Throwable) { failure = thrown }
        finally { done.countDown() }
      }
      if (!done.await(remaining(cleanup = cleanup), TimeUnit.MILLISECONDS)) {
        cancelled.set(true)
        error("owned_main_timeout")
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return value as T
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
      val drawn = CountDownLatch(1)
      var draws = 0
      var preDraws = 0
      val decor = main { activity.window.decorView }
      val before = ViewTreeObserver.OnPreDrawListener { preDraws++; true }
      val onDraw = ViewTreeObserver.OnDrawListener {
        if (++draws >= 2 && preDraws > 0) drawn.countDown()
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
        main(cleanup = true) {
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
        if (!placed(node)) return@flatMap emptyList()
        val config = node.config
        val bounds = node.boundsInWindow
        if (SemanticsProperties.Text !in config || bounds.width <= 0 || bounds.height <= 0 ||
            bounds.left >= decor.width || bounds.top >= decor.height ||
            bounds.right <= 0 || bounds.bottom <= 0) emptyList()
        else config[SemanticsProperties.Text].map {
          Label(it.text, bounds, SemanticsProperties.Selected in config &&
              config[SemanticsProperties.Selected])
        }
      }.distinctBy { it.text to it.bounds }
    }
    fun click(activity: Activity, text: String): Boolean = main {
      val nodes = semantics(activity.window.decorView)
      nodes.firstOrNull { node ->
        placed(node) && SemanticsActions.OnClick in node.config &&
            SemanticsProperties.Disabled !in node.config &&
            node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0 &&
            containsText(node, text)
      }?.config?.get(SemanticsActions.OnClick)?.action?.invoke() == true
    }
    fun followingDisabled(activity: Activity): Boolean = main {
      semantics(activity.window.decorView).any { node ->
        placed(node) && SemanticsProperties.Disabled in node.config &&
            containsText(node, "Following lyrics") &&
            node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0
      }
    }
    fun settle(activity: Activity, condition: (List<Label>) -> Boolean = { true }): List<Label> {
      val until = minOf(workUntil, SystemClock.elapsedRealtime() + 1_600)
      do {
        frame(activity)
        val now = labels(activity)
        if (condition(now)) return now
        SystemClock.sleep(40)
      } while (SystemClock.elapsedRealtime() < until)
      return labels(activity)
    }
    fun stableLyrics(activity: Activity): List<Label> {
      val until = minOf(workUntil, SystemClock.elapsedRealtime() + 1_200)
      var previous = emptyList<Label>()
      var stableFrames = 0
      do {
        val now = settle(activity).filter { it.text.startsWith("Generated lyric line ") }
        if (now.map { it.text to it.bounds } == previous.map { it.text to it.bounds })
          stableFrames++ else stableFrames = 0
        if (stableFrames >= 3) return now
        previous = now
        SystemClock.sleep(40)
      } while (SystemClock.elapsedRealtime() < until)
      return previous
    }
    fun currentVisible(activity: Activity, palette: String, phase: String, index: Int): List<Label> {
      val text = "Generated lyric line $index"
      settle(activity) { rows -> rows.any { it.text == text && it.selected } }
      val visible = stableLyrics(activity)
      expect("$palette.$phase.current_line_${index}_visible_selected",
          visible.any { it.text == text && it.selected })
      return visible
    }
    fun update(activity: Activity, index: Int, track: String = "generated-lyrics-track-a",
        source: String = "fixture:generated-lyrics-a") {
      main {
        // A public debug host method updates immutable input, never private player state.
        activity.javaClass.getMethod("updateMusicLyricsFixture", Int::class.javaPrimitiveType,
            String::class.java, String::class.java).invoke(activity, index, track, source)
      }
    }
    fun visibility(activity: Activity, active: Boolean) {
      main {
        activity.javaClass.getMethod("updateMusicLyricsVisibility", Boolean::class.javaPrimitiveType)
            .invoke(activity, active)
      }
      frame(activity)
    }
    fun drag(activity: Activity) {
      val rows = labels(activity).filter { it.text.startsWith("Generated lyric line ") }
      check(rows.isNotEmpty())
      val sorted = rows.sortedBy { it.bounds.top }
      val anchor = sorted[sorted.size / 2].bounds
      val down = SystemClock.uptimeMillis()
      var pressed = false
      fun dispatch(action: Int, y: Float, cleanup: Boolean = false) {
        main(cleanup) {
          val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
              anchor.center.x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
          try { activity.window.decorView.dispatchTouchEvent(event) }
          finally { event.recycle() }
        }
      }
      try {
        // Begin within a real lyric item; slow movement avoids a post-release fling.
        val distance = minOf(180f, (sorted.last().bounds.bottom - anchor.center.y) * .75f)
        check(distance >= 24f)
        dispatch(MotionEvent.ACTION_DOWN, anchor.center.y)
        pressed = true
        for (step in 1..10) {
          check(remaining() > 0)
          dispatch(MotionEvent.ACTION_MOVE, anchor.center.y + distance * step / 10f)
          SystemClock.sleep(35)
        }
        SystemClock.sleep(160)
        dispatch(MotionEvent.ACTION_UP, anchor.center.y + distance)
        pressed = false
      } finally {
        if (pressed) runCatching { dispatch(MotionEvent.ACTION_CANCEL, anchor.center.y, true) }
      }
    }
    fun capture(activity: Activity, palette: String, phase: String, index: Int) {
      frame(activity)
      val visible = labels(activity)
      val lyrics = visible.filter { it.text.startsWith("Generated lyric line ") }
      val footer = visible.filter { it.text in setOf("Follow lyrics", "Following lyrics") }
      expect("$palette.$phase.generated_lyrics_visible", lyrics.isNotEmpty())
      expect("$palette.$phase.lyric_rows_do_not_overlap",
          lyrics.indices.all { a -> (a + 1 until lyrics.size).all { b ->
            !lyrics[a].bounds.overlaps(lyrics[b].bounds)
          } })
      expect("$palette.$phase.footer_does_not_overlap_lyrics",
          footer.all { control -> lyrics.none { it.bounds.overlaps(control.bounds) } })
      val (width, height) = main { activity.window.decorView.let { it.width to it.height } }
      check(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608L)
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val copied = CountDownLatch(1)
      val lock = Any()
      var issued = false
      var terminal = false
      var abandoned = false
      var status = -1
      try {
        main {
          synchronized(lock) {
            check(!abandoned)
            issued = true
            try {
              PixelCopy.request(activity.window, bitmap, { result ->
                synchronized(lock) {
                  status = result
                  terminal = true
                  if (abandoned && !bitmap.isRecycled) bitmap.recycle()
                  copied.countDown()
                }
              }, handler)
            } catch (failure: Throwable) {
              terminal = true
              throw failure
            }
          }
        }
        check(copied.await(remaining(), TimeUnit.MILLISECONDS) && status == PixelCopy.SUCCESS)
        var nativePixels = 0
        val row = IntArray(width)
        val background = bitmap.getPixel(0, 0)
        for (y in 0 until height) {
          check(remaining() > 0)
          bitmap.getPixels(row, 0, width, 0, y, width, 1)
          nativePixels += row.count { it != background }
        }
        expect("$palette.$phase.native_pixels_present", nativePixels > 100)
        val file = File(probe.targetContext.cacheDir, "$prefix-$palette-$phase.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(file.length() > 0)
        artifacts.put(JSONObject().put("palette", palette).put("phase", phase)
            .put("activeIndex", index).put("relativePath", "cache/${file.name}")
            .put("path", file.absolutePath).put("width", width).put("height", height)
            .put("activeLineVisibleSelected", lyrics.any {
              it.text == "Generated lyric line $index" && it.selected
            })
            .put("nativePixels", nativePixels).put("visibleLyrics", JSONArray(lyrics.map {
              JSONObject().put("text", it.text).put("selected", it.selected)
                  .put("bounds", JSONArray(listOf(it.bounds.left, it.bounds.top,
                      it.bounds.right, it.bounds.bottom)))
            })).put("footerTexts", JSONArray(footer.map { it.text })))
        renders++
      } finally {
        // Never recycle a destination still owned by an outstanding PixelCopy callback.
        synchronized(lock) {
          abandoned = true
          if (!issued || terminal) {
            if (!bitmap.isRecycled) bitmap.recycle()
          }
        }
      }
    }
    var registered = false
    expect("worker_off_main", Looper.myLooper() != Looper.getMainLooper())
    try {
      if (Looper.myLooper() != Looper.getMainLooper()) {
        main { application.registerActivityLifecycleCallbacks(lifecycle); registered = true }
        for (palette in listOf("dark", "light")) {
          if (remaining() <= 0) break
          val row = JSONObject().put("palette", palette)
          cases.put(row)
          val monitor = probe.addMonitor(HOST, null, false)
          var activity: Activity? = null
          try {
            main {
              probe.targetContext.startActivity(Intent().setClassName(probe.targetContext, HOST)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  .putExtra("screen", "lyrics").putExtra("theme", palette)
                  .putExtra("owned_deadline_elapsed_ms", workUntil)
                  .putExtra("owned_probe", token))
            }
            val host = monitor.waitForActivityWithTimeout(remaining(4_000).coerceAtLeast(1))
            check(host != null)
            activity = host
            check(main { owned.any { it === host } &&
                host.intent.getStringExtra("owned_probe") == token })
            frame(host)
            expect("$palette.actual_lyrics_tab_clicked", click(host, "Lyrics"))
            currentVisible(host, palette, "initial", 45)
            val defaultFollowing = labels(host).any { it.text == "Following lyrics" }
            expect("$palette.default_following_label_visible", defaultFollowing)
            expect("$palette.default_following_button_disabled", followingDisabled(host))
            capture(host, palette, "initial", 45)
            update(host, 55)
            currentVisible(host, palette, "advance", 55)
            capture(host, palette, "advance", 55)
            var manual = false
            if (defaultFollowing) {
              val beforeDrag = stableLyrics(host)
              drag(host)
              val browsed = settle(host) { it.any { label -> label.text == "Follow lyrics" } }
              manual = browsed.any { it.text == "Follow lyrics" }
              expect("$palette.drag_shows_exact_follow_lyrics_button", manual)
              expect("$palette.local_touch_moves_lyric_viewport",
                  beforeDrag.map { it.text to it.bounds.top } != browsed.filter {
                    it.text.startsWith("Generated lyric line ")
                  }.map { it.text to it.bounds.top })
            }
            if (manual) {
              // Let the touch settle, then compare actual row coordinates after a position update.
              val stable = stableLyrics(host)
              update(host, 5)
              val after = stableLyrics(host)
              expect("$palette.manual_position_update_preserves_browse_viewport",
                  stable.map { it.text to it.bounds.top } == after.map { it.text to it.bounds.top })
              expect("$palette.manual_current_line_remains_offscreen",
                  after.none { it.text == "Generated lyric line 5" })
              capture(host, palette, "manual-advance", 5)
              expect("$palette.follow_button_click_accepted", click(host, "Follow lyrics"))
              currentVisible(host, palette, "follow", 5)
              expect("$palette.follow_button_returns_to_following",
                  labels(host).any { it.text == "Following lyrics" })
              expect("$palette.following_button_disabled_after_resume", followingDisabled(host))
              capture(host, palette, "follow", 5)
              visibility(host, false)
              val hiddenBefore = stableLyrics(host)
              update(host, 65)
              val hiddenAfter = stableLyrics(host)
              expect("$palette.hidden_position_update_preserves_viewport",
                  hiddenBefore.map { it.text to it.bounds.top } ==
                      hiddenAfter.map { it.text to it.bounds.top })
              expect("$palette.hidden_current_line_stays_offscreen",
                  hiddenAfter.none { it.text == "Generated lyric line 65" })
              visibility(host, true)
              currentVisible(host, palette, "visible-resume", 65)
              drag(host)
              expect("$palette.second_drag_disables_following",
                  settle(host) { it.any { label -> label.text == "Follow lyrics" } }
                      .any { it.text == "Follow lyrics" })
            } else row.put("manualCasesSkipped", "follow_button_absent")
            update(host, 35, "generated-lyrics-track-b")
            currentVisible(host, palette, "track-reset", 35)
            expect("$palette.track_change_resets_following",
                labels(host).any { it.text == "Following lyrics" })
            capture(host, palette, "track-reset", 35)
            if (manual) {
              drag(host)
              expect("$palette.drag_before_source_change_disables_following",
                  settle(host) { it.any { label -> label.text == "Follow lyrics" } }
                      .any { it.text == "Follow lyrics" })
              update(host, 65, "generated-lyrics-track-b", "fixture:generated-lyrics-b")
              currentVisible(host, palette, "source-reset", 65)
              expect("$palette.source_change_resets_following",
                  labels(host).any { it.text == "Following lyrics" })
            }
            row.put("completed", true)
            completed++
          } catch (_: Throwable) {
            isolated++
            expect("$palette.isolated_failure", false)
          } finally {
            probe.removeMonitor(monitor)
            activity?.let { host ->
              runCatching { main(cleanup = true) { if (!host.isDestroyed) host.finish() } }
                  .onFailure { expect("$palette.owned_activity_finish", false) }
            }
          }
        }
      }
    } finally {
      workFinishedAt = SystemClock.elapsedRealtime()
      closing.set(true)
      if (registered) {
        try {
          main(cleanup = true) {
            owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
          }
          val until = minOf(totalUntil, SystemClock.elapsedRealtime() + 4_000)
          while (SystemClock.elapsedRealtime() < until &&
              !main(cleanup = true) { owned.all { it.isDestroyed } }) SystemClock.sleep(40)
          expect("owned_activities_destroyed", main(cleanup = true) { owned.all { it.isDestroyed } })
        } finally {
          main(cleanup = true) { application.unregisterActivityLifecycleCallbacks(lifecycle) }
        }
      }
    }
    expect("work_budget_respected", workFinishedAt - began <= WORK_MS)
    expect("total_budget_respected", SystemClock.elapsedRealtime() - began <= TOTAL_MS)
    val passed = failures == 0 && completed == 2 && renders == 10
    val report = JSONObject().put("checks", checks).put("cases", cases).put("artifacts", artifacts)
        .put("caseCount", completed).put("renderCount", renders).put("checkCount", checks.length())
        .put("failureCount", failures).put("isolatedFailureCount", isolated).put("passed", passed)
        .put("generatedOnly", true).put("providerProof", false).put("audioProof", false)
        .put("commandProof", false).put("workBudgetMs", WORK_MS).put("totalBudgetMs", TOTAL_MS)
        .put("workElapsedMs", workFinishedAt - began).put("fixtureLineCount", 80)
        .put("timingStepMs", 1_000).put("fixturePlayback", "PAUSED").put("layout", "FULL")
        .put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("relativeReportPath", "cache/${reportFile.name}")
    var written = false
    try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
    catch (_: Throwable) { /* Generated labels and private artifact paths only. */ }
    return Bundle().apply {
      putBoolean("musicLyricsCompleted", true)
      putBoolean("musicLyricsPassed", passed && written)
      putBoolean("musicLyricsNativePassed", passed && written)
      putInt("musicLyricsCaseCount", completed)
      putInt("musicLyricsRenderCount", renders)
      putInt("musicLyricsCheckCount", checks.length())
      putInt("musicLyricsFailureCount", failures)
      putInt("musicLyricsIsolatedFailureCount", isolated)
      putString("musicLyricsChecksJson", checks.toString())
      putString("musicLyricsReportRelativePath", "cache/${reportFile.name}")
      putString("musicLyricsReportPath", reportFile.absolutePath)
      putString("musicLyricsArtifactPaths", artifacts.toString())
    }
  }

  /** Same public RootForTest traversal as the generated repeated-items probe. */
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

  /**
   * Public LayoutInfo only: lazy prefetch can retain attached, unplaced nodes with stale bounds.
   * Check layout ancestors too, including parents without their own semantics.
   */
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
