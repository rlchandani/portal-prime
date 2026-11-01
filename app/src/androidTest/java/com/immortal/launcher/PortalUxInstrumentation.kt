/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A test-APK-only UI probe. It snapshots accessibility directly, without requiring clocks to
 * stop sending accessibility events. Raw UI and images stay in the app's private test cache.
 * Console output includes only requested assertion labels, booleans and private file paths.
 */
class PortalUxInstrumentation : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        // Explicitly gated real PCM connection probe; never mutates MA playback or queue.
        if (options.getString("command") == "musicPcmContinuity") {
            val receiver = MusicPcmContinuityDeviceChecks.run(this, options)
            finish(if (receiver.getBoolean("musicPcmContinuityPassed")) 0 else 1, receiver)
            return
        }
        // Signed receiver checks: non-starting Query/client probes, or explicitly opted-in local native fixture.
        // None enter generic private diagnostics or read credentials.
        if (options.getString("command") == "musicReceiverQuery") {
            val receiver = MusicReceiverDeviceChecks.run(this, options)
            finish(if (receiver.getBoolean("musicReceiverQueryPassed")) 0 else 1, receiver)
            return
        }
        // Synthetic native artwork only: no UiAutomation or generic private diagnostics.
        if (options.getString("command") == "musicArtwork") {
            val artwork = try {
                MusicArtworkDeviceChecks.run(this)
            } catch (_: Throwable) {
                Bundle().apply {
                    putBoolean("musicArtworkCompleted", false)
                    putBoolean("isolatedFailure", true)
                }
            }
            finish(if (artwork.getBoolean("musicArtworkCompleted")) 0 else 1, artwork)
            return
        }
        val result = Bundle()
        // These gated list fixtures never acquire UiAutomation or enter private diagnostics.
        if (options.getString("command") == "barWatchServiceList") {
            val barWatch = BarWatchServiceListDeviceChecks.run(targetContext)
            finish(if (barWatch.getBoolean("barWatchServiceListPassed")) 0 else 1, barWatch)
            return
        }
        // Public elapsed counters and read-only clock samples never enter private diagnostics.
        if (options.getString("command") == "sleepElapsedTiming") {
            val timing = SleepElapsedTimingDeviceChecks.run()
            finish(if (timing.getBoolean("sleepElapsedTimingPassed")) 0 else 1, timing)
            return
        }
        // Public snooze counter fixtures never acquire UiAutomation or enter private diagnostics.
        if (options.getString("command") == "alarmSnooze") {
            val snooze = AlarmSnoozeDeviceChecks.run()
            finish(if (snooze.getBoolean("alarmSnoozePassed")) 0 else 1, snooze)
            return
        }
        // Public clock-change fixtures never acquire UiAutomation or enter private diagnostics.
        if (options.getString("command") == "clockChangeAlarms") {
            val clock = ClockChangeAlarmsDeviceChecks.run(targetContext)
            finish(if (clock.getBoolean("clockChangeAlarmsPassed")) 0 else 1, clock)
            return
        }
        // Public alarm edit fixtures use RAM preferences and an injected counter scheduler.
        if (options.getString("command") == "toolsAlarmEdits") {
            val edits = ToolsAlarmEditsDeviceChecks.run()
            finish(if (edits.getBoolean("toolsAlarmEditsPassed")) 0 else 1, edits)
            return
        }
        // Public currency fixtures use only in-memory preferences, before UiAutomation.
        if (options.getString("command") == "currencyIntegrity") {
            val currency = CurrencyRateIntegrityDeviceChecks.run()
            finish(if (currency.getBoolean("currencyIntegrityPassed")) 0 else 1, currency)
            return
        }
        // Public owner contracts and real silent test-APK TTS clients need no UiAutomation.
        if (options.getString("command") == "oneShotSpeech") {
            val speech = OneShotSpeechDeviceChecks.run(this)
            finish(if (speech.getBoolean("oneShotSpeechCompleted")) 0 else 1, speech)
            return
        }
        // Public settings and counter-only readiness checks never acquire UiAutomation.
        if (options.getString("command") == "automaticTimeSpeech") {
            val speech = AutomaticTimeSpeechDeviceChecks.run()
            finish(if (speech.getBoolean("automaticTimeSpeechCompleted")) 0 else 1, speech)
            return
        }
        // Public solar fixtures and counter-only work checks never acquire UiAutomation.
        if (options.getString("command") == "goldenHour") {
            val goldenHour = GoldenHourDeviceChecks.run()
            finish(if (goldenHour.getBoolean("goldenHourCompleted")) 0 else 1, goldenHour)
            return
        }
        // Pure public parser checks need no UiAutomation session. UI paths below retain
        // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES; this branch collects no UI/private files.
        if (options.getString("command") == "weatherTime") {
            val weather = WeatherTimeDeviceChecks.run()
            finish(if (weather.getBoolean("weatherTimeCompleted")) 0 else 1, weather)
            return
        }
        // This public fixture path never enters the generic private-cache diagnostics catch.
        if (options.getString("command") == "issPassUi") {
            val iss = IssPassUiDeviceChecks.run(this, options)
            finish(if (iss.getBoolean("issPassUiCompleted")) 0 else 1, iss)
            return
        }
        if (options.getString("command") == "todayCalendarUi") {
            val calendar = TodayCalendarUiDeviceChecks.run(this)
            finish(if (calendar.getBoolean("todayCalendarUiCompleted")) 0 else 1, calendar)
            return
        }
        // Collect all public native assertions before returning a failure; no private diagnostics.
        if (options.getString("command") == "speedResults") {
            val speed = SpeedResultsUiDeviceChecks.run(this)
            finish(if (speed.getBoolean("speedResultsPassed")) 0 else 1, speed)
            return
        }
        try {
            if (options.getString("command") == "retainedPreview") {
                val retained = RetainedPreviewDeviceChecks.run(this)
                finish(if (retained.getBoolean("retainedPreviewCompleted")) 0 else 1, retained)
                return
            }
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (options.getString("command") in setOf("packageInventorySetup", "packageInventoryCleanup")) {
                result.putAll(PackageInventoryDeviceChecks.run(this,
                    options.getString("command") == "packageInventoryCleanup"))
                finish(if (result.getBoolean("packageInventoryCompleted")) 0 else 1, result)
                return
            }
            if (options.getString("command") == "pinnedApp") {
                result.putAll(PinnedAppDeviceChecks.run(this))
                finish(if (result.getBoolean("pinnedAppCompleted")) 0 else 1, result)
                return
            }
            if (options.getString("command") == "widgetListening") {
                result.putAll(WidgetListeningDeviceChecks.run(this))
                finish(if (result.getBoolean("widgetListeningCompleted")) 0 else 1, result)
                return
            }
            if (options.getString("command") == "tabActivation") {
                result.putAll(TabActivationDeviceChecks.run(this))
                finish(if (result.getBoolean("tabActivationCompleted")) 0 else 1, result)
                return
            }
            if (options.getString("command") == "tabPlacement") {
                result.putAll(TabPlacementDeviceChecks.run(this))
                finish(if (result.getBoolean("tabPlacementCompleted")) 0 else 1, result)
                return
            }
            if (options.getString("command") == "homeDashboardSetup") {
                val url = requireNotNull(options.getString("url"))
                val applied = com.immortal.launcher.settings.SettingsDomains.homeDashboard.apply(
                    targetContext, JSONObject().put("url", url))
                check(applied == setOf("url"))
                // The instrumentation process exits immediately. An empty synchronous commit
                // flushes the registry's pending apply without writing settings around it.
                check(targetContext.getSharedPreferences("immortal_home_dashboard",
                    android.content.Context.MODE_PRIVATE).edit().commit())
                finish(0, Bundle().apply {
                    putBoolean("homeDashboardConfigured", HomeDashboardConfig.configured(targetContext))
                })
                return
            }
            if (options.getString("command") == "homeDashboardOpen") {
                finish(0, Bundle().apply {
                    putBoolean("dashboardOpened", HomeDashboardActivity.openDashboard(targetContext))
                })
                return
            }
            if (options.getString("command") == "homeDashboardSmoke") {
                val dashboard = HomeDashboardDeviceChecks.run(this, automation)
                finish(if (dashboard.getBoolean("homeDashboardCompleted")) 0 else 1, dashboard)
                return
            }
            if (options.getString("command") == "dreamRecovery") {
                val recovery = DreamRecoveryDeviceChecks.run(this, automation)
                finish(if (recovery.getBoolean("dreamRecoveryCompleted")) 0 else 1, recovery)
                return
            }
            if (options.getString("command") == "slideshowReport") {
                finish(0, SlideshowValidationReport.read(targetContext,
                    requireNotNull(options.getString("runId"))))
                return
            }
            if (options.getString("command") == "slideshowCleanup") {
                finish(0, SlideshowValidationReport.cleanupFixtures(targetContext))
                return
            }
            if (options.getString("command") == "slideshowEnableSmart") {
                val applied = com.immortal.launcher.settings.SettingsRegistry.apply(targetContext,
                    "screensaver", JSONObject().put("mode", ScreensaverConfig.MODE_SMART))
                check(applied == setOf("mode"))
                finish(0, Bundle().apply {
                    putBoolean("smartFrameSelected",
                        ScreensaverConfig.load(targetContext).mode == ScreensaverConfig.MODE_SMART)
                })
                return
            }
            if (options.getString("command") == "faceProbe") {
                val face = SlideshowFaceDeviceProbe.run(this)
                finish(0, face)
                return
            }
            if (options.getString("command") == "slideshow") {
                val slideshow = SlideshowDeviceChecks.run(this, automation, options)
                finish(if (slideshow.getBoolean("slideshowCompleted")) 0 else 1, slideshow)
                return
            }
            if (options.getString("command") == "engineDerivatives") {
                val derivatives = EngineDerivativeDeviceChecks.run(this)
                finish(if (derivatives.getBoolean("derivativesCompleted")) 0 else 1, derivatives)
                return
            }
            if (options.getString("command") == "remoteAppsNavigation") {
                val navigation = RemoteAppsNavigationDeviceChecks.run(this, automation, options)
                finish(if (navigation.getBoolean("remoteAppsNavigationCompleted")) 0 else 1, navigation)
                return
            }
            if (options.getString("command") == "framePerformance") {
                val frames = FramePerformanceDeviceChecks.run(this)
                finish(if (frames.getBoolean("framePerformanceCompleted")) 0 else 1, frames)
                return
            }
            if (options.getString("command") == "appearance") {
                val appearance = AppearanceDeviceChecks.run(this, automation)
                finish(if (appearance.getBoolean("appearanceCompleted")) 0 else 1, appearance)
                return
            }
            if (options.getString("command") in listOf("toolsSmoke", "toolsPerformance")) {
                val tools = ToolsUxDeviceChecks.run(this, automation, options)
                finish(if (tools.getBoolean("toolsCompleted")) 0 else 1, tools)
                return
            }
            if (options.getString("command") == "widgetPaging") {
                val widgets = WidgetPagingDeviceChecks.run(this, automation)
                finish(if (widgets.getBoolean("widgetPagingCompleted")) 0 else 1, widgets)
                return
            }
            if (options.getString("command") == "touchPaging") {
                benchmarkTouchPaging(automation)
                finish(0, Bundle().apply {
                    putBoolean("touchPagingCompleted", true)
                    if (options.getString("profile") == "sample") {
                        putString("samplingTrace", "cache/portal-ux-e2e/home-sampling.trace")
                    }
                })
                return
            }
            if (options.getString("command") == "observe") {
                observe(automation)
                finish(0, result)
                return
            }
            val root = root(automation)
            if (options.getString("command") == "tap") {
                val label = requireNotNull(options.getString("label"))
                val target = find(root, label) ?: error("Requested control absent")
                val bounds = Rect().also(target::getBoundsInScreen)
                check(!bounds.isEmpty)
                val now = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(now, now + index * 50L, action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                result.putBoolean("tapped", true)
            } else {
                val name = options.getString("name", "snapshot").replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val folder = File(targetContext.cacheDir, "portal-ux-e2e").apply { mkdirs() }
                val snapshot = snapshot(root, 0, intArrayOf(0))
                File(folder, "$name.json").writeText(snapshot.toString())
                automation.takeScreenshot()?.let { bitmap ->
                    try {
                        File(folder, "$name.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
                val wanted = JSONArray(options.getString("expected", "[]"))
                val checks = JSONObject()
                for (i in 0 until wanted.length()) {
                    val label = wanted.getString(i)
                    checks.put(label, find(root, label) != null)
                }
                result.putString("assertions", checks.toString())
                result.putString("snapshot", "cache/portal-ux-e2e/$name.json")
                result.putString("screenshot", "cache/portal-ux-e2e/$name.png")
                result.putInt("nodeCount", snapshot.optInt("totalNodes"))
            }
            finish(0, result)
        } catch (error: Throwable) {
            result.putString("errorType", error.javaClass.simpleName)
            // Private diagnostics distinguish test-runner linkage from app rendering.
            runCatching {
                val folder = File(targetContext.cacheDir, "portal-ux-e2e")
                folder.mkdirs()
                val writer = java.io.PrintWriter(File(folder, "probe-error.txt"))
                try { error.printStackTrace(writer) } finally { writer.close() }
            }
            finish(1, result)
        }
    }

    /** Navigation-only input, without a shell VM or full window dump before every touch. */
    private fun benchmarkTouchPaging(automation: UiAutomation) {
        val profile = options.getString("profile")
        check(profile == null || profile == "sample")
        val sampling = profile == "sample"
        val scenario = options.getString("scenario", "paging")
        check(scenario == "paging" || scenario == "noop" || scenario == "tabs" || scenario == "settings")
        val noop = scenario == "noop"
        val tabs = scenario == "tabs"
        val settings = scenario == "settings"
        val safety = options.getString("safety", "tree")
        check(safety == "tree" || safety == "window")
        val windowSafety = safety == "window"
        val launch = Intent().setClassName(targetContext.packageName,
            if (settings) "com.immortal.launcher.ImmortalSettingsActivity"
            else "com.immortal.launcher.HomeActivity").apply {
                if (settings) putExtra("approved_settings_category", "appearance")
                else putExtra("approved_home_tab", "home")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        targetContext.startActivity(launch)
        SystemClock.sleep(3_000)
        val initial = root(automation)
        check(initial.packageName?.toString() == targetContext.packageName)
        if (settings) {
            check(find(initial, "Appearance") != null && find(initial, "Display") != null)
        } else {
            check(find(initial, "Favorite apps") != null)
            check(hasInitialPage(initial))
        }
        val dockLabels = listOf("Apps", "Tools", "Home")
        val initialWindowId = initial.windowId
        check(initialWindowId >= 0)
        // Retain only existing system chrome. Any additional window aborts the window probe,
        // including a non-focusable overlay; package identity was checked on the initial root.
        val initialSystemWindows = run {
            val windows = automation.windows
            try {
                windows.filter {
                    it.id != initialWindowId && it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                        !it.isActive && !it.isFocused
                }.map { it.id }.toSet()
            } finally {
                windows.forEach { it.recycle() }
            }
        }

        fun verifyWindow(bounds: Rect) {
            val windows = automation.windows
            try {
                val active = windows.singleOrNull { it.isActive }
                    ?: error("No single active benchmark window")
                check(active.id == initialWindowId &&
                    active.type == AccessibilityWindowInfo.TYPE_APPLICATION)
                check(Rect().also(active::getBoundsInScreen).contains(bounds))
                windows.forEach { window ->
                    if (window.id != initialWindowId) {
                        check(window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                            window.id in initialSystemWindows && !window.isActive && !window.isFocused)
                        val windowBounds = Rect().also(window::getBoundsInScreen)
                        check(window.layer <= active.layer ||
                            !Rect.intersects(windowBounds, bounds))
                    }
                }
            } finally {
                windows.forEach { it.recycle() }
            }
        }

        fun tap(bounds: Rect) {
            if (windowSafety || settings) verifyWindow(bounds)
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                if (index == 1) SystemClock.sleep(40)
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            SystemClock.sleep(220)
        }

        fun verifySettingsOverview(active: AccessibilityNodeInfo) {
            check(active.packageName?.toString() == targetContext.packageName)
            check(active.windowId == initialWindowId)
            check(find(active, "Appearance") != null && find(active, "Display") != null)
            check(findClickable(active, "Display") != null)
            check(findClickable(active, "Settings") == null)
        }

        fun settingsControlBounds(active: AccessibilityNodeInfo, label: String): Rect {
            val control = findClickable(active, label) ?: error("Requested navigation control absent")
            return Rect().also(control::getBoundsInScreen).also { check(!it.isEmpty) }
        }

        val density = targetContext.resources.displayMetrics.density
        var nextSwipe: WidgetPagingDeviceChecks.Swipe? = null
        var previousSwipe: WidgetPagingDeviceChecks.Swipe? = null
        val gestures = when {
            settings -> {
                verifySettingsOverview(initial)
                val display = settingsControlBounds(initial, "Display")
                tap(display)
                val detail = root(automation)
                check(detail.packageName?.toString() == targetContext.packageName)
                check(detail.windowId == initialWindowId)
                check(find(detail, "Appearance") != null && find(detail, "Display") != null)
                val back = settingsControlBounds(detail, "Settings")
                tap(back)
                verifySettingsOverview(root(automation))
                listOf(display to 0, back to 0)
            }
            tabs -> dockLabels.map { label ->
                val node = find(initial, label) ?: error("Dock control absent")
                Rect().also(node::getBoundsInScreen).also { check(!it.isEmpty) } to 0
            }
            else -> {
                val page = checkNotNull(WidgetPagingDeviceChecks.page(initial))
                if (noop) {
                    previousSwipe = WidgetPagingDeviceChecks.horizontalSwipe(initial, density, right = true)
                    listOf(previousSwipe!!.bounds() to -1, previousSwipe!!.bounds() to -1)
                } else {
                    check(page.count >= 2) { "Paging benchmark requires multiple widget pages" }
                    nextSwipe = WidgetPagingDeviceChecks.horizontalSwipe(initial, density, right = false)
                    verifyWindow(nextSwipe!!.bounds())
                    WidgetPagingDeviceChecks.swipe(automation, nextSwipe!!)
                    SystemClock.sleep(320)
                    val second = root(automation)
                    check(second.packageName?.toString() == targetContext.packageName &&
                        second.windowId == initialWindowId)
                    check(WidgetPagingDeviceChecks.page(second) ==
                        WidgetPagingDeviceChecks.Page(2, page.count))
                    previousSwipe = WidgetPagingDeviceChecks.horizontalSwipe(second, density, right = true)
                    verifyWindow(previousSwipe!!.bounds())
                    WidgetPagingDeviceChecks.swipe(automation, previousSwipe!!)
                    SystemClock.sleep(320)
                    check(hasInitialPage(root(automation)))
                    listOf(nextSwipe!!.bounds() to 1, previousSwipe!!.bounds() to -1)
                }
            }
        }

        fun roundTrip() {
            gestures.forEachIndexed { index, (bounds, direction) ->
                if (!windowSafety) {
                    val active = root(automation)
                    check(active.packageName?.toString() == targetContext.packageName)
                    check(active.windowId == initialWindowId)
                    if (settings) {
                        check(active.windowId == initialWindowId)
                        check(find(active, "Appearance") != null && find(active, "Display") != null)
                        if (index == 0) verifySettingsOverview(active)
                        else check(findClickable(active, "Settings") != null)
                    } else if (tabs) {
                        dockLabels.forEach { check(find(active, it) != null) }
                    } else {
                        check(find(active, "Favorite apps") != null)
                        WidgetPagingDeviceChecks.region(active)
                        val page = checkNotNull(WidgetPagingDeviceChecks.page(active))
                        check(page.number == if (noop || index == 0) 1 else 2)
                    }
                    if (noop) check(hasInitialPage(active))
                }
                if (direction == 0) tap(bounds)
                else {
                    if (windowSafety) verifyWindow(bounds)
                    WidgetPagingDeviceChecks.swipe(automation, checkNotNull(
                        if (direction > 0) nextSwipe else previousSwipe))
                    SystemClock.sleep(320)
                    if (!windowSafety) {
                        val active = root(automation)
                        check(active.windowId == initialWindowId &&
                            active.packageName?.toString() == targetContext.packageName)
                        val page = checkNotNull(WidgetPagingDeviceChecks.page(active))
                        check(page.number == if (noop || index == 1) 1 else 2)
                    }
                }
            }
        }
        repeat(6) { roundTrip() }
        automation.executeShellCommand("dumpsys gfxinfo ${targetContext.packageName} reset")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .use { it.readBytes() } }
        var tracing = false
        try {
            if (sampling) {
                val trace = File(targetContext.cacheDir, "portal-ux-e2e").apply { mkdirs() }
                    .resolve("home-sampling.trace")
                Debug.startMethodTracingSampling(trace.absolutePath, 8 * 1024 * 1024, 1000)
                tracing = true
            }
            repeat(if (sampling) 12 else 65) { roundTrip() }
        } finally {
            if (tracing) Debug.stopMethodTracing()
        }
        // Capture measured frames before the final accessibility traversal adds diagnostic work.
        val output = automation.executeShellCommand("dumpsys gfxinfo ${targetContext.packageName} framestats")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .use { it.readBytes() } }
        if (settings) {
            verifySettingsOverview(root(automation))
        } else {
            val finalHome = root(automation)
            check(finalHome.packageName?.toString() == targetContext.packageName)
            check(finalHome.windowId == initialWindowId)
            check(find(finalHome, "Favorite apps") != null)
            WidgetPagingDeviceChecks.region(finalHome)
            if (tabs) dockLabels.forEach { check(find(finalHome, it) != null) }
            check(hasInitialPage(finalHome))
        }
        File(targetContext.cacheDir, "portal-ux-e2e").apply { mkdirs() }
            .resolve("home-touch-frames.txt").writeBytes(output)
    }

    /** Only the page counter is inspected; its text is never emitted. */
    private fun hasInitialPage(node: AccessibilityNodeInfo): Boolean =
        WidgetPagingDeviceChecks.page(node)?.number == 1

    private fun observe(automation: UiAutomation) {
        val folder = File(targetContext.cacheDir, "portal-ux-e2e").apply { mkdirs() }
        val request = File(folder, "request.json")
        val response = File(folder, "response.json")
        request.delete()
        response.delete()
        val homeMonitor = addMonitor("com.immortal.launcher.HomeActivity", null, false)
        val musicMonitor = addMonitor("com.immortal.launcher.MusicDesignPreviewActivity", null, false)
        targetContext.startActivity(Intent().setClassName(targetContext.packageName,
            "com.immortal.launcher.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        sendStatus(0, Bundle().apply { putBoolean("observerReady", true) })
        val remoteEnabledBefore = RemotePairing.isEnabled(targetContext)
        val deadline = SystemClock.uptimeMillis() + 45 * 60_000L
        val captureProbe = lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "portal-capture-probe").apply { isDaemon = true }
            }
        }
        try {
        while (SystemClock.uptimeMillis() < deadline) {
            if (!request.exists()) {
                SystemClock.sleep(100)
                continue
            }
            val command = JSONObject(request.readText())
            request.delete()
            if (command.optString("command") == "stop") return
            val name = command.optString("name", "latest").replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val phases = if (command.optBoolean("traceCapture", false)) JSONArray() else null
            val currentPhase = java.util.concurrent.atomic.AtomicReference("capture-start")
            val captureFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val captureId = if (phases == null) null else java.util.UUID.randomUUID().toString()
            val stallFile = captureId?.let { File(folder, "$name-$it-stall.json") }
            if (phases != null) {
                runCatching { File(folder, "$name-phase.json").delete() }
            }
            fun capturePhase(stage: String) {
                val history = phases ?: return
                currentPhase.set(stage)
                runCatching {
                    history.put(JSONObject().put("stage", stage).put("uptimeMillis", SystemClock.uptimeMillis()))
                    File(folder, "$name-phase.json").writeText(
                        JSONObject().put("name", name).put("captureId", captureId)
                            .put("stallFile", stallFile?.name).put("phases", history).toString())
                }
            }
            val observingThread = Thread.currentThread()
            val stallProbe = if (phases == null) null else captureProbe.value.schedule({
                runCatching {
                    if (captureFinished.get()) return@schedule
                    val stage = currentPhase.get()
                    val sampledAt = SystemClock.uptimeMillis()
                    val threads = JSONArray()
                    for (thread in listOf(android.os.Looper.getMainLooper().thread, observingThread)) {
                        threads.put(JSONObject().put("name", thread.name).put("state", thread.state.name)
                            .put("stack", JSONArray(thread.stackTrace.take(32).map { it.toString() })))
                    }
                    if (!captureFinished.get()) {
                        // A delayed write belongs only to this capture ID. Cleanup never waits
                        // for diagnostic I/O, and a reused request name cannot overwrite it.
                        stallFile?.writeText(JSONObject().put("name", name).put("captureId", captureId)
                            .put("sampledAtUptimeMillis", sampledAt)
                            .put("stage", stage).put("threads", threads).toString())
                    }
                }
            }, 9, java.util.concurrent.TimeUnit.SECONDS)
            try {
                if (command.optString("command") == "nightBrightnessDelivery") {
                    val report = NightBrightnessDeliveryDeviceChecks.run(this, automation)
                    File(folder, "$name.json").writeText(report.toString())
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                if (command.optString("command") == "slideshowDiscovery") {
                    val report = SlideshowDiscoveryDeviceChecks.run(this)
                    File(folder, "$name.json").writeText(report.toString())
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                if (command.optString("command") == "slideshowVideoNight") {
                    val report = SlideshowVideoNightDeviceChecks.run(this)
                    File(folder, "$name.json").writeText(report.toString())
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                if (command.optString("command") == "filmstripFileAdmission") {
                    val report = FilmstripFileAdmissionDeviceChecks.run(targetContext)
                    File(folder, "$name.json").writeText(report.toString())
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                if (command.optString("command") == "iconProbe") {
                    val report = IconRendererDeviceRegression.verify(targetContext)
                    response.writeText(JSONObject().put("name", name).put("success", true)
                        .put("report", report).toString())
                    continue
                }
                if (command.optString("command") == "exportAppIcons") {
                    val inventory = checkNotNull(peekApprovedIconInventory())
                    val exported = File(folder, name).apply { mkdirs() }
                    val entries = JSONArray()
                    inventory.forEachIndexed { index, app ->
                        val image = File(exported, "$index.png")
                        image.outputStream().use {
                            check(app.icon.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                        val builtin = when (app.component.className.substringAfterLast('.')) {
                            "StoreActivity" -> "store"
                            "WebAppActivity" -> "music"
                            "ImmortalSettingsActivity" -> "settings"
                            "__immortal_calls_bridge__" -> "calls"
                            "ToolsActivity" -> "tools"
                            else -> null
                        }
                        entries.put(JSONObject().put("packageName", app.component.packageName)
                            .put("component", app.component.flattenToString()).put("label", app.label)
                            .put("builtin", builtin ?: JSONObject.NULL).put("image", image.name))
                    }
                    File(exported, "manifest.json").writeText(entries.toString())
                    response.writeText(JSONObject().put("name", name).put("success", true)
                        .put("count", entries.length()).toString())
                    continue
                }
                if (command.optString("command") == "returnHome") {
                    runOnMainSync { BackHelper.ensureImmortalHome(targetContext) }
                    waitForIdleSync()
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                capturePhase("accessibility-root")
                val active = root(automation)
                if (command.optString("command") == "recreateHome") {
                    val home = checkNotNull(homeMonitor.lastActivity)
                    runOnMainSync { home.recreate() }
                    waitForIdleSync()
                    SystemClock.sleep(500)
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                if (command.optString("command") == "widgetGesture") {
                    widgetGesture(automation, active, command) { phase ->
                        File(folder, "$name-gesture.json").writeText(JSONObject()
                            .put("phase", phase).put("pointerDown", phase != "finished").toString())
                    }
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                if (command.optString("command") == "setText") {
                    val fields = mutableListOf<AccessibilityNodeInfo>()
                    collectEditable(active, fields)
                    val target = fields[command.getInt("field")]
                    val value = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            command.getString("value"))
                    }
                    check(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value))
                    response.writeText(JSONObject().put("name", name).put("success", true).toString())
                    continue
                }
                capturePhase("accessibility-tree")
                val snapshot = snapshotWindows(automation, active)
                capturePhase("fixture-metadata")
                runOnMainSync {
                    currentPhase.set("fixture-metadata-running")
                    snapshot.put("fixturePhotoControllerIdentity",
                        (musicMonitor.lastActivity as? MusicDesignPreviewActivity)
                            ?.realPhotoControllerIdentityForChecks ?: 0)
                    currentPhase.set("fixture-metadata-finished")
                }
                capturePhase("snapshot-json")
                File(folder, "$name.json").writeText(snapshot.toString())
                capturePhase("screenshot")
                val capturedBitmap = automation.takeScreenshot()
                try {
                    capturePhase("screenshot-png")
                    capturedBitmap?.let { bitmap ->
                        File(folder, "$name.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                } finally {
                    capturedBitmap?.recycle()
                }
                capturePhase("response")
                response.writeText(JSONObject().put("name", name).put("captureId", captureId)
                    .put("success", true).toString())
            } catch (error: Throwable) {
                capturePhase("error")
                response.writeText(JSONObject().put("name", name).put("captureId", captureId).put("success", false)
                    .put("errorType", error.javaClass.simpleName).toString())
            } finally {
                captureFinished.set(true)
                stallProbe?.cancel(false)
            }
        }
        } finally {
            if (captureProbe.isInitialized()) captureProbe.value.shutdownNow()
            removeMonitor(homeMonitor)
            removeMonitor(musicMonitor)
            // Opening the real pairing UI opts in. A test visit must preserve the prior opt-in.
            if (RemotePairing.isEnabled(targetContext) != remoteEnabledBefore) {
                RemotePairing.setEnabled(targetContext, remoteEnabledBefore)
                SettingsGuard.reconcileBarWatch(targetContext)
                if (!remoteEnabledBefore && !FleetConfig.isEnabled(targetContext)) {
                    targetContext.stopService(Intent(targetContext, FleetAgentService::class.java))
                }
            }
        }
    }

    /** Functional QA only: real stationary hold, movement and release/cancel in Customize. */
    private fun widgetGesture(automation: UiAutomation, active: AccessibilityNodeInfo, command: JSONObject,
        progress: (String) -> Unit) {
        check(active.packageName?.toString() == targetContext.packageName)
        check(find(active, "Done") != null)
        val owner = find(active, command.getString("source")) ?: error("Widget not found")
        check(owner.isVisibleToUser)
        val source = if (command.optBoolean("resize")) find(owner, "Resize widget") ?: error("Handle not found")
            else owner
        check(source.isVisibleToUser)
        val bounds = Rect().also(source::getBoundsInScreen)
        check(!bounds.isEmpty)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val targetLabel = command.optString("target")
        val target = if (targetLabel.isNotEmpty()) {
            val node = find(active, targetLabel) ?: error("Target not found")
            check(node.isVisibleToUser)
            Rect().also(node::getBoundsInScreen).also { check(!it.isEmpty) }
        } else null
        val endX = target?.exactCenterX() ?: (x + command.optInt("dx"))
        val endY = target?.exactCenterY() ?: (y + command.optInt("dy"))
        val hold = command.optLong("holdMs", 0).also { require(it in 0..2_000) }
        val moving = command.optLong("moveMs", 700).also { require(it in 100..3_000) }
        val edgeHold = command.optLong("edgeHoldMs", 0).also { require(it in 0..3_000) }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        var pressed = false
        try {
            inject(MotionEvent.ACTION_DOWN, x, y)
            pressed = true
            progress("pressed")
            SystemClock.sleep(hold)
            val steps = (moving / 16).toInt().coerceAtLeast(1)
            repeat(steps) { index ->
                val fraction = (index + 1).toFloat() / steps
                inject(MotionEvent.ACTION_MOVE, x + (endX - x) * fraction, y + (endY - y) * fraction)
                if (index == steps * 3 / 4) progress("moving")
                SystemClock.sleep(moving / steps)
            }
            SystemClock.sleep(edgeHold)
            if (command.optBoolean("handoff")) {
                fun multi(action: Int, ids: IntArray, points: List<Pair<Float, Float>>) {
                    val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
                        this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
                    } }.toTypedArray()
                    val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
                        this.x = px; this.y = py; pressure = 1f; size = 1f
                    } }.toTypedArray()
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        ids.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                val first = endX to endY
                val second = endX + 80f to endY + 20f
                multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    intArrayOf(0, 1), listOf(first, second))
                SystemClock.sleep(100)
                multi(MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 1), listOf(first, second))
                SystemClock.sleep(100)
                multi(MotionEvent.ACTION_MOVE, intArrayOf(1), listOf(second.first + 10f to second.second))
                SystemClock.sleep(100)
                multi(MotionEvent.ACTION_UP, intArrayOf(1), listOf(second.first + 10f to second.second))
            } else {
                inject(if (command.optBoolean("cancel")) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX, endY)
            }
            pressed = false
        } finally {
            if (pressed) runCatching { inject(MotionEvent.ACTION_CANCEL, endX, endY) }
            progress("finished")
        }
    }

    private fun collectEditable(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0) {
        if (depth > 60) return
        if (node.isEditable) result += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectEditable(it, result, depth + 1) }
        }
    }

    private fun root(automation: UiAutomation): AccessibilityNodeInfo {
        repeat(20) {
            automation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(100)
        }
        error("No active native window")
    }

    /** Compose search popups are separate, non-focusable windows above the Activity. */
    private fun snapshotWindows(automation: UiAutomation, active: AccessibilityNodeInfo): JSONObject {
        val roots = automation.windows.sortedByDescending { it.layer }.mapNotNull { window ->
            window.root?.takeIf { it.packageName?.toString() == targetContext.packageName }
        }
        if (roots.isEmpty()) return snapshot(active, 0, intArrayOf(0))
        val count = intArrayOf(0)
        return JSONObject().put("class", "native-windows")
            .put("children", JSONArray(roots.map { snapshot(it, 0, count) }))
            .put("totalNodes", count[0])
    }

    private fun find(node: AccessibilityNodeInfo, label: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 60) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, label, depth + 1)?.let { return it } }
        }
        return null
    }

    /** Navigation discovery excludes static headings with the same text as a back button. */
    private fun findClickable(node: AccessibilityNodeInfo, label: String,
        depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 60) return null
        if (node.isClickable && node.isEnabled && node.isVisibleToUser &&
            find(node, label, depth) != null) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findClickable(child, label, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, count: IntArray): JSONObject {
        if (depth > 60 || count[0] >= 5000) return JSONObject().put("truncated", true)
        count[0]++
        val bounds = Rect().also(node::getBoundsInScreen)
        val children = JSONArray()
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { children.put(snapshot(it, depth + 1, count)) }
        }
        return JSONObject()
            .put("text", if (node.isPassword) "" else node.text?.toString().orEmpty())
            .put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty())
            .put("class", node.className?.toString().orEmpty())
            .put("clickable", node.isClickable).put("enabled", node.isEnabled)
            .put("selected", node.isSelected)
            .put("checked", node.isChecked).put("checkable", node.isCheckable)
            .put("password", node.isPassword).put("visible", node.isVisibleToUser)
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("children", children)
            .apply { if (depth == 0) put("totalNodes", count[0]) }
    }
}
