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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

/** Real legacy controller/DOM evidence from UUID-owned generated media; no hardware claims. */
internal object NightLegacyPhotoDeviceChecks {
  fun run(instrumentation: Instrumentation): Bundle = Probe(instrumentation).run()

  private const val HOST = "com.immortal.launcher.NightPhotoPreviewActivity"
  private val modes = linkedMapOf(
      "kenBurns" to ".ken-burns",
      "diptych" to ".ss-frame",
      "framed" to ".ss-art",
      "galleryWall" to ".gallery-wall",
      "push" to ".cinematic-push",
      "memories" to ".ss-memory-scene",
      "panorama" to ".panorama-drift",
      "polaroid" to ".ss-table",
      "mosaic" to ".mosaic-wall",
      "cinema" to ".ss-scope",
      "heroSidebar" to ".ss-hero-sidebar",
  )

  private class Deadline : RuntimeException()
  private class CaseFailure(val label: String) : RuntimeException()
  private class HarnessFailure(val label: String) : RuntimeException()

  private class Owned(val mode: String, val token: String) {
    val activities = mutableListOf<Activity>() // Accessed only by main rendezvous/callbacks.
    val closing = AtomicBoolean()
    var host: Activity? = null
    var controller: PhotoFrameController? = null
    var web: WebView? = null
    var monitor: Instrumentation.ActivityMonitor? = null
    val checks = JSONArray()
    val samples = JSONArray()
    val report = JSONObject().put("mode", mode).put("checks", checks).put("samples", samples)
    var failed = false
    var exercised = false
    var destroyed = false
  }

  private class Probe(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private val began = SystemClock.elapsedRealtime()
    private val workDeadline = began + 300_000L
    private var deadline = workDeadline
    private val closing = AtomicBoolean()
    private val cases = mutableListOf<Owned>() // Populated/queried on Main.
    private val reports = JSONArray()
    private val checks = JSONArray()
    private var failed = false
    private var isolatedFailure = false
    private var assertionFailure = false
    private var registered = false
    private var completedCaseCount = 0
    private var maxMainWaitMs = 0L

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity.javaClass.name != HOST) return
        val token = activity.intent.getStringExtra("owned_probe")
        val owner = cases.firstOrNull { it.token == token } ?: return
        owner.activities.add(activity)
        if (closing.get() || owner.closing.get()) activity.finish()
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
        check(Looper.myLooper() !== Looper.getMainLooper())
        main("register_lifecycle") { app.registerActivityLifecycleCallbacks(lifecycle) }
        registered = true
        for ((mode, marker) in modes) {
          bounded()
          val owner = Owned(mode, "night-photo-owned-${UUID.randomUUID()}")
          main("track_owned_case") { cases.add(owner) }
          reports.put(owner.report)
          // Count an attempt even if this case consumes the final work budget.
          completedCaseCount++
          val started = SystemClock.elapsedRealtime()
          try {
            exercise(owner, marker)
          } catch (error: Throwable) {
            failure(owner, error)
            if (error is Deadline) throw error
          } finally {
            owner.report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("performed", owner.exercised)
            if (SystemClock.elapsedRealtime() < workDeadline) cleanup(owner)
            owner.report.put("passed", owner.exercised && !owner.failed && owner.destroyed)
          }
        }
      } catch (error: Throwable) {
        failed = true
        isolatedFailure = true
        checks.put(JSONObject().put("name", "harness_work")
            .put("passed", false).put("failureClass", error.javaClass.simpleName))
      } finally {
        closing.set(true)
        deadline = minOf(began + 315_000L, SystemClock.elapsedRealtime() + 15_000L)
        val tracked = try { main("tracked_cases") { cases.toList() } }
        catch (_: Throwable) { failed = true; isolatedFailure = true; emptyList() }
        for (owner in tracked) {
          if (SystemClock.elapsedRealtime() >= deadline) { failed = true; break }
          if (!owner.destroyed || owner.monitor != null) cleanup(owner)
          owner.report.put("passed", owner.exercised && !owner.failed && owner.destroyed)
        }
        attempt("lifecycle_removed") {
          main("unregister_lifecycle") {
            if (registered) app.unregisterActivityLifecycleCallbacks(lifecycle)
            registered = false
          }
        }
      }
      val tracked = cases.toList()
      val destroyed = tracked.isNotEmpty() && tracked.all { it.destroyed }
      val completed = completedCaseCount == modes.size &&
          tracked.size == modes.size && tracked.all { it.exercised } && destroyed && !registered
      val passed = completed && !failed && tracked.none { it.failed }
      val elapsed = SystemClock.elapsedRealtime() - began
      val report = JSONObject().put("passed", passed).put("completed", completed)
          .put("elapsedMs", elapsed).put("caseCount", tracked.size)
          .put("completedCaseCount", completedCaseCount)
          .put("performedCaseCount", tracked.count { it.exercised })
          .put("ownedActivitiesDestroyed", destroyed).put("isolatedFailure", isolatedFailure)
          .put("assertionFailure", assertionFailure)
          .put("checks", checks).put("modes", reports)
          .put("maxMainWaitMs", maxMainWaitMs).put("mainWaitLimitMs", 2000)
          .put("workLimitMs", 300_000).put("teardownLimitMs", 15_000)
          .put("generatedPhotoCountPerCase", 16).put("photoIntervalMs", 5000)
          .put("evidence", "controller fields and current DOM; no physical brightness or FPS measurement")
      val file = File(context.cacheDir, "night-legacy-report-${UUID.randomUUID()}.json")
      var reportWritten = false
      try { file.writeText(report.toString(2)); reportWritten = true }
      catch (_: Throwable) { failed = true; isolatedFailure = true }
      return Bundle().apply {
        putBoolean("nightLegacyPassed", passed && reportWritten && !failed)
        putBoolean("completed", completed)
        putInt("caseCount", tracked.size)
        putInt("completedCaseCount", completedCaseCount)
        putBoolean("isolatedFailure", isolatedFailure)
        putBoolean("assertionFailure", assertionFailure)
        putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
        if (reportWritten) putString("nightLegacyReportRelativePath", "cache/${file.name}")
      }
    }

    private fun exercise(owner: Owned, marker: String) {
      owner.monitor = instrumentation.addMonitor(HOST, null, false)
      main("launch_owned") {
        context.startActivity(Intent().setClassName(context, HOST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("owned_probe", owner.token).putExtra("slideshow_mode", owner.mode)
            .putExtra("photo_motion", true))
      }
      val launchEnd = minOf(deadline, SystemClock.elapsedRealtime() + 5000)
      while (SystemClock.elapsedRealtime() < launchEnd && owner.host == null) {
        // Monitor waits occur on this worker, never on Main, and cannot strand the harness.
        val observed = owner.monitor!!.waitForActivityWithTimeout(100)
        owner.host = main("owned_host") {
          val candidate = observed?.takeIf {
            it.javaClass.name == HOST && it.intent.getStringExtra("owned_probe") == owner.token
          } ?: owner.activities.lastOrNull()
          candidate?.takeUnless { it.isFinishing || it.isDestroyed }
        }
      }
      demand(owner, "owned_activity_started", owner.host != null)
      owner.controller = main("owned_controller") {
        field(checkNotNull(owner.host), "frame") as PhotoFrameController
      }
      waitFor(owner, "controller_webview_attached", 5000) {
        main("webview_ready") {
          val controller = checkNotNull(owner.controller)
          val web = field(controller, "webView") as? WebView
          if (web != null && web.isAttachedToWindow && webViews(controller.view).contains(web)) {
            owner.web = web
            true
          } else false
        }
      }.also { demand(owner, "controller_webview_attached_required", it) }

      var ready: JSONObject? = null
      val readyEnd = minOf(deadline, SystemClock.elapsedRealtime() + 5000)
      var previous: JSONObject? = null
      while (SystemClock.elapsedRealtime() < readyEnd) {
        val current = sample(owner, marker, "readiness")
        if (current.optBoolean("ready") && current.optBoolean("ownedLibrary") &&
            current.optBoolean("modeMarker") && current.optBoolean("imagesOwned") &&
            previous?.optBoolean("ready") == true && sameObjects(previous!!, current) &&
            stableOpacity(previous!!, current)) {
          ready = current
          break
        }
        previous = current
        sleep(150)
      }
      demand(owner, "generated_mode_stably_ready", ready != null)
      record(owner, "native_full_window_and_photo_viewport", main("viewport_dimensions") {
        val decor = checkNotNull(owner.host).window.decorView
        val web = checkNotNull(owner.web)
        owner.report.put("nativeWidth", decor.width).put("nativeHeight", decor.height)
            .put("webViewWidth", web.width).put("webViewHeight", web.height)
        decor.width == 2160 && decor.height == 1440 &&
            web.width == 2160 && web.height == 1440
      })
      record(owner, "correct_controller_webview_before_night", main("verify_webview_owner") {
        val controller = checkNotNull(owner.controller)
        field(controller, "webView") === owner.web &&
            webViews(controller.view).count { it === owner.web } == 1 &&
            field(controller, "hostActive") == true && field(controller, "paused") == false
      })
      // A partial dwell catches restarts without depending on exact admission/fade duration.
      sleep(1000)
      val before = sample(owner, marker, "partial_dwell")
      demand(owner, "partial_dwell_has_live_owned_scene", before.optBoolean("ready") &&
          before.optBoolean("modeMarker") && before.optBoolean("ownedLibrary"))
      var nightEnteredAt = 0L
      val generation = main("night_entry") {
        val controller = checkNotNull(owner.controller)
        nightEnteredAt = SystemClock.elapsedRealtime()
        invokeBoolean(controller, "setAmbientNight", true)
        record(owner, "entry_immediately_host_inactive", field(controller, "hostActive") == false)
        field(controller, "gen")
      }
      // Removing applyEnginePlayback's pause must change real scene/time during this hold;
      // losing its resume must prevent a later image-URI advancement after the reveal.
      // WebView pause is asynchronous: establish the reference after DOM acknowledgement.
      waitFor(owner, "dom_pause_acknowledged", 2000) {
        val current = sample(owner, marker, "pause_ack")
        current.optBoolean("paused")
      }
      sleep(250) // Pause acknowledgement can precede the final compositor frame.
      val frozen = sample(owner, marker, "settled_pause_reference")
      record(owner, "settled_dom_is_paused", frozen.optBoolean("paused"))
      settle(owner, true, generation, nightEnteredAt)
      val holdBegan = SystemClock.elapsedRealtime()
      var holdSamples = 0
      do {
        val current = sample(owner, marker, "night_hold")
        record(owner, "night_frozen_${holdSamples++}",
            current.optBoolean("paused") && sameObjects(frozen, current) &&
                samePose(frozen, current) && sameAnimationTimes(frozen, current))
        record(owner, "night_controller_retained_$holdSamples",
            retained(owner, generation, active = false))
        if (SystemClock.elapsedRealtime() - holdBegan < 6000) sleep(250)
      } while (SystemClock.elapsedRealtime() - holdBegan < 6000)
      val held = sample(owner, marker, "night_hold_end")
      record(owner, "night_frozen_past_photo_interval", sameObjects(frozen, held) &&
          samePose(frozen, held) && sameAnimationTimes(frozen, held) && held.optBoolean("paused"))
      owner.report.put("nightHoldMs", SystemClock.elapsedRealtime() - holdBegan)
          .put("nightHoldSamples", holdSamples + 1)
          .put("getAnimationsSupported", frozen.optBoolean("getAnimationsSupported"))
          .put("cssMatrixSampled", hasMatrix(frozen))

      var nightExitedAt = 0L
      main("night_exit") {
        nightExitedAt = SystemClock.elapsedRealtime()
        invokeBoolean(checkNotNull(owner.controller), "setAmbientNight", false)
      }
      val returnEnd = minOf(deadline, nightExitedAt + 5000)
      var revealed = false
      var revealedAt = 0L
      var exitSamples = 0
      while (SystemClock.elapsedRealtime() < returnEnd) {
        val current = sample(owner, marker, "night_reveal")
        // Read native state after the DOM callback: a cover finishing between two
        // rendezvous must not compare a resumed DOM to a stale inactive-host value.
        val state = nativeState(owner)
        record(owner, "reveal_objects_retained_${exitSamples++}", sameObjects(frozen, current) &&
            state.optInt("generation") == (generation as Number).toInt() &&
            state.optBoolean("sameWebView") && !state.optBoolean("paused"))
        if (state.optDouble("reveal") == 0.0 && state.optBoolean("hostActive") &&
            SystemClock.elapsedRealtime() <= returnEnd) {
          revealed = true
          revealedAt = SystemClock.elapsedRealtime()
          record(owner, "reveal_animation_continuity", continuousAnimationTimes(frozen, current))
          record(owner, "reveal_css_matrix_continuity", continuousPose(frozen, current))
          break
        }
        record(owner, "reveal_keeps_photos_frozen_$exitSamples", !state.optBoolean("hostActive") &&
            current.optBoolean("paused") && samePose(frozen, current) &&
            sameAnimationTimes(frozen, current))
        sleep(100)
      }
      record(owner, "reveal_settled_zero_within_5s", revealed)
      val resumed = sample(owner, marker, "revealed_reference")
      record(owner, "revealed_scene_and_image_objects_retained", sameObjects(frozen, resumed))
      val resumeBegan = if (revealed) revealedAt else SystemClock.elapsedRealtime()
      var advanced: JSONObject? = null
      waitFor(owner, "content_advances_within_8s_after_reveal",
          (8000 - (SystemClock.elapsedRealtime() - resumeBegan)).coerceAtLeast(0)) {
        val current = sample(owner, marker, "continuation")
        val changed = current.optBoolean("ready") && current.optBoolean("imagesOwned") &&
            !current.optBoolean("paused") &&
            (imageIdentity(current) - imageIdentity(resumed)).isNotEmpty()
        if (changed) advanced = current
        changed
      }
      record(owner, "same_controller_webview_after_continuation", main("continuation_owner") {
        field(checkNotNull(owner.controller), "webView") === owner.web
      })
      owner.report.put("contentAdvanced", advanced != null)
          .put("firstContentAdvanceMs", if (advanced == null) JSONObject.NULL
              else SystemClock.elapsedRealtime() - resumeBegan)
          .put("motionAlsoAdvanced", advanced?.let {
            !samePose(resumed, it) || !sameAnimationTimes(resumed, it)
          } ?: false)
      owner.exercised = true
    }

    private fun nativeState(owner: Owned): JSONObject = main("controller_sample") {
      val controller = checkNotNull(owner.controller)
      JSONObject().put("hostActive", field(controller, "hostActive"))
          .put("paused", field(controller, "paused")).put("generation", field(controller, "gen"))
          .put("reveal", (field(controller, "nightTransition") as NightClockTransition).reveal)
          .put("sameWebView", field(controller, "webView") === owner.web)
    }

    private fun retained(owner: Owned, generation: Any?, active: Boolean): Boolean {
      val state = nativeState(owner)
      return state.optBoolean("sameWebView") && state.optBoolean("hostActive") == active &&
          !state.optBoolean("paused") && state.optInt("generation") == (generation as Number).toInt()
    }

    private fun settle(owner: Owned, night: Boolean, generation: Any?, started: Long) {
      waitFor(owner, "night_reveal_settled_${if (night) "one" else "zero"}_within_5s",
          (5000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)) {
        val state = nativeState(owner)
        state.optDouble("reveal") == (if (night) 1.0 else 0.0) &&
            retained(owner, generation, active = !night)
      }
    }

    private fun sample(owner: Owned, marker: String, phase: String): JSONObject {
      bounded()
      val done = CountDownLatch(1)
      val accepting = AtomicBoolean(true)
      var raw: String? = null
      try {
        main("evaluate_dom") {
          val controller = checkNotNull(owner.controller)
          val web = checkNotNull(owner.web)
          check(field(controller, "webView") === web)
          web.evaluateJavascript(script(owner.token, marker)) { value ->
            if (accepting.get() && !owner.closing.get()) raw = value
            done.countDown()
          }
        }
        if (!done.await(minOf(2000L, remaining()), TimeUnit.MILLISECONDS))
          throw HarnessFailure("javascript_callback_timeout")
        val decoded = JSONTokener(checkNotNull(raw)).nextValue()
        val result = if (decoded is JSONObject) decoded else JSONObject(decoded as String)
        result.put("elapsedMs", SystemClock.elapsedRealtime() - began).put("phase", phase)
        owner.samples.put(result)
        return result
      } finally { accepting.set(false) }
    }

    private fun cleanup(owner: Owned) {
      owner.closing.set(true)
      try {
        main("cancel_and_finish_owned") {
          Class.forName(HOST).getMethod("cancelOwnedProbe", String::class.java).invoke(null, owner.token)
          owner.activities.forEach { if (!it.isDestroyed) it.finish() }
          owner.host?.takeIf {
            it.intent.getStringExtra("owned_probe") == owner.token && !it.isDestroyed
          }?.finish()
        }
        val stop = minOf(deadline, SystemClock.elapsedRealtime() + 2000)
        do {
          owner.destroyed = main("owned_destroyed") {
            owner.activities.isNotEmpty() && owner.activities.all { it.isDestroyed }
          }
          if (!owner.destroyed) sleep(50)
        } while (!owner.destroyed && SystemClock.elapsedRealtime() < stop)
        record(owner, "owned_activities_destroyed", owner.destroyed)
        // The fixture's onDestroy owns media/prefs removal; the harness only observes.
        record(owner, "owned_generated_media_removed", !File(context.cacheDir, owner.token).exists())
        val preferences = File(context.applicationInfo.dataDir, "shared_prefs")
        record(owner, "owned_preferences_removed",
            preferences.listFiles().orEmpty().none { it.name.startsWith(owner.token) })
      } catch (error: Throwable) { failure(owner, error) }
      finally {
        try {
          owner.monitor?.let { instrumentation.removeMonitor(it) }
          owner.monitor = null
        } catch (error: Throwable) { failure(owner, error) }
      }
    }

    private fun record(owner: Owned, name: String, pass: Boolean) {
      owner.checks.put(JSONObject().put("name", name).put("passed", pass))
      checks.put(JSONObject().put("name", "${owner.mode}_$name")
          .put("mode", owner.mode).put("passed", pass))
      if (!pass) { owner.failed = true; failed = true; assertionFailure = true }
    }

    private fun demand(owner: Owned, name: String, pass: Boolean) {
      record(owner, name, pass)
      if (!pass) throw CaseFailure(name)
    }

    private fun failure(owner: Owned, error: Throwable) {
      owner.failed = true
      failed = true
      if (error !is CaseFailure) isolatedFailure = true
      val label = when (error) {
        is CaseFailure -> error.label
        is HarnessFailure -> error.label
        else -> "case_error"
      }
      owner.checks.put(JSONObject().put("name", label)
          .put("passed", false).put("failureClass", error.javaClass.simpleName))
      checks.put(JSONObject().put("name", "${owner.mode}_$label").put("mode", owner.mode)
          .put("passed", false).put("failureClass", error.javaClass.simpleName))
    }

    private fun attempt(name: String, block: () -> Unit) {
      try { block(); checks.put(JSONObject().put("name", name).put("passed", true)) }
      catch (error: Throwable) {
        failed = true
        isolatedFailure = true
        checks.put(JSONObject().put("name", name).put("passed", false)
            .put("failureClass", error.javaClass.simpleName))
      }
    }

    private fun waitFor(owner: Owned, label: String, timeout: Long, condition: () -> Boolean): Boolean {
      val until = minOf(deadline, SystemClock.elapsedRealtime() + timeout)
      while (SystemClock.elapsedRealtime() < until) {
        if (condition() && SystemClock.elapsedRealtime() <= until) {
          record(owner, label, true)
          return true
        }
        sleep(100)
      }
      bounded()
      record(owner, label, false)
      return false
    }

    private fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).also {
      if (it <= 0 || Thread.currentThread().isInterrupted) throw Deadline()
    }

    private fun bounded() { remaining() }

    private fun sleep(ms: Long) { SystemClock.sleep(minOf(ms, remaining())) }

    private fun <T> main(label: String, block: () -> T): T {
      check(Looper.myLooper() !== Looper.getMainLooper())
      val limit = minOf(2000L, remaining())
      val task = FutureTask(Callable(block))
      val started = SystemClock.elapsedRealtime()
      check(mainHandler.post(task))
      try { return task.get(limit, TimeUnit.MILLISECONDS) }
      catch (error: ExecutionException) { throw error.cause ?: CaseFailure(label) }
      catch (_: java.util.concurrent.TimeoutException) { throw HarnessFailure("${label}_main_timeout") }
      finally {
        task.cancel(false)
        mainHandler.removeCallbacks(task)
        maxMainWaitMs = maxOf(maxMainWaitMs, SystemClock.elapsedRealtime() - started)
      }
    }
  }

  private fun field(target: Any, name: String): Any? =
      target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

  private fun invokeBoolean(controller: PhotoFrameController, name: String, value: Boolean) {
    controller.javaClass.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(controller, value)
  }

  private fun webViews(view: View): List<WebView> = when (view) {
    is WebView -> listOf(view)
    is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
    else -> emptyList()
  }

  private fun nodes(sample: JSONObject): List<JSONObject> = sample.optJSONArray("nodes")?.let { array ->
    (0 until array.length()).map { array.getJSONObject(it) }
  } ?: emptyList()

  private fun sameObjects(a: JSONObject, b: JSONObject): Boolean =
      a.optString("documentId") == b.optString("documentId") &&
          a.optInt("sceneId") == b.optInt("sceneId") &&
          a.optString("sceneClass") == b.optString("sceneClass") &&
          nodes(a).map { listOf(it.optInt("id"), it.optInt("backgroundId"), it.optInt("srcId"),
            it.optString("class")) } ==
          nodes(b).map { listOf(it.optInt("id"), it.optInt("backgroundId"), it.optInt("srcId"),
            it.optString("class")) }

  private fun stableOpacity(a: JSONObject, b: JSONObject): Boolean {
    val other = nodes(b).associateBy { it.getInt("id") }
    return nodes(a).all { n ->
      other[n.getInt("id")]?.let { abs(n.getDouble("opacity") - it.getDouble("opacity")) < .005 } == true
    }
  }

  private fun samePose(a: JSONObject, b: JSONObject): Boolean {
    val other = nodes(b).associateBy { it.getInt("id") }
    return nodes(a).all { n ->
      val retained = other[n.getInt("id")] ?: return@all false
      val x = n.getJSONArray("matrix")
      val y = retained.getJSONArray("matrix")
      x.length() == y.length() && (0 until x.length()).all {
        abs(x.getDouble(it) - y.getDouble(it)) <= .01
      } && abs(n.getDouble("opacity") - retained.getDouble("opacity")) <= .005 &&
          n.optString("backgroundPosition") == retained.optString("backgroundPosition") &&
          n.optString("visibility") == retained.optString("visibility")
    }
  }

  private fun animationTimes(sample: JSONObject): Map<Int, Double> {
    val array = sample.optJSONArray("animations") ?: return emptyMap()
    return (0 until array.length()).map { array.getJSONObject(it) }
        .filter { !it.isNull("currentTime") }
        .associate { it.getInt("id") to it.getDouble("currentTime") }
  }

  // Capability is reported independently; an absent optional API never substitutes for DOM checks.
  private fun sameAnimationTimes(a: JSONObject, b: JSONObject): Boolean {
    if (!a.optBoolean("getAnimationsSupported") || !b.optBoolean("getAnimationsSupported")) return true
    val other = animationTimes(b)
    return animationTimes(a).all { (id, time) -> other[id]?.let { abs(it - time) <= 50 } ?: false }
  }

  private fun continuousAnimationTimes(a: JSONObject, b: JSONObject): Boolean {
    if (!a.optBoolean("getAnimationsSupported") || !b.optBoolean("getAnimationsSupported")) return true
    val other = animationTimes(b)
    return animationTimes(a).all { (id, time) -> other[id]?.let { it - time in -50.0..500.0 } ?: false }
  }

  private fun continuousPose(a: JSONObject, b: JSONObject): Boolean {
    val other = nodes(b).associateBy { it.getInt("id") }
    return nodes(a).all { n ->
      val retained = other[n.getInt("id")] ?: return@all false
      val x = n.getJSONArray("matrix")
      val y = retained.getJSONArray("matrix")
      // Allow the first resumed frame of a wide panorama, but reject a restart
      // of its retained sweep or a large zoom jump. Exact stability is checked
      // throughout the covered samples, including on engines without getAnimations.
      x.length() == y.length() && (0 until x.length()).all {
        val translation = if (x.length() == 6) it == 4 || it == 5 else it in 12..14
        abs(x.getDouble(it) - y.getDouble(it)) <= if (translation) 200.0 else .01
      }
    }
  }

  private fun hasMatrix(sample: JSONObject): Boolean =
      nodes(sample).any { it.getJSONArray("matrix").length() > 0 }

  private fun imageIdentity(sample: JSONObject): Set<Int> =
      nodes(sample).filter { it.optBoolean("visible") &&
          (it.optInt("backgroundId") != 0 || it.optInt("srcId") != 0) }
          .flatMap { listOf(it.optInt("backgroundId"), it.optInt("srcId")) }
          .filter { it != 0 }.toSet()

  private fun script(token: String, marker: String): String = """
    (function() {
      var probe = window.__nightLegacyOwnedObserver;
      if (!probe) probe = window.__nightLegacyOwnedObserver = {
        objects:new WeakMap(), sources:new Map(), nextObject:1, nextSource:1,
        documentId:Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)
      };
      function objectId(o) {
        if (!probe.objects.has(o)) probe.objects.set(o,probe.nextObject++);
        return probe.objects.get(o);
      }
      function sourceId(s) {
        if (!s || s==='none') return 0;
        if (!probe.sources.has(s)) probe.sources.set(s,probe.nextSource++);
        return probe.sources.get(s);
      }
      function classes(n) {
        return Array.from(n.classList).filter(function(c) {
          return c!=='paused' && c!=='freeze' && c!=='ss-dissolve-paused';
        }).sort().join(' ');
      }
      function matrix(s) {
        if (!s || s==='none') return [];
        var match=s.match(/^matrix(?:3d)?\((.*)\)$/);
        return match ? match[1].split(',').map(Number) : [];
      }
      function visible(n) {
        var rect=n.getBoundingClientRect(), opacity=1;
        for (var e=n; e; e=e.parentElement) {
          var s=getComputedStyle(e);
          if (s.display==='none' || s.visibility==='hidden') return false;
          opacity*=Number(s.opacity);
        }
        return opacity>.01 && rect.width>0 && rect.height>0 &&
          rect.right>0 && rect.bottom>0 && rect.left<innerWidth && rect.top<innerHeight;
      }
      var ownedToken=${JSONObject.quote(token)}, marker=${JSONObject.quote(marker)};
      var photos=window.PHOTOS||[];
      var ownedLibrary=photos.length===16 && photos.every(function(p) {
        return typeof p.url==='string' && p.url.indexOf('file://')===0 &&
          p.url.indexOf('/'+ownedToken+'/')>=0;
      });
      function ownedUrl(u) {
        return u.indexOf('/'+ownedToken+'/')>=0 ||
          (ownedLibrary && u.indexOf('https://immortal-photo.invalid/')===0) ||
          u.indexOf('file:///android_asset/')===0 || u.indexOf('data:image/')===0;
      }
      var beats=Array.from(document.querySelectorAll('#stage .ss-beat.on'));
      var scene=beats[beats.length-1], imagesOwned=true, imageCount=0;
      var elements=scene ? [scene].concat(Array.from(scene.querySelectorAll('*'))) : [];
      var rows=elements.map(function(n) {
        var style=getComputedStyle(n), bg=style.backgroundImage, src=n.currentSrc||n.getAttribute('src')||'';
        if (src) { imageCount++; if (!ownedUrl(src)) imagesOwned=false; }
        var urls=[], re=/url\(["']?(.*?)["']?\)/g, match;
        while ((match=re.exec(bg))) urls.push(match[1]);
        urls.forEach(function(u) { imageCount++; if (!ownedUrl(u)) imagesOwned=false; });
        return {id:objectId(n),class:classes(n),backgroundId:urls.length?sourceId(bg):0,
          srcId:sourceId(src),matrix:matrix(style.transform),opacity:Number(style.opacity),
          visible:visible(n),
          backgroundPosition:style.backgroundPosition,visibility:style.visibility,
          animationName:style.animationName,animationPlayState:style.animationPlayState,
          transitionProperty:style.transitionProperty};
      });
      var supported=false, animations=[];
      if (scene && typeof scene.getAnimations==='function') {
        try {
          var actual=scene.getAnimations({subtree:true}), seen=new Set(actual); supported=true;
          // Older providers can ignore subtree; query real descendants independently.
          elements.forEach(function(n) {
            if (typeof n.getAnimations==='function') n.getAnimations().forEach(function(a) {
              if (!seen.has(a)) { seen.add(a); actual.push(a); }
            });
          });
          animations=actual.map(function(a) {
            return {id:objectId(a),targetId:a.effect&&a.effect.target?objectId(a.effect.target):0,
              currentTime:typeof a.currentTime==='number'?a.currentTime:null,
              playState:a.playState,name:typeof a.animationName==='string'?a.animationName:'',
              transition:typeof a.transitionProperty==='string'?a.transitionProperty:''};
          });
        } catch (_) {}
      }
      var loading=document.getElementById('loading'), hidden=!loading ||
        loading.classList.contains('hide') || getComputedStyle(loading).display==='none' ||
        Number(getComputedStyle(loading).opacity)<.01;
      return JSON.stringify({documentId:probe.documentId,sceneId:scene?objectId(scene):0,
        sceneClass:scene?classes(scene):'',modeMarker:!!scene&&!!scene.querySelector(marker),
        ready:!!scene&&hidden&&imageCount>0&&Number(getComputedStyle(scene).opacity)>.98,
        ownedLibrary:ownedLibrary,imagesOwned:imagesOwned,deckSize:photos.length,
        paused:!!scene&&scene.classList.contains('paused'),nodes:rows,
        getAnimationsSupported:supported,animations:animations});
    })()
  """.trimIndent()
}
