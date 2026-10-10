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
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import com.immortal.launcher.settings.SettingsRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * command=retainedPreview, test APK only. Three owned, generated Android PNGs;
 * no household media, hierarchy, screenshot, URL, preference value or exception is exported.
 *
 * Two real opaque AppSwitcher/Back cycles per renderer, with user pause on cycle two.
 * Click the live QuickBar control when attached; otherwise use its exact NEW_TASK intent.
 * User pause uses the controller's private setter on main. The Framed assertion is
 * synchronized by the real onStop; active-window legacy pause-control UI is out of scope.
 */
internal object RetainedPreviewDeviceChecks {
  fun run(instrumentation: Instrumentation): Bundle = Probe(instrumentation).run()

  private const val DWELL_MS = 5_000L
  private const val COVER_MS = DWELL_MS + 1_200L
  private const val WAIT_MS = 12_000L
  private const val LIMIT_MS = 180_000L

  private class Failure : RuntimeException()
  private class Probe(
      private val instrumentation: Instrumentation,
  ) {
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as Application
    private val deadline = SystemClock.elapsedRealtime() + LIMIT_MS
    private val folder = File(context.cacheDir, "retained-preview-${UUID.randomUUID()}")
    private val photos = File(folder, "photos")
    private val assertions = JSONObject()
    private val touched = linkedMapOf<String, MutableSet<String>>()
    private val prefsBefore = linkedMapOf<String, Map<String, *>>()
    private var registryBefore = emptyMap<String, JSONObject>()
    private var secureBefore = emptyMap<String, String?>()
    private var sourceBefore: ScreensaverConfig.Settings? = null
    private var presenceBefore: PresenceState? = null
    private val created = ArrayList<Activity>()
    private val stopped = HashSet<Activity>()
    private var host: PhotoFramePreviewActivity? = null
    private var cover: AppSwitcherActivity? = null
    private var callbacksRegistered = false
    private var configured = false
    private var failed = false
    private var phase = "setup"
    private var cycles = 0
    private var quickBarClicks = 0
    private var intentFallbacks = 0
    private var queueDeliveries = 0
    private var lateWrappers = 0
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity is PhotoFramePreviewActivity || activity is AppSwitcherActivity) created += activity
      }
      override fun onActivityStarted(activity: Activity) { stopped.remove(activity) }
      override fun onActivityStopped(activity: Activity) { stopped.add(activity) }
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    }

    fun run(): Bundle {
      try {
        snapshot()
        check(photos.mkdirs())
        createFixtures()
        main { application.registerActivityLifecycleCallbacks(lifecycle) }
        callbacksRegistered = true
        apply("quickbar", JSONObject().put("enabled", true).put("alwaysShow", true))
        for (mode in listOf("smartFrame", "framed")) {
          phase = mode
          configure(mode)
          exercise(mode)
        }
        demand("all_four_opaque_cover_back_cycles", cycles == 4)
      } catch (_: Throwable) {
        failed = true
        assertions.put("${phase}_completed", false)
      } finally {
        cleanup()
      }
      return Bundle().apply {
        putBoolean("retainedPreviewCompleted", !failed)
        putString("assertions", assertions.toString())
        putInt("completedCoverCycles", cycles)
        putInt("quickBarClickCount", quickBarClicks)
        putInt("sameIntentFallbackCount", intentFallbacks)
        putInt("queueDeliveryCount", queueDeliveries)
        putInt("lateWrapperDispatchCount", lateWrappers)
        putInt("generatedPhotoCount", 3)
      }
    }

    private fun snapshot() = main {
      registryBefore = listOf("screensaver", "calendar", "quickbar").associateWith {
        checkNotNull(SettingsRegistry.domain(it)).flatJson(context)
      }
      for (name in listOf("immortal_screensaver", "quick_bar")) {
        prefsBefore[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
      }
      sourceBefore = ScreensaverConfig.load(context)
      presenceBefore = PresenceHub.current.copy()
      secureBefore = listOf("screensaver_enabled", "screensaver_components",
          "screensaver_default_component", "enabled_accessibility_services",
          "accessibility_enabled").associateWith { Settings.Secure.getString(context.contentResolver, it) }
    }

    private fun createFixtures() {
      for (index in 0..2) {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
          bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
          File(photos, "$index.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
          }
        } finally { bitmap.recycle() }
      }

    }

    private fun apply(domain: String, body: JSONObject) {
      configured = true
      val keys = body.keys().asSequence().toSet()
      touched.getOrPut(domain) { linkedSetOf() }.addAll(keys)
      demand("${domain}_registry_apply", main { SettingsRegistry.apply(context, domain, body) == keys })
    }

    private fun configure(mode: String) {
      apply("screensaver", JSONObject().put("enabled", true)
          .put("mode", mode).put("intervalSec", 5)
          .put("shuffle", false).put("includeVideo", false).put("photo_motion", true)
          .put("fit", ScreensaverConfig.FIT_FILL).put("keep_people_in_frame", false)
          .put("presenceMode", "ALWAYS_ON").put("overnightEnabled", false)
          .put("idleSleepMin", 0).put("sleepTimerEnabled", false).put("welcomeEnabled", false)
          .put("ambientDashboard", false).put("gestureWave", false).put("soundscape", "off")
          .put("showNowPlaying", false).put("antiBurnIn", false).put("cacheEnabled", true))
      apply("calendar", JSONObject().put("widgetOn", false))
      main { ScreensaverConfig.setFolder(context, photos.path) }
    }

    private fun exercise(mode: String) {
      val activity = instrumentation.startActivitySync(
          Intent(context, PhotoFramePreviewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PhotoFramePreviewActivity.EXTRA_SHOW_WELCOME, false)
            putExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, false)
            putExtra("face_json", """{"clock":{"mode":"none"},"weather":{"enabled":false},
              "nowPlaying":{"enabled":false},"battery":{"enabled":false},"caption":{"enabled":false}}""")
          }) as PhotoFramePreviewActivity
      // singleTask must not grant ownership of an already-existing household preview.
      demand("${mode}_new_owned_preview", main { created.any { it === activity } })
      host = activity
      val controller = main { field(activity, "frame") as PhotoFrameController }
      val queue = main { field(controller, "ui") as PausableFrameCallbacks }
      await("${mode}_active_host") { main {
        activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
            field(controller, "hostActive") == true && queue.isActive
      } }
      val stage = if (mode == "smartFrame") main {
        field(controller, "nativeStage") as SlideshowStage
      } else null
      val web = if (stage == null) {
        await("${mode}_webview_ready") { main { field(controller, "webView") is WebView } }
        main { field(controller, "webView") as WebView }
      } else null
      if (stage != null) {
        await("${mode}_generated_photo_ready") { main {
          stage.currentPhoto?.let { ownedPhoto(it) } == true && field(controller, "nativeStarting") == false
        } }
      } else {
        val engine = checkNotNull(web)
        await("${mode}_generated_engine_ready") {
          js(engine, """
            !!window.immortalSlideshow && window.PHOTOS.length===3 &&
            window.PHOTOS.every(p=>p.url.indexOf(${JSONObject.quote("file://${photos.path}/")})===0) &&
            !!document.querySelector('.ss-art.on .ss-print') &&
            document.getElementById('loading').classList.contains('hide')
          """) == "true"
        }
        js(engine, """
          (function(){
            const nodes=Array.from(document.querySelectorAll('.ss-print'));
            window.retainedProbe={document:document,stage:document.getElementById('stage'),
              nodes:nodes,values:nodes.map(n=>n.style.backgroundImage),changes:0};
            retainedProbe.observer=new MutationObserver(function(){
              nodes.forEach(function(n,i){const v=n.style.backgroundImage;
                if(v!==retainedProbe.values[i]){retainedProbe.changes++;retainedProbe.values[i]=v;}});
            });
            nodes.forEach(n=>retainedProbe.observer.observe(n,{attributes:true,attributeFilter:['style']}));
            return true;
          })()
        """)
      }
      var expectedEngineChanges: Int? = null
      for (cycle in 1..2) {
        val label = "${mode}_cycle_$cycle"
        phase = label
        if (cycle == 2) {
          setPaused(controller, true)
          demand("${label}_user_pause_set", main { field(controller, "paused") == true })
        }
        val oldWrappers = main { wrappers(queue) }
        openCover(label, activity)
        await("${label}_opaque_cover_stops_preview") { main {
          cover?.lifecycle?.currentState == Lifecycle.State.RESUMED &&
              stopped.contains(activity) && field(activity, "hostStarted") == false &&
              activity.lifecycle.currentState == Lifecycle.State.CREATED &&
              field(controller, "hostActive") == false && !queue.isActive &&
              !activity.isDestroyed && field(controller, "stopped") == false &&
              (stage == null || field(stage, "hostActive") == false)
        } }
        if (mode == "framed") await("${label}_engine_host_paused") { enginePaused(checkNotNull(web)) }
        val bitmap = main { stage?.currentPhoto?.bitmap }
        val generation = main { field(controller, "gen") as Int }
        val engineChanges = if (mode == "framed") js(checkNotNull(web), "retainedProbe.changes").toInt() else 0
        if (mode == "framed") {
          expectedEngineChanges?.let { expected ->
            demand("${mode}_cycle_1_single_engine_advance", engineChanges == expected)
          }
          js(checkNotNull(web), "retainedProbe.held=document.querySelector('.ss-art.on');true")
        }
        val delivered = AtomicInteger()
        main {
          val pending = queue.pendingCount
          check(queue.postDelayed(Runnable { delivered.incrementAndGet() }, 250))
          demand("${label}_inactive_post_retained", queue.pendingCount == pending + 1)
          controller.setHostActive(false)
          demand("${label}_repeated_inactive_no_duplicate", queue.pendingCount == pending + 1)
          oldWrappers.forEach { it.run(); lateWrappers++ }
        }
        hold(COVER_MS)
        demand("${label}_covered_queue_inert", delivered.get() == 0)
        demand("${label}_covered_identities_retained", main {
          field(activity, "frame") === controller && !activity.isDestroyed &&
              field(controller, "gen") == generation &&
              (stage == null || (field(controller, "nativeStage") === stage &&
                  stage.currentPhoto?.bitmap === bitmap && bitmap?.isRecycled == false)) &&
              (web == null || field(controller, "webView") === web)
        })
        if (mode == "framed") demand("${label}_no_engine_advance_beyond_dwell",
            js(checkNotNull(web), "retainedProbe.changes").toInt() == engineChanges &&
                js(web, "retainedProbe.held===document.querySelector('.ss-art.on')") == "true")
        returnByBack(label, activity)
        demand("${label}_same_renderers_after_back", main {
          field(activity, "frame") === controller &&
              (stage == null || (field(controller, "nativeStage") === stage &&
                  stage.currentPhoto?.bitmap === bitmap && bitmap?.isRecycled == false)) &&
              (web == null || field(controller, "webView") === web)
        })
        main { repeat(3) { controller.setHostActive(true) } }
        await("${label}_one_retained_callback_resumes") { delivered.get() == 1 }
        hold(400)
        demand("${label}_no_duplicate_callback_delivery", delivered.get() == 1)
        queueDeliveries += delivered.get()
        demand("${label}_user_pause_preserved", main { field(controller, "paused") == (cycle == 2) })
        if (web != null) demand("${label}_engine_document_nodes_retained", js(web,
            "retainedProbe.document===document && retainedProbe.stage===document.getElementById('stage')" +
                " && retainedProbe.nodes.every(n=>n.isConnected)") == "true")
        if (cycle == 1) {
          await("${label}_playback_resumes_once") {
            if (stage != null) main { (field(controller, "gen") as Int) > generation &&
                stage.currentPhoto?.bitmap !== bitmap && stage.currentPhoto?.let { ownedPhoto(it) } == true }
            else js(checkNotNull(web), "retainedProbe.changes").toInt() > engineChanges
          }
          setPaused(controller, true)
          if (stage != null) demand("${label}_single_native_advance",
              main { field(controller, "gen") == generation + 1 })
          else {
            // User choice is retained now; the next real onStop is the synchronization
            // boundary for the engine snapshot. This tests pause across cover/Back, not
            // the legacy viewer's active-window pause-control UI.
            expectedEngineChanges = engineChanges + 1
          }
        } else if (cycle == 2) {
          hold(COVER_MS)
          demand("${label}_paused_after_back_beyond_dwell",
              if (stage != null) main { field(controller, "gen") == generation &&
                  stage.currentPhoto?.bitmap === bitmap && field(stage, "paused") == true }
              else enginePaused(checkNotNull(web)) &&
                  js(web, "retainedProbe.changes").toInt() == engineChanges)
        }
        cycles++
      }
      stopChecks(mode, activity, controller, queue)
    }

    private fun openCover(label: String, activity: Activity) {
      val monitor = instrumentation.addMonitor(AppSwitcherActivity::class.java.name, null, false)
      try {
        val clicked = main {
          val button = field(QuickBar, "switcher") as? View
          if (button?.isAttachedToWindow == true && button.isShown) button.performClick() else false
        }
        if (clicked) quickBarClicks++ else {
          intentFallbacks++
          main { activity.startActivity(Intent(context, AppSwitcherActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        demand("${label}_quickbar_clicked_or_exact_new_task_fallback", true)
        assertions.put("${label}_live_quickbar_click", clicked)
        assertions.put("${label}_same_intent_fallback_scope", !clicked)
        await("${label}_owned_appswitcher_created") { main {
          val candidate = monitor.lastActivity as? AppSwitcherActivity
          if (candidate != null && created.any { it === candidate }) cover = candidate
          cover != null
        } }
      } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun returnByBack(label: String, activity: PhotoFramePreviewActivity) {
      val ownedCover = checkNotNull(cover)
      instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
      await("${label}_back_returns_retained_preview") { main {
        ownedCover.isDestroyed && activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
            field(activity, "hostStarted") == true &&
            field(field(activity, "frame")!!, "hostActive") == true
      } }
      cover = null
    }

    private fun stopChecks(mode: String, activity: Activity,
        controller: PhotoFrameController, queue: PausableFrameCallbacks) {
      val count = AtomicInteger()
      val stale = main {
        check(queue.postDelayed(Runnable { count.incrementAndGet() }, 60_000))
        wrappers(queue).also { activity.finish() }
      }
      await("${mode}_owned_preview_destroyed") { main { activity.isDestroyed } }
      host = null
      main {
        stale.forEach { it.run(); lateWrappers++ }
        controller.setHostActive(true)
        queue.setActive(true)
        demand("${mode}_stop_closes_queue", field(controller, "stopped") == true &&
            !queue.isActive && queue.pendingCount == 0 && !queue.post(Runnable { count.incrementAndGet() }))
        demand("${mode}_stop_releases_renderers", field(controller, "webView") == null &&
            (field(controller, "nativeStage") as SlideshowStage).currentPhoto == null)
      }
      hold(350)
      demand("${mode}_late_callbacks_after_stop_inert", count.get() == 0)
    }

    private fun setPaused(controller: PhotoFrameController, value: Boolean) = main {
      PhotoFrameController::class.java.getDeclaredMethod("setPaused", Boolean::class.javaPrimitiveType)
          .apply { isAccessible = true }.invoke(controller, value)
    }

    private fun ownedPhoto(photo: SlideshowPhoto): Boolean =
        photo.sourceKey.startsWith("local:${photos.absolutePath}${File.separator}") &&
            !photo.bitmap.isRecycled

    private fun enginePaused(web: WebView): Boolean = js(web, """
      (function(){const b=Array.from(document.querySelectorAll('#stage .ss-beat'));
        return b.length>0 && b.every(n=>n.classList.contains('paused'));})()
    """) == "true"

    private fun wrappers(queue: PausableFrameCallbacks): List<Runnable> {
      val entries = field(queue, "entries") as Iterable<*>
      return entries.mapNotNull { it?.let { entry -> field(entry, "wrapper") as? Runnable } }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun <T> main(action: () -> T): T {
      var value: T? = null
      instrumentation.runOnMainSync { value = action() }
      @Suppress("UNCHECKED_CAST")
      return value as T
    }

    private fun js(web: WebView, script: String): String {
      bounded()
      val done = CountDownLatch(1)
      var value = "null"
      main { web.evaluateJavascript(script.trimIndent()) { value = it; done.countDown() } }
      demand("${phase}_javascript_callback", done.await(4, TimeUnit.SECONDS))
      return value
    }

    private fun await(label: String, condition: () -> Boolean) {
      val until = minOf(deadline, SystemClock.elapsedRealtime() + WAIT_MS)
      while (SystemClock.elapsedRealtime() < until) {
        bounded()
        if (condition()) { demand(label, true); return }
        SystemClock.sleep(80)
      }
      demand(label, false)
    }

    private fun bounded() {
      if (SystemClock.elapsedRealtime() >= deadline || Thread.currentThread().isInterrupted)
        demand("bounded_probe_budget", false)
    }

    private fun hold(millis: Long) {
      val until = SystemClock.elapsedRealtime() + millis
      while (SystemClock.elapsedRealtime() < until) {
        bounded()
        SystemClock.sleep(minOf(80, until - SystemClock.elapsedRealtime()).coerceAtLeast(1))
      }
    }

    private fun demand(label: String, valid: Boolean) {
      assertions.put(label, valid)
      if (!valid) throw Failure()
    }

    private fun cleanup() {
      fun attempt(label: String, action: () -> Unit) {
        try { action(); assertions.put(label, true) }
        catch (_: Throwable) { failed = true; assertions.put(label, false) }
      }
      var ownedHostsDestroyed = false
      attempt("only_owned_hosts_finished") {
        val owned = main { created.toList() }
        main { owned.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() } }
        // Cleanup has its own bounded wait even if the run budget is exhausted.
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        while (!main { owned.all { it.isDestroyed } } && SystemClock.elapsedRealtime() < until)
          SystemClock.sleep(80)
        check(main { owned.all { it.isDestroyed } })
        host = null; cover = null
        ownedHostsDestroyed = true
      }
      attempt("registry_source_and_exact_preferences_restored") {
        if (configured) main {
          touched.forEach { (domain, keys) ->
            val original = registryBefore.getValue(domain)
            val body = JSONObject()
            keys.forEach { if (original.has(it)) body.put(it, original.get(it)) }
            SettingsRegistry.apply(context, domain, body)
          }
          restoreSource(checkNotNull(sourceBefore))
          prefsBefore.forEach { (name, before) ->
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val now = preferences.all
            val editor = preferences.edit()
            (before.keys + now.keys).filter {
              before[it] != now[it] || before.containsKey(it) != now.containsKey(it)
            }.forEach { putPreference(editor, it, before[it]) }
            check(editor.commit())
            check(preferences.all == before)
          }
          SettingsGuard.afterScreensaverApply(context, touched["screensaver"].orEmpty())
          QuickBar.applyConfig()
          touched.forEach { (domain, keys) ->
            val current = checkNotNull(SettingsRegistry.domain(domain)).flatJson(context)
            keys.forEach { check(current.opt(it) == registryBefore.getValue(domain).opt(it)) }
          }
          check(ScreensaverConfig.load(context) == sourceBefore)
        }
      }
      attempt("secure_settings_restored") {
        if (configured) main {
          secureBefore.forEach { (key, value) ->
            if (Settings.Secure.getString(context.contentResolver, key) != value)
              check(Settings.Secure.putString(context.contentResolver, key, value))
            check(Settings.Secure.getString(context.contentResolver, key) == value)
          }
        }
      }
      attempt("presence_snapshot_restored") {
        presenceBefore?.let { original -> main {
          if (PresenceHub.current != original) {
            PresenceHub::class.java.getDeclaredField("current").apply { isAccessible = true }
                .set(PresenceHub, original)
            @Suppress("UNCHECKED_CAST")
            val listeners = field(PresenceHub, "listeners") as List<PresenceHub.Listener>
            listeners.toList().forEach { it.onPresenceChanged(original) }
            PresenceHub::class.java.getDeclaredMethod("publish", Context::class.java, PresenceState::class.java)
                .apply { isAccessible = true }.invoke(PresenceHub, context, original)
          }
          check(PresenceHub.current == original)
        } }
      }
      attempt("lifecycle_observer_removed") {
        if (callbacksRegistered) main { application.unregisterActivityLifecycleCallbacks(lifecycle) }
      }
      attempt("generated_fixture_folder_removed") {
        check(ownedHostsDestroyed && host == null)
        if (folder.exists()) check(folder.deleteRecursively())
      }
    }

    private fun restoreSource(original: ScreensaverConfig.Settings) {
      when (original.source) {
        ScreensaverConfig.SOURCE_FOLDER -> ScreensaverConfig.setFolder(context, original.folderPath.orEmpty())
        ScreensaverConfig.SOURCE_URL -> ScreensaverConfig.setAlbumUrl(context, original.albumUrl.orEmpty())
        ScreensaverConfig.SOURCE_IMMICH -> ScreensaverConfig.setImmich(context,
            original.immichUrl.orEmpty(), original.immichKey.orEmpty())
        ScreensaverConfig.SOURCE_SMB -> ScreensaverConfig.setSmb(context,
            original.smbHost.orEmpty(), original.smbShare.orEmpty(), original.smbPath.orEmpty(),
            original.smbUser.orEmpty(), original.smbPass.orEmpty())
        ScreensaverConfig.SOURCE_DAV -> ScreensaverConfig.setDav(context,
            original.davUrl.orEmpty(), original.davUser.orEmpty(), original.davPass.orEmpty())
        ScreensaverConfig.SOURCE_WEBURL -> ScreensaverConfig.setWebUrl(context, original.webUrl.orEmpty())
        else -> ScreensaverConfig.useDefault(context)
      }
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
      when (value) {
        null -> editor.remove(key)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is String -> editor.putString(key, value)
        is Set<*> -> {
          @Suppress("UNCHECKED_CAST")
          editor.putStringSet(key, value as Set<String>)
        }
        else -> error("Unsupported preference type")
      }
    }
  }
}
