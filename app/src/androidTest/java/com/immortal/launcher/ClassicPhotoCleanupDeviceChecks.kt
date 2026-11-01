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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Native Classic ownership regression using only the UUID-isolated generated-photo host. */
internal object ClassicPhotoCleanupDeviceChecks {
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
        .put("evidence", "native drawable and queue ownership; no physical FPS measurement")
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
    private var queuedForStop: Bitmap? = null
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
      val completed = exercised && destroyed && !registered && monitor == null
      record("total_elapsed_within_60s", SystemClock.elapsedRealtime() - began <= 60_000L)
      val passed = completed && !assertionFailure && !isolatedFailure
      report.put("passed", passed).put("completed", completed)
          .put("isolatedFailure", isolatedFailure).put("assertionFailure", assertionFailure)
          .put("ownedActivitiesDestroyed", destroyed).put("maxMainWaitMs", maxMainWaitMs)
          .put("elapsedMs", SystemClock.elapsedRealtime() - began)
      val file = File(context.cacheDir, "classic-cleanup-report-${UUID.randomUUID()}.json")
      var written = false
      try { file.writeText(report.toString(2)); written = true }
      catch (error: Throwable) { harness("report_write", error) }
      return Bundle().apply {
        putBoolean("classicCleanupPassed", passed && written && !isolatedFailure)
        putBoolean("completed", completed)
        putBoolean("isolatedFailure", isolatedFailure)
        putBoolean("assertionFailure", assertionFailure)
        putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
        if (written) putString("classicCleanupReportRelativePath", "cache/${file.name}")
      }
    }

    private fun exercise() {
      val controller = checkNotNull(frame)
      val ready = waitFor("real_generated_photo_displayed", 8000) {
        main {
          val views = photoViews(controller)
          val present = views.any {
            val bitmap = (it.drawable as? BitmapDrawable)?.bitmap
            it.isAttachedToWindow && it.visibility == View.VISIBLE && it.alpha > .01f &&
                bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
          }
          rememberDisplayed(views)
          present
        }
      }
      if (!ready) return
      val dimensions = main {
        val views = photoViews(controller)
        val playlist = field(controller, "playlist") as List<*>
        val ownedRoot = File(context.cacheDir, token).canonicalPath + File.separator
        val ownedPaths = playlist.isNotEmpty() && playlist.size <= 16 && playlist.all {
          val path = field(checkNotNull(it), "path") as String
          File(path).canonicalPath.startsWith(ownedRoot)
        }
        val decor = checkNotNull(host).window.decorView
        JSONObject().put("nativeWidth", decor.width).put("nativeHeight", decor.height)
            .put("photoWidth", views[0].width).put("photoHeight", views[0].height)
            .put("photoBackWidth", views[1].width).put("photoBackHeight", views[1].height)
            .put("generatedPhotoCount", playlist.size).put("ownedPaths", ownedPaths)
            .put("localMode", field(controller, "localMode"))
            .put("webViewAbsent", field(controller, "webView") == null)
      }
      report.put("native", dimensions)
      record("uuid_generated_local_source", dimensions.getBoolean("ownedPaths") &&
          dimensions.getBoolean("localMode") && dimensions.getBoolean("webViewAbsent"))
      record("native_views_have_dimensions", dimensions.getInt("photoWidth") > 0 &&
          dimensions.getInt("photoHeight") > 0 && dimensions.getInt("photoBackWidth") > 0 &&
          dimensions.getInt("photoBackHeight") > 0)
      // Let the first incoming layer become front, so admission populates the other layer.
      waitFor("initial_crossfade_settled", 2500) {
        main { field(controller, "photoFade") == null }
      }

      // Ordinary admission control: the same paused queue must deliver a live bitmap on resume.
      val admitted = inactiveDecode(controller, "admission")
      report.put("admissionBitmap", bitmapReport(admitted))
      main { controller.setHostActive(true) }
      val admittedToView = waitFor("ordinary_admission_displays_same_bitmap", 4000) {
        main {
          val views = photoViews(controller)
          rememberDisplayed(views)
          views.any {
            it.isAttachedToWindow && it.visibility == View.VISIBLE && it.alpha > .01f &&
                (it.drawable as? BitmapDrawable)?.bitmap === admitted
          }
        }
      }
      record("ordinary_admission_bitmap_unrecycled", admittedToView && !admitted.isRecycled)
      queuedForStop = inactiveDecode(controller, "stop")
      val queued = checkNotNull(queuedForStop)
      report.put("queuedBitmap", bitmapReport(queued))
      var before: List<Drawable?> = emptyList()
      var after: List<Drawable?> = emptyList()
      val counts = main {
        val views = photoViews(controller)
        rememberDisplayed(views)
        before = views.map { it.drawable }
        val ui = field(controller, "ui") as PausableFrameCallbacks
        val pendingBefore = ui.pendingCount
        controller.stop()
        after = views.map { it.drawable }
        JSONObject().put("pendingBeforeStop", pendingBefore).put("pendingAfterStop", ui.pendingCount)
      }
      report.put("counts", counts).put("drawablesBeforeStop", drawableReport(before))
          .put("drawablesAfterStop", drawableReport(after))
          .put("queuedBitmapRecycledAfterStop", queued.isRecycled)
      record("queued_never_displayed", displayed.none { it === queued })
      record("queued_bitmap_recycled_by_stop", queued.isRecycled)
      record("ui_pending_count_zero_after_stop", counts.getInt("pendingAfterStop") == 0)
      record("both_displayed_drawables_present_before_stop", before.all { it != null })
      record("photo_drawable_cleared_by_stop", after[0] == null)
      record("photoBack_drawable_cleared_by_stop", after[1] == null)
      record("displayed_bitmaps_not_recycled_by_stop", before.filterIsInstance<BitmapDrawable>()
          .all { !it.bitmap.isRecycled })
      exercised = true // Baseline production failures above are assertions, not harness failures.
    }

    private fun inactiveDecode(controller: PhotoFrameController, phase: String): Bitmap {
      val executor = main {
        controller.setHostActive(false)
        controller.javaClass.getDeclaredMethod("advanceLocal",
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, 1, false)
        field(controller, "io") as ExecutorService
      }
      requireWorker()
      val drained = executor.submit(Callable { true })
      try { drained.get(minOf(2000L, remaining()), TimeUnit.MILLISECONDS) }
      finally { drained.cancel(false) }
      return main {
        val ui = field(controller, "ui") as PausableFrameCallbacks
        val candidates = queuedBitmaps(ui)
        rememberDisplayed(photoViews(controller))
        val unseen = candidates.filter { bitmap -> displayed.none { it === bitmap } }
        report.put("${phase}PendingCount", ui.pendingCount)
            .put("${phase}CapturedBitmapCount", candidates.size)
        if (ui.isActive || unseen.size != 1 || unseen.single().isRecycled)
          throw HarnessFailure("${phase}_queued_bitmap_ownership")
        unseen.single()
      }
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
        // Only the captured queued decode is ours to recycle. Displayed drawables are left alone.
        val queued = queuedForStop
        if (destroyed && queued != null && displayed.none { it === queued } && !queued.isRecycled) {
          queued.recycle()
          report.put("baselineQueuedBitmapRecycledByHarness", true)
        } else report.put("baselineQueuedBitmapRecycledByHarness", false)
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

  private fun field(target: Any, name: String): Any? {
    if (!target.javaClass.name.startsWith("com.immortal.launcher."))
      throw HarnessFailure("foreign_reflection_target")
    return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
  }
}
