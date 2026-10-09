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
import android.app.UiAutomation
import android.content.Context
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.immortal.launcher.settings.SettingsRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * command=remoteAppsNavigation; optional dream=true requires the provisioned Immortal Dream.
 * Main owns runner wiring, build and device execution. Run on the instrumentation worker.
 * Real RemoteRoutes POSTs use the existing fleet token, which never enters output. Owned local
 * images replace the source temporarily; registry values, exact prefs (including absence),
 * secure Dream selection, bridge/presence markers and the original Home tab are restored.
 * Lifecycle callbacks, native windows, Apps content and sampled chrome establish the retained
 * host and both themes. No source/photo inventory, hierarchy, screenshots or secrets are emitted.
 */
@Suppress("DEPRECATION") // Accessibility nodes are pooled on the supported Android 9/10 devices.
internal object RemoteAppsNavigationDeviceChecks {
  fun run(probe: Instrumentation, automation: UiAutomation, options: Bundle = Bundle()): Bundle =
      Probe(probe, automation, options.get("dream")?.toString()?.equals("true", ignoreCase = true) == true).run()

  private class Probe(
      private val probe: Instrumentation,
      private val automation: UiAutomation,
      private val checkDream: Boolean,
  ) {
    private val context = probe.targetContext
    private val application = context.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private val routes = RemoteRoutes(context)
    private val id = UUID.randomUUID().toString()
    private val folder = File(context.cacheDir, "remote-apps-navigation-$id")
    private val began = SystemClock.elapsedRealtime()
    private val deadline = began + 60_000L
    private var cleaning = false
    private val assertions = JSONObject()
    private var transitionDiagnostics = JSONObject()
    private var stage = "preflight"
    private var token = ""
    private var host: HomeActivity? = null
    private var windowId = -1
    private var originalTab: ApprovedLauncherTab? = null
    private var baselineCreates = 0
    private var homeCreates = 0
    private var switcherCreates = 0
    private var resumed: Activity? = null // Main-thread access only.
    private var lastPreviewTaskId: Int? = null // Main-thread diagnostic only.
    private val ownedActivities = linkedSetOf<Activity>()
    private val livePreviews = linkedSetOf<PhotoFramePreviewActivity>()
    private var startedDream: PhotoDreamService? = null
    private var registered = false
    private var configured = false
    private var dreamStops = 0 // Main-thread access only.
    private var expectedDreamStops = 0
    private val dreamReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        // Drain after ImmortalApp's global observer before restoring its suppression markers.
        handler.post { dreamStops++ }
      }
    }
    private val sourceBefore = ScreensaverConfig.load(context)
    private val presenceBefore = PresenceHub.current.copy()
    private val exitBefore = DreamPolicy.userExitAt
    private val bridgeBefore = DreamPolicy.bridgeAt
    private val handoffBefore = DreamPolicy.inStockHandoff
    private val portalBefore = DreamPolicy.portalHomeHandoff
    private val secureBefore = listOf("screensaver_enabled", "screensaver_components",
        "screensaver_default_component").associateWith {
      Settings.Secure.getString(context.contentResolver, it)
    }
    private val prefsBefore = listOf("immortal_screensaver", "immortal_settings", "remote_presets",
        "dream_bridge").associateWith { name ->
      context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
      }
    }
    private val valuesBefore = listOf("screensaver", "calendar", "immortal").associateWith {
      checkNotNull(SettingsRegistry.domain(it)).flatJson(context)
    }
    private val touched = linkedMapOf<String, Set<String>>()
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        ownedActivities += activity
        if (activity is HomeActivity) homeCreates++
        if (activity is AppSwitcherActivity) switcherCreates++
        if (activity is PhotoFramePreviewActivity) {
          livePreviews += activity
          lastPreviewTaskId = activity.taskId
        }
      }
      override fun onActivityResumed(activity: Activity) { resumed = activity }
      override fun onActivityPaused(activity: Activity) {
        if (activity is HomeActivity && originalTab == null) {
          originalTab = activity.approvedCurrentTab()
        }
        if (resumed === activity) resumed = null
      }
      override fun onActivityDestroyed(activity: Activity) {
        ownedActivities -= activity
        if (activity is PhotoFramePreviewActivity) livePreviews -= activity
        if (resumed === activity) resumed = null
      }
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun run(): Bundle {
      var passed = false
      var restored = false
      var dreamCases = 0
      val result = Bundle()
      try {
        demand("worker_thread", Looper.myLooper() != Looper.getMainLooper())
        // Refuse to mint/rotate credentials as a side effect of a test.
        token = context.getSharedPreferences("fleet_agent", Context.MODE_PRIVATE)
            .getString("token", null).orEmpty()
        demand("existing_fleet_auth", token.isNotBlank())
        demand("tour_already_seen", HelpActivity.hasSeen(context))
        demand("no_active_dream_at_setup", main { activeDream() == null })
        createFixtures()
        main {
          application.registerActivityLifecycleCallbacks(callbacks)
          registered = true
          if (checkDream) context.registerReceiver(dreamReceiver, IntentFilter(Intent.ACTION_DREAMING_STOPPED))
          configured = true
          apply("screensaver", JSONObject().put("enabled", true)
              .put("mode", ScreensaverConfig.MODE_SMART).put("intervalSec", 300)
              .put("shuffle", false).put("includeVideo", false).put("photo_motion", false)
              .put("keep_people_in_frame", false).put("presenceMode", "ALWAYS_ON")
              .put("overnightEnabled", false).put("idleSleepMin", 0)
              .put("sleepTimerEnabled", false).put("welcomeEnabled", false)
              .put("ambientDashboard", false).put("gestureWave", false)
              .put("soundscape", "off").put("showNowPlaying", false)
              .put("antiBurnIn", false).put("cacheEnabled", true))
          apply("calendar", JSONObject().put("widgetOn", false))
          ScreensaverConfig.setFolder(context, folder.path)
        }
        // Pause any existing Home first so its original tab and subsequent resume are observable.
        openPreview()
        main {
          // No tab extra yet: retain the existing host's navigation state before changing it.
          context.startActivity(Intent(context, HomeActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                  Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        stage = "initial_host"
        await { main { resumed is HomeActivity } }
        main {
          host = resumed as HomeActivity
          originalTab = originalTab ?: checkNotNull(host).approvedCurrentTab()
          baselineCreates = homeCreates
        }
        saveOwnedPresets()
        for (dark in listOf(true, false)) {
          main { apply("immortal", JSONObject().put("settingsTheme", if (dark) "dark" else "light")) }
          postKey("home")
          awaitPage(apps = false, dark = dark, establishWindow = windowId < 0)
          navigate("home_apps", "apps", dark)
          navigate("apps_home", "home", dark)
          navigate("home_preset_apps", "apps", dark, preset = true)
          navigate("preset_apps_home", "home", dark)
          navigate("home_preset_recents", "recents", dark, preset = true)
          navigate("preset_recents_home", "home", dark)
          openPreview()
          navigate("preview_apps", "apps", dark)
          navigate("preview_apps_home", "home", dark)
          openPreview()
          navigate("preview_home", "home", dark)
          openPreview()
          navigate("preview_preset_apps", "apps", dark, preset = true)
          navigate("preview_preset_apps_home", "home", dark)
          if (checkDream) {
            for ((action, preset) in listOf("apps" to false, "home" to false, "apps" to true)) {
              openDream()
              navigate("dream_${action}_${preset}", action, dark, preset)
              await { main { activeDream() == null && dreamStops >= expectedDreamStops &&
                  startedDream?.window?.decorView?.isAttachedToWindow != true } }
              demand("dream_finished_${action}_${preset}_$dark", main {
                startedDream?.window?.decorView?.isAttachedToWindow != true
              })
              startedDream = null
              dreamCases++
            }
          }
        }
        passed = true
      } catch (error: Throwable) {
        result.putString("failedStage", stage)
        result.putString("errorType", error.javaClass.simpleName)
      } finally {
        cleaning = true
        restored = cleanup()
        token = ""
      }
      return result.apply {
        putBoolean("remoteAppsNavigationCompleted", passed && restored)
        putBoolean("settingsAndFixturesRestored", restored)
        putBoolean("realDreamCovered", checkDream && dreamCases == 6)
        putInt("realDreamCases", dreamCases)
        putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
        putString("assertions", assertions.toString())
        putString("transitionDiagnostics", transitionDiagnostics.toString())
      }
    }

    private fun navigate(name: String, action: String, dark: Boolean, preset: Boolean = false) {
      stage = "$name:$dark"
      if (preset) post("/remote/preset", JSONObject().put("id", "$id-$action"))
      else postKey(action)
      awaitPage(apps = action != "home", dark = dark)
    }

    private fun awaitPage(apps: Boolean, dark: Boolean, establishWindow: Boolean = false) {
      val theme = if (dark) "Switch to day theme" else "Switch to night theme"
      val began = SystemClock.elapsedRealtime()
      var samples = 0
      var stableSince = 0L
      var pageReady = false
      var retained = false
      var settled = false
      var lifecycle = JSONObject()
      // Read the resolver without changing the Home role or dismissing any external window.
      val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      val homeLookup = runCatching {
        val pm = context.packageManager
        pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY) to
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
      }.getOrNull()
      val homeCandidates = homeLookup?.second.orEmpty()
      val resolvedHome = homeLookup?.first?.activityInfo
      // Resolver/chooser UI is not a selected Home. Match the exact candidate component.
      val defaultHomePackage = resolvedHome?.takeIf { selected ->
        homeCandidates.any { it.activityInfo.packageName == selected.packageName &&
            it.activityInfo.name == selected.name }
      }?.packageName
      val homePackages = homeCandidates.map { it.activityInfo.packageName }.toSet()
      val systemHomePackages = homeCandidates.filter {
        it.activityInfo.applicationInfo.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
      }.map { it.activityInfo.packageName }.toSet()
      val rootOwnerSamples = JSONObject()
      transitionDiagnostics = JSONObject().put("stage", stage).put("appsRequested", apps)
          .put("darkRequested", dark).put("requiredStableMs", 240)
          .put("homeLookupSucceeded", homeLookup != null)
          .put("resolvedHomePackage", resolvedHome?.packageName ?: JSONObject.NULL)
          .put("defaultHomePackage", defaultHomePackage ?: JSONObject.NULL)
          .put("defaultHomeIsTarget", defaultHomePackage == context.packageName)
          .put("rootOwnerSamples", rootOwnerSamples)
      try {
        await {
          samples++
          val state = hostState()
          lifecycle = state.second
          retained = state.first
          pageReady = false
          val sample = JSONObject().put("lifecycle", lifecycle).put("expectedWindowId", windowId)
              .put("rootAvailable", false).put("rootPackage", JSONObject.NULL)
              .put("rootOwnerKind", "unavailable").put("ownedPackage", false).put("sameWindow", false)
              .put("themeSemantics", false).put("pageContent", false).put("homeSearchChrome", false)
              .put("chromePixelsMatch", false).put("savedThemeMatches", false)
          transitionDiagnostics.put("latest", sample)
          // Layer-only page motion can leave UiAutomation cached bounds at its first sample.
          // Re-read the live tree; visual settlement must still meet every existing invariant.
          if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
          val root = automation.rootInActiveWindow
          if (root != null) try {
            val rootPackage = root.packageName?.toString()
            val ownedPackage = rootPackage == context.packageName
            // Classify from package metadata only; no foreign content enters diagnostics.
            val ownerKind = when (rootPackage) {
              context.packageName -> "owned_app"
              "com.android.permissioncontroller", "com.google.android.permissioncontroller",
              "com.android.packageinstaller", "com.google.android.packageinstaller" -> "permission_dialog"
              "com.android.systemui", "android" -> "system_ui"
              in systemHomePackages -> "system_launcher"
              else -> "other"
            }
            sample.put("rootAvailable", true).put("actualWindowId", root.windowId)
                .put("rootPackage", rootPackage ?: JSONObject.NULL).put("rootOwnerKind", ownerKind)
                .put("rootIsHomeCandidate", rootPackage in homePackages)
                .put("rootMatchesDefaultHome", rootPackage != null && rootPackage == defaultHomePackage)
                .put("ownedPackage", ownedPackage)
            root.window?.let { window ->
              try { sample.put("accessibilityWindowFocused", window.isFocused)
                  .put("accessibilityWindowActive", window.isActive)
                  .put("accessibilityWindowType", window.type) }
              finally { window.recycle() }
            }
            if (!ownedPackage) {
              val external = JSONObject().put("sample", samples)
                  .put("atMs", SystemClock.elapsedRealtime() - began)
                  .put("rootPackage", rootPackage ?: JSONObject.NULL).put("rootOwnerKind", ownerKind)
                  .put("windowId", root.windowId).put("rootIsHomeCandidate", rootPackage in homePackages)
                  .put("rootMatchesDefaultHome", rootPackage != null && rootPackage == defaultHomePackage)
                  .put("windowFocused", sample.optBoolean("accessibilityWindowFocused"))
                  .put("windowActive", sample.optBoolean("accessibilityWindowActive"))
                  .put("homeWindowFocus", lifecycle.getBoolean("homeWindowFocus"))
              if (!transitionDiagnostics.has("firstExternalRoot"))
                transitionDiagnostics.put("firstExternalRoot", external)
              transitionDiagnostics.put("lastExternalRoot", external)
            }
            // Anchor once to the retained host; a later window change must still fail.
            if (ownedPackage && lifecycle.getBoolean("resumedHost") && establishWindow && windowId < 0)
              windowId = root.windowId
            val sameWindow = root.windowId == windowId
            val themeSemantics = contains(root, theme)
            val content = if (apps) contains(root, "Your apps") &&
                contains(root, "Installed apps on this Portal.") && contains(root, "Edit favorites")
            else contains(root, "Favorite apps") && contains(root, "Customize")
            val homeSearchChrome = contains(root, "Search apps and settings")
            val savedThemeMatches = ImmortalSettings.settingsTheme(context) == if (dark) "dark" else "light"
            sample.put("expectedWindowId", windowId).put("sameWindow", sameWindow)
                .put("themeSemantics", themeSemantics).put("pageContent", content)
                .put("homeSearchChrome", homeSearchChrome).put("savedThemeMatches", savedThemeMatches)
            if (ownedPackage && lifecycle.getBoolean("resumedHost") && sameWindow &&
                themeSemantics && content && homeSearchChrome) {
              val remote = find(root, "Connect your phone")
              if (remote != null) {
                val bounds = Rect().also(remote::getBoundsInScreen)
                if (remote !== root) remote.recycle()
                val image = automation.takeScreenshot()
                if (image != null) try {
                  val x = bounds.centerX()
                  val y = bounds.top + 14
                  val inBounds = x in 0 until image.width && y in 0 until image.height
                  sample.put("chromeSampleInBounds", inBounds)
                  if (inBounds) {
                    val pixel = image.getPixel(x, y)
                    val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                    val chromeMatches = if (dark) brightness < 130 else brightness > 180
                    sample.put("chromeBrightness", brightness).put("chromePixelsMatch", chromeMatches)
                    pageReady = chromeMatches && savedThemeMatches
                  }
                } finally { image.recycle() }
              }
            }
          } finally { root.recycle() }
          val ownerKind = sample.getString("rootOwnerKind")
          rootOwnerSamples.put(ownerKind, rootOwnerSamples.optInt(ownerKind) + 1)
          val now = SystemClock.elapsedRealtime()
          if (!pageReady || !retained) stableSince = 0L
          else if (stableSince == 0L) stableSince = now
          // Resumed callbacks, window focus and preview destruction can settle after first paint.
          // Keep every existing invariant, and require consecutive successful observations.
          val stableMs = if (stableSince == 0L) 0L else now - stableSince
          settled = pageReady && retained && stableMs >= 240L
          sample.put("pageReady", pageReady).put("retainedHost", retained).put("stableMs", stableMs)
              .put("transitionSettled", settled)
          settled
        }
      } finally {
        transitionDiagnostics.put("samples", samples)
            .put("waitMs", SystemClock.elapsedRealtime() - began)
        assertions.put("$stage: apps_content_window_theme", pageReady)
            .put("$stage: retained_host", retained).put("$stage: transition_settled", settled)
        for (key in listOf("resumedHost", "sameHomeCreationCount", "noSwitcherCreated",
            "homeWindowFocus", "noLivePreviews")) {
          if (lifecycle.has(key)) assertions.put("$stage: $key", lifecycle.getBoolean(key))
        }
      }
    }

    /** Main-thread snapshot; diagnostics contain only controlled tags, identities and booleans. */
    private fun hostState(): Pair<Boolean, JSONObject> = main {
      val resumedHost = host != null && resumed === host
      val sameHomeCreationCount = homeCreates == baselineCreates
      val noSwitcherCreated = switcherCreates == 0
      val decor = host?.window?.decorView
      val homeWindowFocus = decor?.hasWindowFocus() == true
      val noLivePreviews = livePreviews.isEmpty()
      val details = JSONObject().put("resumedHost", resumedHost)
          .put("approvedCurrentTab", host?.approvedCurrentTab()?.id ?: JSONObject.NULL)
          .put("hostLifecycleState", host?.lifecycle?.currentState?.name ?: JSONObject.NULL)
          .put("sameHomeCreationCount", sameHomeCreationCount).put("homeCreates", homeCreates)
          .put("baselineHomeCreates", baselineCreates).put("noSwitcherCreated", noSwitcherCreated)
          .put("switcherCreates", switcherCreates).put("homeWindowFocus", homeWindowFocus)
          .put("homeWindowAttached", decor?.isAttachedToWindow == true)
          .put("homeTaskId", host?.taskId ?: JSONObject.NULL)
          .put("homeIsTaskRoot", host?.isTaskRoot == true)
          .put("lastPreviewTaskId", lastPreviewTaskId ?: JSONObject.NULL)
          .put("hostFinishing", host?.isFinishing == true).put("hostDestroyed", host?.isDestroyed == true)
          .put("noLivePreviews", noLivePreviews).put("livePreviews", livePreviews.size)
          .put("finishingPreviews", livePreviews.count { it.isFinishing })
          .put("attachedPreviews", livePreviews.count { it.window.decorView.isAttachedToWindow })
          .put("resumedKind", when {
            resumedHost -> "retained_home"
            resumed is HomeActivity -> "other_home"
            resumed is PhotoFramePreviewActivity -> "preview"
            resumed is AppSwitcherActivity -> "switcher"
            resumed == null -> "none"
            else -> "other"
          })
      (resumedHost && sameHomeCreationCount && noSwitcherCreated && homeWindowFocus && noLivePreviews) to details
    }

    private fun openPreview() {
      stage = "owned_preview"
      postKey("screensaver")
      await {
        main {
          val preview = resumed as? PhotoFramePreviewActivity ?: return@main false
          preview in livePreviews && ownedPhoto(preview.window.decorView)
        } && rootHas("Photo slideshow")
      }
    }

    private fun openDream() {
      stage = "owned_dream"
      demand("dream_selection_owned", ComponentName.unflattenFromString(
          Settings.Secure.getString(context.contentResolver, "screensaver_components").orEmpty()) ==
          ComponentName(context, PhotoDreamService::class.java) &&
          Settings.Secure.getString(context.contentResolver, "screensaver_enabled") == "1")
      // Privileged SystemUI requests the real Dream; no testDream/private lifecycle simulation.
      val descriptor = automation.executeShellCommand(
          "timeout 3s am start -n com.android.systemui/.Somnambulator")
      val reply = FutureTask {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
          // Fixed command output stays private and bounded; the attached Dream proves readiness.
          val bytes = ByteArray(2048)
          while (stream.read(bytes) >= 0) { /* discard */ }
        }
      }
      Thread(reply, "remote-apps-dream-request").apply { isDaemon = true }.start()
      try { reply.get(minOf(remaining(), 4_000), TimeUnit.MILLISECONDS) }
      finally { descriptor.close(); reply.cancel(true) }
      var attachedDreamObserved = false
      await {
        main {
          val dream = activeDream() ?: return@main false
          startedDream = dream
          val decor = dream.window?.decorView ?: return@main false
          if (!decor.isAttachedToWindow) return@main false
          if (!attachedDreamObserved) {
            // A request that never attaches produces no stop broadcast to drain during cleanup.
            expectedDreamStops = dreamStops + 1
            attachedDreamObserved = true
          }
          ownedPhoto(decor)
        } && rootHas("Photo slideshow")
      }
    }

    private fun activeDream(): PhotoDreamService? {
      @Suppress("UNCHECKED_CAST")
      val reference = ImmortalDreamNavigation::class.java.getDeclaredField("active")
          .apply { isAccessible = true }.get(ImmortalDreamNavigation) as WeakReference<PhotoDreamService>
      return reference.get()
    }

    private fun ownedPhoto(view: View): Boolean {
      if (view is SlideshowStage) return view.isShown && view.isAttachedToWindow &&
          view.currentPhoto?.let { it.key.startsWith("local:${folder.path}/") &&
              it.sourceKey.startsWith("local:${folder.path}/") && !it.bitmap.isRecycled } == true
      return view is ViewGroup && (0 until view.childCount).any { ownedPhoto(view.getChildAt(it)) }
    }

    private fun saveOwnedPresets() {
      val presets = RemotePresets.listJson(context)
      for (action in listOf("apps", "recents")) presets.put(JSONObject().put("id", "$id-$action")
          .put("name", "Navigation check").put("steps", JSONArray().put(
              JSONObject().put("type", "key").put("action", action))))
      post("/remote/presets", JSONObject().put("presets", presets))
    }

    private fun postKey(action: String) = post("/remote/key", JSONObject().put("action", action))

    private fun post(path: String, body: JSONObject) {
      val bytes = body.toString().toByteArray(Charsets.UTF_8)
      routes.handle(FleetHttpServer.Request("POST", path, "",
          mapOf("authorization" to "Bearer $token"), ByteArrayInputStream(bytes), bytes.size)).use {
        demand("$stage: route_accepted", it.status == 200 &&
            JSONObject(String(checkNotNull(it.jsonBytes), Charsets.UTF_8)).optBoolean("ok"))
      }
    }

    private fun createFixtures() {
      check(folder.mkdirs())
      for ((index, color) in listOf(Color.rgb(45, 180, 80), Color.rgb(185, 40, 170)).withIndex()) {
        val image = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        try {
          image.eraseColor(color)
          File(folder, "$index.jpg").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.JPEG, 90, it))
          }
        } finally { image.recycle() }
      }
    }

    private fun apply(domain: String, values: JSONObject) {
      val keys = values.keys().asSequence().toSet()
      touched[domain] = touched[domain].orEmpty() + keys
      check(SettingsRegistry.apply(context, domain, values)?.containsAll(keys) == true)
    }

    private fun cleanup(): Boolean {
      if (!registered) return runCatching { !folder.exists() || folder.deleteRecursively() }.getOrDefault(false)
      var success = true
      fun attempt(name: String, block: () -> Unit) {
        val ok = runCatching(block).isSuccess
        assertions.put(name, ok)
        success = success && ok
      }
      attempt("owned_windows_closed") {
        main {
          // Finish directly, avoiding the configured tap-dismiss app.
          startedDream?.finish()
          livePreviews.toList().forEach { it.finish() }
          ownedActivities.filter { it !is HomeActivity }.toList().forEach { it.finish() }
          host?.let { check(context.openApprovedHomeTab(originalTab ?: ApprovedLauncherTab.HOME)) }
        }
        await { main { livePreviews.isEmpty() && activeDream() == null &&
            (!checkDream || dreamStops >= expectedDreamStops) && (host == null || resumed === host) } }
        main {
          host?.let {
            check(it.approvedCurrentTab() == (originalTab ?: ApprovedLauncherTab.HOME))
          }
        }
      }
      attempt("settings_source_and_secure_selection_restored") {
        main {
          if (configured) {
            touched.forEach { (domain, keys) ->
              val restore = JSONObject()
              keys.forEach { restore.put(it, valuesBefore.getValue(domain).get(it)) }
              SettingsRegistry.apply(context, domain, restore)
            }
            restoreSource()
            prefsBefore.forEach { (name, values) -> restorePrefs(name, values) }
            SettingsGuard.afterScreensaverApply(context, touched["screensaver"].orEmpty())
          }
          secureBefore.forEach { (key, value) ->
            if (Settings.Secure.getString(context.contentResolver, key) != value)
              check(Settings.Secure.putString(context.contentResolver, key, value))
          }
          check(ScreensaverConfig.load(context) == sourceBefore)
          prefsBefore.forEach { (name, before) ->
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).all == before)
          }
          check(ImmortalSettings.settingsTheme(context) == valuesBefore.getValue("immortal")
              .getString("settingsTheme"))
        }
      }
      attempt("navigation_and_runtime_markers_restored") {
        main {
          // Only Activities created after this probe registered its callbacks are owned.
          ownedActivities.filterIsInstance<HomeActivity>().toList().forEach { it.finish() }
        }
        await { main { ownedActivities.none { it is HomeActivity } } }
        main {
          DreamPolicy.userExitAt = exitBefore
          DreamPolicy.bridgeAt = bridgeBefore
          DreamPolicy.inStockHandoff = handoffBefore
          DreamPolicy.portalHomeHandoff = portalBefore
          if (PresenceHub.current != presenceBefore) {
            PresenceHub::class.java.getDeclaredField("current").apply { isAccessible = true }
                .set(PresenceHub, presenceBefore)
            @Suppress("UNCHECKED_CAST")
            val listeners = PresenceHub::class.java.getDeclaredField("listeners")
                .apply { isAccessible = true }.get(PresenceHub) as List<PresenceHub.Listener>
            listeners.forEach { it.onPresenceChanged(presenceBefore) }
            PresenceHub::class.java.getDeclaredMethod("publish", Context::class.java, PresenceState::class.java)
                .apply { isAccessible = true }.invoke(PresenceHub, context, presenceBefore)
          }
          application.unregisterActivityLifecycleCallbacks(callbacks)
          if (checkDream) context.unregisterReceiver(dreamReceiver)
        }
      }
      attempt("owned_fixtures_removed") {
        check(main { livePreviews.isEmpty() && startedDream?.window?.decorView?.isAttachedToWindow != true })
        check(!folder.exists() || folder.deleteRecursively())
      }
      return success
    }

    private fun restoreSource() {
      val s = sourceBefore
      when (s.source) {
        ScreensaverConfig.SOURCE_FOLDER -> ScreensaverConfig.setFolder(context, s.folderPath.orEmpty())
        ScreensaverConfig.SOURCE_URL -> ScreensaverConfig.setAlbumUrl(context, s.albumUrl.orEmpty())
        ScreensaverConfig.SOURCE_IMMICH -> ScreensaverConfig.setImmich(context, s.immichUrl.orEmpty(), s.immichKey.orEmpty())
        ScreensaverConfig.SOURCE_SMB -> ScreensaverConfig.setSmb(context, s.smbHost.orEmpty(), s.smbShare.orEmpty(),
            s.smbPath.orEmpty(), s.smbUser.orEmpty(), s.smbPass.orEmpty())
        ScreensaverConfig.SOURCE_DAV -> ScreensaverConfig.setDav(context, s.davUrl.orEmpty(), s.davUser.orEmpty(), s.davPass.orEmpty())
        ScreensaverConfig.SOURCE_WEBURL -> ScreensaverConfig.setWebUrl(context, s.webUrl.orEmpty())
        else -> ScreensaverConfig.useDefault(context)
      }
    }

    /** Test-only restoration includes dormant fields and originally absent keys. */
    private fun restorePrefs(name: String, values: Map<String, *>) {
      val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
      val editor = prefs.edit()
      (prefs.all.keys + values.keys).forEach { key ->
        when (val value = values[key]) {
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
        }
      }
      check(editor.commit() && prefs.all == values)
    }

    private fun rootHas(label: String): Boolean {
      val root = automation.rootInActiveWindow ?: return false
      return try { root.packageName?.toString() == context.packageName && contains(root, label) }
      finally { root.recycle() }
    }
    private fun contains(node: AccessibilityNodeInfo, label: String): Boolean {
      val found = find(node, label) ?: return false
      if (found !== node) found.recycle()
      return true
    }
    private fun find(node: AccessibilityNodeInfo, label: String, depth: Int = 0): AccessibilityNodeInfo? {
      if (depth > 60) return null
      // Older Portal APIs lack UiAutomation.clearCache; refresh each queried node instead.
      if (Build.VERSION.SDK_INT < 33 && !node.refresh()) return null
      if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label))
        return node
      for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        val found = find(child, label, depth + 1)
        if (found != null) {
          if (found !== child) child.recycle()
          return found
        }
        child.recycle()
      }
      return null
    }
    private fun remaining(): Long = ((if (cleaning) deadline + 10_000 else deadline) -
        SystemClock.elapsedRealtime()).also { check(it > 0) { "Probe deadline" } }
    private fun <T> main(block: () -> T): T {
      val task = FutureTask(block)
      check(handler.post(task))
      return try { task.get(minOf(remaining(), 2_000), TimeUnit.MILLISECONDS) }
      catch (error: Throwable) { task.cancel(false); throw error }
    }
    private fun await(condition: () -> Boolean) {
      val until = SystemClock.elapsedRealtime() + minOf(remaining(), 8_000)
      do {
        if (condition()) return
        SystemClock.sleep(80)
      } while (SystemClock.elapsedRealtime() < until)
      error("Navigation condition timed out")
    }
    private fun demand(name: String, value: Boolean) {
      assertions.put(name, value)
      check(value) { name }
    }
  }
}
