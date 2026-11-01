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
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Counts actual file reads for a paused Classic navigation burst in a UUID-owned photo folder. */
internal object ClassicPhotoBacklogDeviceChecks {
  private const val HOST = "com.immortal.launcher.NightPhotoPreviewActivity"
  fun run(instrumentation: Instrumentation): Bundle = Probe(instrumentation).run()

  private class HarnessFailure(message: String) : RuntimeException(message)

  private class Probe(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as Application
    private val token = "night-photo-owned-${UUID.randomUUID()}"
    private val began = SystemClock.elapsedRealtime()
    private var deadline = began + 45_000L // Reserve teardown/report time within the total minute.
    private val handler = Handler(Looper.getMainLooper())
    private val activities = mutableListOf<Activity>() // Main thread only.
    private val checks = JSONArray()
    private val report = JSONObject().put("mode", "classic").put("probeToken", token)
        .put("checks", checks).put("mainWaitLimitMs", 2000).put("totalLimitMs", 60_000)
        .put("evidence", "owned PNG file opens/closes, native drawables and queue ownership; no physical FPS measurement")
    private var host: Activity? = null
    private var frame: PhotoFrameController? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private var registered = false
    private var closing = false // Main thread only.
    private var destroyed = false
    private var exercised = false
    private var assertionFailure = false
    private var isolatedFailure = false
    private var maxMainWaitMs = 0L
    private val captured = mutableListOf<Bitmap>()
    private val releaseIo = CountDownLatch(1)
    private val enteredIo = CountDownLatch(1)
    private val exitedIo = CountDownLatch(1)
    private val blockerExpired = AtomicBoolean()
    private var watcher: PngObserver? = null
    private val displayed = mutableListOf<Bitmap>() // Never recycle these, even after destruction.

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (!owns(activity)) return
        activities.add(activity)
        if (closing) activity.finish()
      }
      override fun onActivityDestroyed(activity: Activity) = Unit
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityResumed(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun run(): Bundle {
      try {
        requireWorker()
        main {
          app.registerActivityLifecycleCallbacks(lifecycle)
          registered = true
        }
        monitor = instrumentation.addMonitor(HOST, null, false)
        main {
          context.startActivity(Intent().setClassName(context, HOST)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              .putExtra("owned_probe", token).putExtra("slideshow_mode", "classic")
              .putExtra("photo_motion", false))
        }
        waitFor("owned_activity_started", 5000) {
          val observed = monitor!!.waitForActivityWithTimeout(50) // Worker, bounded.
          host = main {
            (observed?.takeIf(::owns) ?: activities.lastOrNull())
                ?.takeUnless { it.isDestroyed || it.isFinishing }
          }
          host != null
        }
        val ownedHost = host ?: throw HarnessFailure("owned_activity_missing")
        frame = main { field(ownedHost, "frame") as PhotoFrameController }
        exercise()
      } catch (error: Throwable) {
        harness("exercise", error)
      } finally {
        deadline = began + 58_000L
        cleanup()
      }
      val completed = exercised && destroyed && !registered && monitor == null && watcher == null
      record("total_elapsed_within_60s", SystemClock.elapsedRealtime() - began <= 60_000L)
      val passed = completed && !assertionFailure && !isolatedFailure
      report.put("passed", passed).put("completed", completed)
          .put("isolatedFailure", isolatedFailure).put("assertionFailure", assertionFailure)
          .put("ownedActivitiesDestroyed", destroyed).put("maxMainWaitMs", maxMainWaitMs)
          .put("elapsedMs", SystemClock.elapsedRealtime() - began)
      val file = File(context.cacheDir, "classic-backlog-report-${UUID.randomUUID()}.json")
      var written = false
      try { file.writeText(report.toString(2)); written = true }
      catch (error: Throwable) { harness("report_write", error) }
      return Bundle().apply {
        putBoolean("classicBacklogPassed", passed && written && !isolatedFailure)
        putBoolean("completed", completed)
        putBoolean("isolatedFailure", isolatedFailure)
        putBoolean("assertionFailure", assertionFailure)
        putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
        if (written) putString("classicBacklogReportRelativePath", "cache/${file.name}")
      }
    }

    private fun exercise() {
      val controller = checkNotNull(frame)
      val ready = waitFor("initial_generated_photo_settled", 8000) {
        main {
          val views = photoViews(controller)
          rememberDisplayed(views)
          field(controller, "photoFade") == null && views.any {
            val bitmap = (it.drawable as? BitmapDrawable)?.bitmap
            it.isAttachedToWindow && it.visibility == View.VISIBLE && it.alpha > .01f &&
                bitmap != null && !bitmap.isRecycled
          }
        }
      }
      if (!ready) return
      val executors = main {
        (field(controller, "io") as ExecutorService) to
            (field(controller, "metaIo") as ExecutorService)
      }
      // All waits happen on the instrumentation worker. Finish initial decode/caption I/O first.
      drain(executors.first)
      drain(executors.second)
      val state = main {
        controller.setHostActive(false)
        val playlist = field(controller, "playlist") as List<*>
        if (playlist.size !in 13..16) throw HarnessFailure("generated_playlist_size")
        val folder = File(context.cacheDir, token).canonicalFile
        val paths = playlist.map { File(field(checkNotNull(it), "path") as String).canonicalFile }
        if (paths.any { it.parentFile != folder || it.extension != "png" })
          throw HarnessFailure("foreign_playlist_path")
        val initialIndex = (field(controller, "localIndex") as Number).toInt()
        val expectedIndex = (initialIndex + 12) % paths.size
        val decor = checkNotNull(host).window.decorView
        report.put("nativeWidth", decor.width).put("nativeHeight", decor.height)
            .put("generatedPhotoCount", paths.size).put("requestCount", 12)
            .put("initialLocalIndex", initialIndex).put("expectedLocalIndex", expectedIndex)
        Triple(folder, paths, expectedIndex)
      }
      // Read only the generated latest image's header BEFORE observing, so this read is excluded.
      val expectedFile = state.second[state.third]
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(expectedFile.path, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw HarnessFailure("generated_bounds")
      report.put("expectedFile", expectedFile.name).put("expectedWidth", bounds.outWidth)
          .put("expectedHeight", bounds.outHeight)
      val emptyBefore = main { queuedBitmaps(field(controller, "ui") as PausableFrameCallbacks).isEmpty() }
      if (!emptyBefore) throw HarnessFailure("initial_bitmap_queue_not_drained")

      watcher = PngObserver(state.first, state.second.map { it.name }.toSet())
      watcher!!.startWatching()
      // Own this blocker and release it in both the burst's finally and the outer teardown.
      executors.first.execute {
        enteredIo.countDown()
        try {
          if (!releaseIo.await(10_000, TimeUnit.MILLISECONDS)) blockerExpired.set(true)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        } finally { exitedIo.countDown() }
      }
      if (!enteredIo.await(minOf(2000L, remaining()), TimeUnit.MILLISECONDS))
        throw HarnessFailure("io_blocker_enter_timeout")
      try {
        main {
          val advance = controller.javaClass.getDeclaredMethod("advanceLocal",
              Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
              .apply { isAccessible = true }
          repeat(12) { advance.invoke(controller, 1, false) }
          report.put("actualLocalIndex", field(controller, "localIndex"))
        }
      } finally { releaseIo.countDown() }
      drain(executors.first, 8000)
      if (blockerExpired.get()) throw HarnessFailure("io_blocker_expired")
      record("owned_io_blocker_exited", exitedIo.count == 0L)
      val observed = checkNotNull(watcher)
      val quiet = waitFor("file_events_balanced_and_200ms_quiet", 2000) { observed.quiet() }
      // Stop BEFORE UI admission: EXIF caption reads must never enter the measured counts.
      observed.stop()
      watcher = null
      val events = observed.snapshot()
      report.put("fileEvents", events).put("fileObservationStable", quiet)
      record("actual_png_open_count_one", events.getInt("openCount") == 1)
      record("actual_png_close_count_one", events.getInt("closeCount") == 1)
      record("latest_png_was_last_opened", events.optString("lastOpenedFile") == expectedFile.name)
      main {
        val ui = field(controller, "ui") as PausableFrameCallbacks
        if (ui.isActive) throw HarnessFailure("queue_not_inactive")
        rememberDisplayed(photoViews(controller))
        captured.addAll(queuedBitmaps(ui))
        report.put("uiPendingBeforeAdmission", ui.pendingCount)
      }
      if (captured.isEmpty()) throw HarnessFailure("no_queued_bitmap_capture")
      report.put("queuedBitmapCount", captured.size)
          .put("queuedAllocationBytes", captured.sumOf { it.allocationByteCount.toLong() })
          .put("queuedBitmaps", JSONArray().apply { captured.forEach { put(bitmapReport(it)) } })
      record("queued_bitmap_count_one", captured.size == 1)
      record("captured_bitmaps_never_displayed_before_admission",
          captured.all { bitmap -> displayed.none { it === bitmap } })
      val latest = captured.last() // Entries preserve the single executor's decode/post order.
      record("latest_local_index_matches_burst", report.getInt("actualLocalIndex") == state.third)
      record("latest_bitmap_matches_generated_dimensions",
          latest.width == bounds.outWidth && latest.height == bounds.outHeight && !latest.isRecycled)
      main { controller.setHostActive(true) }
      val admitted = waitFor("latest_captured_bitmap_visibly_admitted", 4000) {
        main {
          val views = photoViews(controller)
          rememberDisplayed(views)
          views.any {
            it.isAttachedToWindow && it.visibility == View.VISIBLE && it.alpha > .01f &&
                (it.drawable as? BitmapDrawable)?.bitmap === latest
          }
        }
      }
      record("latest_admitted_bitmap_unrecycled", admitted && !latest.isRecycled)
      var before: List<Drawable?> = emptyList()
      var after: List<Drawable?> = emptyList()
      val pendingAfter = main {
        controller.setHostActive(false)
        val views = photoViews(controller)
        rememberDisplayed(views)
        before = views.map { it.drawable }
        controller.stop()
        after = views.map { it.drawable }
        (field(controller, "ui") as PausableFrameCallbacks).pendingCount
      }
      report.put("drawablesBeforeStop", drawableReport(before))
          .put("drawablesAfterStop", drawableReport(after)).put("uiPendingAfterStop", pendingAfter)
          .put("capturedBitmapsRecycledAfterStop", captured.count { it.isRecycled })
          .put("unpublishedCapturedBitmapCount",
              captured.count { bitmap -> displayed.none { it === bitmap } })
      record("all_unpublished_captured_bitmaps_recycled", captured
          .filter { bitmap -> displayed.none { it === bitmap } }.all { it.isRecycled })
      record("ui_pending_zero_after_stop", pendingAfter == 0)
      record("photo_drawable_cleared_by_stop", after[0] == null)
      record("photoBack_drawable_cleared_by_stop", after[1] == null)
      record("displayed_bitmaps_not_recycled_by_stop", before.filterIsInstance<BitmapDrawable>()
          .all { !it.bitmap.isRecycled })
      exercised = true // Baseline count failures are production assertions, not harness errors.
    }

    private fun drain(executor: ExecutorService, timeoutMs: Long = 2000) {
      requireWorker()
      val barrier = executor.submit(Callable { true })
      try { barrier.get(minOf(timeoutMs, remaining()), TimeUnit.MILLISECONDS) }
      finally { barrier.cancel(false) }
    }

    // Only this controller's queue entries and its own runnable closure fields are inspected.
    // No recursive walk through captured controller/context/view/global objects.
    private fun queuedBitmaps(ui: PausableFrameCallbacks): List<Bitmap> {
      val result = mutableListOf<Bitmap>()
      synchronized(checkNotNull(field(ui, "lock"))) {
        val entries = field(ui, "entries") as Collection<*>
        if (entries.size > 64) throw HarnessFailure("queue_reflection_bound")
        for (entry in entries) {
          val runnable = field(checkNotNull(entry), "runnable") ?: continue
          val type = runnable.javaClass
          if (!type.name.startsWith("com.immortal.launcher.PhotoFrameController")) continue
          val fields = type.declaredFields
          if (fields.size > 32) throw HarnessFailure("closure_reflection_bound")
          for (member in fields) {
            if (java.lang.reflect.Modifier.isStatic(member.modifiers)) continue
            if (!Bitmap::class.java.isAssignableFrom(member.type)) continue
            val bitmap = member.apply { isAccessible = true }.get(runnable) as? Bitmap ?: continue
            if (result.none { it === bitmap }) result.add(bitmap)
          }
        }
      }
      return result
    }

    private fun cleanup() {
      releaseIo.countDown()
      attempt("owned_file_observer_stopped") { watcher?.stop(); watcher = null }
      attempt("cancel_and_finish_owned") {
        main {
          closing = true
          Class.forName(HOST).getMethod("cancelOwnedProbe", String::class.java).invoke(null, token)
          activities.filter(::owns).forEach { if (!it.isDestroyed) it.finish() }
          host?.takeIf(::owns)?.let { if (!it.isDestroyed) it.finish() }
        }
      }
      attempt("owned_activity_destroyed") {
        destroyed = waitFor("owned_activities_destroyed", 5000) {
          main { activities.isNotEmpty() && activities.all { it.isDestroyed } }
        }
        if (!destroyed) throw HarnessFailure("owned_activity_not_destroyed")
      }
      attempt("never_displayed_baseline_bitmap_cleanup") {
        var recycled = 0
        if (destroyed) captured.filter { bitmap -> displayed.none { it === bitmap } }.forEach {
          if (!it.isRecycled) { it.recycle(); recycled++ }
        }
        report.put("baselineUnpublishedBitmapsRecycledByHarness", recycled)
      }
      attempt("monitor_removed") {
        monitor?.let { instrumentation.removeMonitor(it) }
        monitor = null
      }
      attempt("lifecycle_removed") {
        main {
          if (registered) app.unregisterActivityLifecycleCallbacks(lifecycle)
          registered = false
        }
      }
    }

    private fun owns(activity: Activity): Boolean =
        activity.javaClass.name == HOST && activity.intent.getStringExtra("owned_probe") == token

    private fun photoViews(controller: PhotoFrameController): List<ImageView> =
        listOf(field(controller, "photo") as ImageView, field(controller, "photoBack") as ImageView)

    private fun rememberDisplayed(views: List<ImageView>) {
      views.forEach {
        val bitmap = (it.drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && displayed.none { prior -> prior === bitmap }) displayed.add(bitmap)
      }
    }

    private fun bitmapReport(bitmap: Bitmap): JSONObject =
        JSONObject().put("identity", System.identityHashCode(bitmap))
            .put("allocationBytes", bitmap.allocationByteCount).put("byteCount", bitmap.byteCount)
            .put("width", bitmap.width).put("height", bitmap.height).put("recycled", bitmap.isRecycled)

    private fun drawableReport(refs: List<Drawable?>): JSONArray = JSONArray().apply {
      refs.forEach {
        put(if (it == null) JSONObject.NULL else JSONObject()
            .put("identity", System.identityHashCode(it))
            .put("bitmapIdentity", (it as? BitmapDrawable)?.bitmap?.let(System::identityHashCode)
                ?: JSONObject.NULL))
      }
    }

    private fun record(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) assertionFailure = true
    }

    private fun harness(name: String, error: Throwable) {
      isolatedFailure = true
      checks.put(JSONObject().put("name", name).put("passed", false).put("harnessError", true)
          .put("failureClass", error.javaClass.simpleName)
          .put("detail", error.message ?: error.javaClass.simpleName))
    }

    private fun attempt(name: String, block: () -> Unit) {
      try { block() } catch (error: Throwable) { harness(name, error) }
    }

    private fun waitFor(name: String, timeoutMs: Long, condition: () -> Boolean): Boolean {
      val until = minOf(deadline, SystemClock.elapsedRealtime() + timeoutMs)
      while (SystemClock.elapsedRealtime() < until) {
        if (condition()) { record(name, true); return true }
        SystemClock.sleep(minOf(50L, remaining()))
      }
      record(name, false)
      return false
    }

    private fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).also {
      if (it <= 0 || Thread.currentThread().isInterrupted) throw HarnessFailure("deadline")
    }

    private fun requireWorker() {
      if (Looper.myLooper() === Looper.getMainLooper()) throw HarnessFailure("worker_required")
    }

    private fun <T> main(block: () -> T): T {
      requireWorker()
      val task = FutureTask(Callable(block))
      val started = SystemClock.elapsedRealtime()
      try {
        if (!handler.post(task)) throw HarnessFailure("main_post_rejected")
        return task.get(minOf(2000L, remaining()), TimeUnit.MILLISECONDS)
      } catch (error: ExecutionException) {
        throw error.cause ?: error
      } finally {
        task.cancel(false)
        handler.removeCallbacks(task)
        maxMainWaitMs = maxOf(maxMainWaitMs, SystemClock.elapsedRealtime() - started)
      }
    }
  }

  @Suppress("DEPRECATION") // Path constructor also works on the Portal's API 28 firmware.
  private class PngObserver(folder: File, private val names: Set<String>) :
      FileObserver(folder.path, FileObserver.OPEN or FileObserver.CLOSE_NOWRITE) {
    private val lock = Any()
    private var accepting = true
    private var opens = 0
    private var closes = 0
    private var lastEvent = SystemClock.elapsedRealtime()
    private var lastOpened: String? = null
    private val byFile = linkedMapOf<String, Int>()

    override fun onEvent(event: Int, path: String?) {
      if (path == null || path !in names) return
      synchronized(lock) {
        if (!accepting) return
        if (event and FileObserver.OPEN != 0) {
          opens++
          lastOpened = path
          byFile[path] = (byFile[path] ?: 0) + 1
        }
        if (event and FileObserver.CLOSE_NOWRITE != 0) closes++
        lastEvent = SystemClock.elapsedRealtime()
      }
    }

    fun quiet(): Boolean = synchronized(lock) {
      opens > 0 && opens == closes && SystemClock.elapsedRealtime() - lastEvent >= 200
    }

    fun stop() {
      stopWatching()
      synchronized(lock) { accepting = false }
    }

    fun snapshot(): JSONObject = synchronized(lock) {
      JSONObject().put("openCount", opens).put("closeCount", closes)
          .put("lastOpenedFile", lastOpened ?: JSONObject.NULL)
          .put("opensByFile", JSONObject(byFile))
          .put("quietMs", SystemClock.elapsedRealtime() - lastEvent)
    }
  }

  private fun field(target: Any, name: String): Any? {
    if (!target.javaClass.name.startsWith("com.immortal.launcher."))
      throw HarnessFailure("foreign_reflection_target")
    return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
  }
}
