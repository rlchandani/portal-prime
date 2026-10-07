/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native, test-APK-only checks for the approved Tools host.
 *
 * Entry points: command=toolsSmoke / toolsPerformance. Optional toolsSpeedCancel=true
 * exercises native Start/Cancel/Run again; toolsEnableAdvanced=true permits temporarily
 * enabling Advanced through the settings registry, restoring its value and key presence.
 * No permission is granted/revoked.
 *
 * Plan: verify the eleven literal library entries and retained chrome; exercise child
 * cancel/validation/return boundaries; remove only owned fixtures; compare config hashes.
 * Countdown Add/Remove uses a unique label and new ID. Note Save/Clear runs only when
 * the original text_note key is absent; household notes receive draft-only checks.
 * A short voice memo uses native Record/Stop/Play/Stop/Delete only when microphone access
 * was already granted and the original memo file was completely absent; even an empty file skips it.
 * Failure cleanup closes the host and drains shared native audio work before deleting our memo.
 * toolsCaptureNotes=true permits a private workspace image only after memo deletion and
 * synthetic text clearing, with neither original note nor original memo content present.
 * Performance separately warms four local bodies, caches rail bounds, runs three bounded
 * trials using windows (no tree reads in the measured loop), then samples five memory
 * blocks and idle frames. Main owns compilation, installation and device execution.
 *
 * Instrumentation restarts the target process. Launch Home with Tools selected BEFORE any
 * root/bounds discovery, then keep all flows in that one session. Finishing the test-owned
 * Home discards the note draft. Persisted note comparisons never emit text values.
 * No hierarchy, preference value, exception message, address or token is emitted. Images
 * and raw gfxinfo stay in the target app's private cache; JSON contains allowlisted labels,
 * numbers, hashes, booleans and private relative artifact paths.
 */
internal object ToolsUxDeviceChecks {
    fun run(instrumentation: Instrumentation, automation: UiAutomation, options: Bundle): Bundle =
        Probe(instrumentation, automation, options).run()

    private data class Tool(val id: String, val title: String, val ready: String)

    // Independent expectations: do not derive discovery coverage from the production catalog.
    private val ordinary = listOf(
        Tool("alarm", "Alarm", "Your next alarm"),
        Tool("timers", "Timers", "Start a timer"),
        Tool("countdowns", "Countdowns", "Dates to look forward to"),
        Tool("converter", "Converter", "Convert"),
        Tool("lamp", "Lamp", "A softer light"),
        Tool("notes", "Leave a note", "Your message"),
        Tool("camera", "Cameras", "Your cameras"),
        Tool("intercom", "Intercom", "Broadcast this room"),
        Tool("stories", "Bedtime story", "Pick a story"),
        Tool("iss", "ISS passes", "Upcoming passes"),
        Tool("aurora", "Aurora outlook", "Conditions"),
    )
    private val localTools = listOf("alarm", "converter", "countdowns", "lamp")
    private val configNames = listOf(
        "immortal_alarm", "immortal_timers", "immortal_countdown",
        "immortal_cameras", "immortal_notes", "immortal_settings",
    )
    private const val POLL_MS = 80L
    private const val WAIT_MS = 4_000L
    private const val TRIAL_MS = 28_000L
    private const val MAX_NODES = 4_000
    private const val COUNTDOWN_GAP = "Countdown Add/Remove persistence and Remove confirmation Cancel"
    private const val NOTE_GAP = "Note Save/Clear persistence and Clear confirmation Cancel"
    private const val MEMO_GAP = "Voice memo Record/Stop/Play/Stop/Delete confirmations (requires existing microphone permission and no original memo file)"
    private const val SPEED_GAP = "Speed Start/Cancel/Run again (requires toolsSpeedCancel=true; toolsEnableAdvanced=true when Advanced is disabled)"

    private class ProbeFailure(val assertion: String) : RuntimeException()
    private class UiUnavailable : RuntimeException()
    private data class Control(val bounds: Rect, val enabled: Boolean, val selected: Boolean)
    private data class TimerMeta(val id: String, val duration: Long, val end: Long)
    private data class Frames(
        val durationsMs: List<Double>, val flagged: Int, val invalid: Int, val tables: Int,
    ) {
        fun json(): JSONObject {
            val sorted = durationsMs.sorted()
            fun percentile(fraction: Double): Any =
                if (sorted.isEmpty()) JSONObject.NULL
                else sorted[kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
            return JSONObject().put("validFrames", sorted.size).put("flaggedFrames", flagged)
                .put("invalidFrames", invalid).put("frameTables", tables)
                .put("completedFrames", sorted.size + flagged)
                .put("frameDurationDefinition", "FrameCompleted - IntendedVsync")
                .put("frameDurationMsP50", percentile(.50)).put("frameDurationMsP95", percentile(.95))
                .put("frameDurationMsP99", percentile(.99))
                .put("framesOver16_667Ms", sorted.count { it > 16.667 })
                .put("framesOver33_333Ms", sorted.count { it > 33.333 })
        }
    }

    private class Probe(
        private val instrumentation: Instrumentation,
        private val automation: UiAutomation,
        private val options: Bundle,
    ) {
        private val context = instrumentation.targetContext
        private val command = options.getString("command")
        private val start = SystemClock.uptimeMillis()
        private val deadline = start + 180_000L
        private val relative = "cache/portal-ux-e2e/tools-${start}"
        private val folder = File(context.cacheDir, "portal-ux-e2e/tools-${start}")
        private val checks = JSONObject()
        private val skips = JSONObject()
        private val artifacts = JSONArray().put("$relative/report.json")
        private val notCovered = linkedSetOf(
            "Real alarm firing/snooze or timer completion audio",
            "Intercom microphone broadcast or story narration",
            "Reachable household cameras or actual ISS/Aurora feed accuracy",
            "All converter units",
            COUNTDOWN_GAP, NOTE_GAP, MEMO_GAP, SPEED_GAP,
        )
        private val report = JSONObject().put("command", command)
            .put("assertions", checks).put("skips", skips).put("privateArtifacts", artifacts)
            .put("functionalPollIntervalMs", POLL_MS)
            .put("readinessDefinition", "Accessible workspace/body heading observed; includes polling/tree overhead")
            .put("inputDurationDefinition", "Synchronous UiAutomation injection calls and pointer hold; not physical input latency")
            .put("notCovered", JSONArray(notCovered.toList()))
        private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()
        private var host: Activity? = null
        private var hostWindow = -1
        private var systemWindows = emptySet<Int>()
        private var screen = Rect()
        private val chrome = linkedMapOf<String, Rect>()
        private var headingBounds: Rect? = null
        // Track only this probe's navigation; window, heading and selection checks stay native.
        private var ownedWorkspace: String? = null
        private var before = emptyMap<String, String>()
        private var audioBefore = ""
        private var micBefore = false
        // exists(), rather than hasAudioNote(), preserves even an original zero-byte file.
        private var memoFileExisted = true
        private var memoFixtureOwned = false
        private var ownedMemoFingerprint: String? = null
        private var advancedBefore = false
        private var advancedKeyExisted = false
        private var advancedFixtureOwned = false
        private var timerKeyExisted = false
        private var cameraKeyExisted = false
        private var countdownKeyExisted = false
        private var noteKeyExisted = false
        private var timerKeyOwned = false
        private var cameraKeyOwned = false
        private var countdownKeyOwned = false
        private var ownedTimer: TimerMeta? = null
        private var timerBaseline = emptySet<String>()
        private var timerStartAt = 0L
        private var timerDuration = 0L
        private var ownedCameraId: Long? = null
        private var cameraBaseline = emptySet<Long>()
        private var cameraAddStarted = false
        private val cameraName = "Tools probe ${start}"
        private val fixtureTag = UUID.randomUUID().toString()
        private val countdownName = "Tools countdown probe $fixtureTag"
        private var countdownBaseline = emptySet<Long>()
        private var countdownAddStarted = false
        private var ownedCountdownId: Long? = null
        private val syntheticNote = "Temporary Tools probe draft $fixtureTag"
        private var noteSaveStarted = false
        private var lampRedChanged = false
        private val readiness = JSONArray()
        private val fullscreen = mutableListOf<Activity>()
        private var stage = "setup"
        private var step = "setup"
        private var lookupDiagnostics = JSONObject()
        private var failed = false

        fun run(): Bundle {
            try {
                require(command == "toolsSmoke" || command == "toolsPerformance")
                check(folder.mkdirs() || folder.isDirectory)
                before = fingerprints()
                memoFileExisted = NotesConfig.audioFile(context).exists()
                audioBefore = audioFingerprint()
                micBefore = microphoneGranted()
                advancedBefore = ImmortalSettings.advancedSettings(context)
                advancedKeyExisted = prefs("immortal_settings").contains("advanced_settings")
                timerKeyExisted = prefs("immortal_timers").contains("timers")
                cameraKeyExisted = prefs("immortal_cameras").contains("cameras")
                countdownKeyExisted = prefs("immortal_countdown").contains("events")
                noteKeyExisted = prefs("immortal_notes").contains("text_note")
                launch()
                if (command == "toolsSmoke") smoke() else performance()
            } catch (error: Throwable) {
                failed = true
                report.put("failedStage", stage).put("errorType", error.javaClass.simpleName)
                    .put("failedStep", step)
                    .put("failedAssertion", if (error is ProbeFailure) error.assertion else step)
                    .put("failedLookup", lookupDiagnostics)
                checks.put("completed", false)
                // Preserve only a private image, never a hierarchy or exception message.
                runCatching {
                    screenshot("failure-$stage")
                    report.put("failureScreenshot", "$relative/failure-$stage.png")
                }
            } finally {
                stage = "cleanup"
                cleanUp()
                report.put("observedReadiness", readiness).put("elapsedMs", SystemClock.uptimeMillis() - start)
                    .put("notCovered", JSONArray(notCovered.toList()))
                checks.put("completed", !failed)
                report.put("completed", !failed)
                runCatching {
                    File(folder, "report.json").writeText(report.toString(2))
                }.onFailure {
                    failed = true
                    report.put("completed", false).put("reportWriteFailed", true)
                }
            }
            return Bundle().apply {
                putBoolean("toolsCompleted", !failed)
                putString("assertions", checks.toString())
                putString("toolsReport", report.toString())
                putString("report", "$relative/report.json")
                putString("privateArtifactPaths", artifacts.toString())
            }
        }

        private fun launch() {
            val monitor = instrumentation.addMonitor("com.immortal.launcher.HomeActivity", null, false)
            monitors += monitor
            context.startActivity(Intent(context, HomeActivity::class.java)
                .putExtra("approved_home_tab", "tools")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            poll("tools_host_launched") { monitor.lastActivity != null }
            host = monitor.lastActivity
            waitLabel("Small things that make the day easier.", timeout = 12_000L)
            SystemClock.sleep(500)
            withRoot { root ->
                demand("target_package", root.packageName?.toString() == context.packageName)
                hostWindow = root.windowId
                root.getBoundsInScreen(screen)
                demand("host_window_valid", hostWindow >= 0 && !screen.isEmpty)
            }
            val windows = automation.windows
            try {
                systemWindows = windows.filter { it.id != hostWindow &&
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM && !it.isActive && !it.isFocused
                }.map { it.id }.toSet()
            } finally { windows.forEach { it.recycle() } }
            // Static labels only. The room name and any media metadata are never copied.
            listOf("immortal", "Settings", "Sleep screen", "Home", "Apps", "Tools").forEach {
                chrome[it] = requireControl(it, clickable = it != "immortal" && it != "Tools",
                    preferBottom = it == "Tools").bounds
            }
            checkChrome()
            screenshot("library")
        }

        private fun smoke() {
            stage = "library_dock_navigation"
            dockNavigation()
            ordinary.forEach { tool ->
                stage = "library_${tool.id}"
                openLibrary(tool)
                checkWorkspace(tool)
                if (tool.id in listOf("alarm", "converter", "lamp")) screenshot("workspace-${tool.id}")
                returnLibrary()
                checkLibrary(tool)
            }
            report.put("ordinaryLibraryEntriesChecked", ordinary.size)
            stage = "alarm"; openLibrary(tool("alarm")); alarm()
            stage = "timers"; returnLibrary(); openLibrary(tool("timers")); timers()
            stage = "countdowns"; returnLibrary(); openLibrary(tool("countdowns")); countdowns()
            stage = "lamp"; returnLibrary(); openLibrary(tool("lamp")); lamp()
            stage = "camera"; returnLibrary(); openLibrary(tool("camera")); cameras()
            stage = "stories"; returnLibrary(); openLibrary(tool("stories")); stories()
            stage = "notes"; returnLibrary(); openLibrary(tool("notes")); notes()
            stage = "intercom"; returnLibrary(); openLibrary(tool("intercom")); intercom()
            stage = "advanced_speed"; returnLibrary(); speed()
            demand("completed", true)
        }

        /** Entering Tools from another tab retains the tool; active Tools reselect returns. */
        private fun dockNavigation() {
            tap("Everyday", region = libraryRegion())
            poll("library_everyday_filter_selected") {
                findControl("Everyday", clickable = false, region = libraryRegion())?.selected == true
            }
            openLibrary(tool("alarm"))
            checkWorkspace(tool("alarm"))
            tapBounds(requireControl("Home", preferBottom = true).bounds)
            waitLabel("Favorite apps")
            tapBottomTools()
            waitLabel(tool("alarm").ready)
            checkWorkspace(tool("alarm"))
            demand("tools_enter_from_other_tab_retains_workspace",
                findControl("Alarm", clickable = false, region = railRegion())?.selected == true &&
                    findControl("Your next alarm", region = workspaceRegion()) != null)
            returnLibrary()
            poll("tools_reselect_preserves_library_filter") {
                findControl("Everyday", clickable = false, region = libraryRegion())?.selected == true
            }
            demand("library_filter_still_limits_entries",
                findControl("ISS passes", clickable = true, region = libraryRegion()) == null)
            tap("All tools", region = libraryRegion())
            poll("library_filter_restored_to_all_tools") {
                findControl("All tools", clickable = false, region = libraryRegion())?.selected == true
            }
        }

        private fun alarm() {
            val initialHash = fingerprint("immortal_alarm")
            tap("Change time"); waitLabel("Change alarm time")
            val parentWindow = withRoot { it.windowId }
            openChoice("Minute")
            waitLabel("Minute"); tap("Cancel"); waitLabel("Change alarm time")
            demand("alarm_nested_cancel_returns_to_draft", has("Change alarm time"))
            demand("alarm_minute_cancel_returns_to_parent_window", withRoot { it.windowId } == parentWindow)
            tapPrefix("Hour, "); waitLabel("Hour")
            setText("Hour", "23"); tap("Cancel"); waitLabel("Change alarm time")
            demand("alarm_hour_cancel_returns_to_parent_window", withRoot { it.windowId } == parentWindow)
            tap("Cancel"); checkWorkspace(tool("alarm"))
            demand("alarm_time_cancel_preserves_config", fingerprint("immortal_alarm") == initialHash)
            enterFullscreen(AlarmRingActivity::class.java, "Preview ringing screen", "Alarm preview") {
                demand("alarm_preview_is_explicitly_silent",
                    has("Preview only · no sound or real alarm scheduled") &&
                        it.intent.getBooleanExtra("tools_preview", false))
                screenshot("alarm-silent-preview")
                tap("Dismiss")
            }
            checkWorkspace(tool("alarm"))
            demand("alarm_preview_preserves_config", fingerprint("immortal_alarm") == initialHash)
        }

        private fun timers() {
            val initial = timerMetadata()
            timerBaseline = initial.map { it.id }.toSet()
            val initialHash = fingerprint("immortal_timers")
            tap("Custom timer"); waitLabel("Hours")
            setText("Hours", "0"); setText("Minutes", "0"); setText("Seconds", "0")
            demand("timer_zero_start_disabled",
                has("Start timer") && findControl("Start timer", clickable = true) == null)
            demand("timer_zero_shows_validation",
                has("Enter a duration greater than zero, with each field in range."))
            tap("Cancel"); checkWorkspace(tool("timers"))
            demand("timer_custom_cancel_preserves_config", fingerprint("immortal_timers") == initialHash)

            // A long, distinct duration cannot ring within this bounded probe. Never start a preset.
            val hours = (23 downTo 1).firstOrNull { hour ->
                initial.none { kotlin.math.abs(it.end - System.currentTimeMillis() - hour * 3_600_000L) < 300_000L }
            }
            demand("timer_safe_duration_available", hours != null)
            timerDuration = hours!! * 3_600_000L
            tap("Custom timer"); waitLabel("Hours")
            setText("Hours", hours.toString()); setText("Minutes", "0"); setText("Seconds", "0")
            timerStartAt = System.currentTimeMillis()
            tap("Start timer")
            poll("timer_owned_created") {
                discoverOwnedTimer()
                ownedTimer != null
            }
            checkWorkspace(tool("timers"))
            demand("timer_added_exactly_one", timerMetadata().size == initial.size + 1)
            report.put("timerCounts", JSONObject().put("before", initial.size).put("afterStart", initial.size + 1))
            // Locate only our clock row. Refuse an ambiguous match instead of cancelling user timers.
            val prefix = String.format(Locale.ROOT, "%d:59:", hours - 1)
            val clockMatches: (AccessibilityNodeInfo) -> Boolean = { node ->
                val text = node.text?.toString()
                text != null && (text.startsWith(prefix) ||
                    (hours == 1 && text.startsWith("59:")) || text == "$hours:00:00")
            }
            tapAssociated("Cancel", clockMatches)
            waitLabel("Cancel this timer?")
            tap("Cancel"); checkWorkspace(tool("timers"))
            demand("timer_remove_confirmation_cancel_keeps_owned",
                timerMetadata().any { it.id == ownedTimer?.id })
            tapAssociated("Cancel", clockMatches); waitLabel("Cancel this timer?")
            tap("Cancel timer")
            poll("timer_removed_and_count_restored") {
                val now = timerMetadata()
                now.size == initial.size && now.none { it.id == ownedTimer?.id }
            }
            ownedTimer = null
            timerStartAt = 0L
            report.getJSONObject("timerCounts").put("afterRemoval", timerMetadata().size)
            pruneOwnedEmptyKeys()
            demand("timer_original_config_restored", fingerprint("immortal_timers") == initialHash)
        }

        private fun countdowns() {
            val initialIds = countdownIds()
            countdownBaseline = initialIds.toSet()
            val initialHash = fingerprint("immortal_countdown")
            tap("Add countdown"); waitLabel("Repeat every year")
            val parentWindow = withRoot { it.windowId }
            demand("countdown_year_hidden_when_repeating", !hasPrefix("Year, "))
            tap("Repeat every year"); waitPrefix("Year, ")
            demand("countdown_year_shown_for_one_off", hasPrefix("Year, "))
            listOf("Month", "Day", "Year").forEach { title ->
                val originalChoice = allowedNumericDescription(title)
                tapPrefix("$title, "); waitLabel(title)
                setText(title, if (title == "Year") "2099" else "1")
                tap("Cancel"); waitLabel("Repeat every year")
                demand("countdown_${title.lowercase(Locale.ROOT)}_cancel_returns_to_add", has("Name"))
                demand("countdown_${title.lowercase(Locale.ROOT)}_cancel_keeps_value", has(originalChoice))
                demand("countdown_${title.lowercase(Locale.ROOT)}_cancel_returns_to_parent_window",
                    withRoot { it.windowId } == parentWindow)
            }
            openChoice("Icon"); waitLabel("Icon"); tap("Cancel"); waitLabel("Repeat every year")
            demand("countdown_icon_cancel_returns_to_parent_window", withRoot { it.windowId } == parentWindow)
            tap("Repeat every year")
            demand("countdown_year_hides_again", !hasPrefix("Year, "))
            screenshot("countdown-add")
            tap("Cancel"); checkWorkspace(tool("countdowns"))
            demand("countdown_add_cancel_preserves_config", fingerprint("immortal_countdown") == initialHash)

            val today = Calendar.getInstance()
            val month = today.get(Calendar.MONTH) + 1
            val day = today.get(Calendar.DAY_OF_MONTH)
            val year = today.get(Calendar.YEAR)
            demand("countdown_current_year_supported", year in 2000..2100)
            tap("Add countdown"); waitLabel("Repeat every year")
            setText("Name", countdownName)
            tap("Repeat every year"); waitPrefix("Year, ")
            val addWindow = withRoot { it.windowId }
            listOf("Month" to month, "Day" to day, "Year" to year).forEach { (title, value) ->
                tapPrefix("$title, "); waitLabel(title)
                setText(title, value.toString())
                // EverydayNumberChoice commits with Save, not the parent's Add countdown.
                tap("Save")
                poll("countdown_${title.lowercase(Locale.ROOT)}_save_returns_to_add") {
                    withRoot { it.windowId == addWindow } && has("$title, $value")
                }
            }
            demand("countdown_original_ids_preserved_before_add", countdownIds() == initialIds)
            countdownAddStarted = true
            tap("Add countdown")
            poll("countdown_unique_owned_fixture_created") {
                discoverOwnedCountdown()
                ownedCountdownId != null
            }
            val fixtureId = checkNotNull(ownedCountdownId)
            val addedIds = countdownIds()
            demand("countdown_added_exactly_one", addedIds.size == initialIds.size + 1)
            demand("countdown_added_exact_owned_id_preserves_original_ids",
                fixtureId !in countdownBaseline && addedIds.toSet() == countdownBaseline + fixtureId &&
                    ownedCountdownIds() == listOf(fixtureId))
            val persistedFixture = JSONArray(prefs("immortal_countdown").getString("events", "[]") ?: "[]")
                .let { array -> (0 until array.length()).map { array.getJSONObject(it) }
                    .single { it.getLong("id") == fixtureId } }
            demand("countdown_current_date_persisted",
                persistedFixture.getString("label") == countdownName &&
                    persistedFixture.getInt("month") == month && persistedFixture.getInt("day") == day &&
                    persistedFixture.getInt("year") == year)
            report.put("countdownCounts", JSONObject().put("before", initialIds.size)
                .put("afterAdd", addedIds.size))
            checkWorkspace(tool("countdowns"))
            val fixtureRow: (AccessibilityNodeInfo) -> Boolean = { matches(it, "🎂 $countdownName") }
            // The Remove button is centered across the title AND date lines of the row.
            tapAssociated("Remove", fixtureRow, verticalMargin = dp(32))
            waitLabel("Remove countdown?")
            demand("countdown_remove_confirmation_targets_owned_fixture",
                has("Remove “$countdownName” from your countdowns?"))
            tap("Cancel"); checkWorkspace(tool("countdowns"))
            demand("countdown_remove_cancel_retains_exact_fixture",
                countdownIds() == addedIds && ownedCountdownIds() == listOf(fixtureId))
            tapAssociated("Remove", fixtureRow, verticalMargin = dp(32))
            waitLabel("Remove countdown?")
            demand("countdown_confirm_remove_targets_owned_fixture",
                has("Remove “$countdownName” from your countdowns?") &&
                    ownedCountdownIds() == listOf(fixtureId))
            tap("Remove")
            poll("countdown_remove_restores_original_collection") { countdownIds() == initialIds }
            ownedCountdownId = null
            countdownAddStarted = false
            report.getJSONObject("countdownCounts").put("afterRemoval", countdownIds().size)
            pruneOwnedEmptyKeys()
            checkWorkspace(tool("countdowns"))
            demand("countdown_original_config_restored", fingerprint("immortal_countdown") == initialHash)
            notCovered.remove(COUNTDOWN_GAP)
        }

        private fun lamp() {
            val originalBrightness = requireControlPrefix("Set brightness, ").bounds
            val brightnessLabel = allowedDescriptionPrefix("Set brightness, ")
            tapBounds(originalBrightness); waitLabel("Percent")
            setText("Percent", "5"); tap("Cancel"); checkWorkspace(tool("lamp"))
            demand("lamp_numeric_cancel_preserves_brightness", has(brightnessLabel))
            val redWasOn = has("Red lamp color")
            tap("Red night light"); lampRedChanged = true
            demand("lamp_red_gates_warmth", if (redWasOn) has("Warmth") else !has("Warmth"))
            tap("Red night light"); lampRedChanged = false
            demand("lamp_red_restored", has("Red lamp color") == redWasOn)
            enterFullscreen(LampActivity::class.java, "Turn on lamp", "Lamp display") {
                screenshot("lamp-display")
                tap("Back to Lamp controls")
            }
            checkWorkspace(tool("lamp"))
            demand("lamp_native_back_preserves_values", has(brightnessLabel) && has("Red lamp color") == redWasOn)
        }

        private fun cameras() {
            val initialIds = cameraIds()
            cameraBaseline = initialIds
            val initialHash = fingerprint("immortal_cameras")
            tap("Add camera"); waitLabel("Stream address")
            setText("Camera name (optional)", cameraName)
            setText("Stream address", "https://invalid.example.invalid/feed")
            tap("Add camera")
            waitLabel("Use a camera stream address beginning with rtsp:// and a valid camera address.")
            demand("camera_invalid_rtsp_not_saved", cameraIds() == initialIds)
            tap("Cancel"); checkWorkspace(tool("camera"))
            demand("camera_add_cancel_preserves_config", fingerprint("immortal_cameras") == initialHash)
            tap("Add camera"); waitLabel("Stream address")
            setText("Camera name (optional)", cameraName)
            // IPv4 loopback, closed low port: no household camera/host is contacted.
            setText("Stream address", "rtsp://127.0.0.1:1/tools-probe")
            cameraAddStarted = true
            tap("Add camera")
            poll("camera_owned_created") {
                discoverOwnedCamera()
                ownedCameraId != null
            }
            demand("camera_added_exactly_one", cameraIds().size == initialIds.size + 1)
            report.put("cameraCounts", JSONObject().put("before", initialIds.size).put("afterAdd", initialIds.size + 1))
            enterFullscreen(CameraViewerActivity::class.java, "View $cameraName camera", cameraName) {
                waitLabel("Camera unavailable", timeout = 12_000L)
                screenshot("camera-unavailable")
                tap("Back to cameras")
            }
            checkWorkspace(tool("camera"))
            tap("Remove $cameraName"); waitLabel("Remove camera?")
            tap("Cancel")
            demand("camera_remove_cancel_keeps_owned", ownedCameraId?.let { it in cameraIds() } == true)
            tap("Remove $cameraName"); waitLabel("Remove camera?"); tap("Remove")
            poll("camera_removed_original_ids_preserved") { cameraIds() == initialIds }
            ownedCameraId = null
            cameraAddStarted = false
            report.getJSONObject("cameraCounts").put("afterRemoval", cameraIds().size)
            pruneOwnedEmptyKeys()
            demand("camera_original_config_restored", fingerprint("immortal_cameras") == initialHash)
        }

        private fun stories() {
            tap("Read"); waitLabel("‹ All stories")
            checkWorkspace(tool("stories"), ready = false)
            demand("story_reader_has_explicit_return", has("‹ All stories"))
            demand("story_does_not_start_narration", !has("Stop reading"))
            screenshot("story-reader")
            tap("‹ All stories"); waitLabel("Pick a story")
            checkWorkspace(tool("stories"))
            demand("story_back_returns_to_picker", has("Read"))
        }

        private fun notes() {
            val initialHash = fingerprint("immortal_notes")
            setText("Note", syntheticNote)
            tapRail(tool("converter")); tapRail(tool("notes"))
            poll("note_draft_survives_revisit") { fieldEquals("Note", syntheticNote) }
            tap("Clear note"); waitLabel("Clear this note?")
            tap("Cancel"); checkWorkspace(tool("notes"))
            poll("note_clear_cancel_keeps_draft") { fieldEquals("Note", syntheticNote) }
            demand("note_saved_text_never_changed", fingerprint("immortal_notes") == initialHash)
            demand("note_audio_never_changed", audioFingerprint() == audioBefore)
            if (noteKeyExisted) {
                skips.put("note_save_clear", "original_text_note_key_present_draft_only")
            } else {
                demand("note_save_original_key_still_absent", !prefs("immortal_notes").contains("text_note"))
                noteSaveStarted = true
                tap("Save note")
                poll("note_save_persists_exact_synthetic_value") {
                    prefs("immortal_notes").getString("text_note", null) == syntheticNote
                }
                poll("note_save_native_status") { has("Saved") && !has("Unsaved changes") }
                val savedHash = fingerprint("immortal_notes")
                tap("Clear note"); waitLabel("Clear this note?")
                tap("Cancel"); checkWorkspace(tool("notes"))
                poll("note_clear_cancel_retains_persisted_synthetic_value") {
                    prefs("immortal_notes").getString("text_note", null) == syntheticNote &&
                        fieldEquals("Note", syntheticNote)
                }
                demand("note_clear_cancel_preserves_saved_config", fingerprint("immortal_notes") == savedHash)
                tap("Clear note"); waitLabel("Clear this note?")
                demand("note_confirm_clear_targets_owned_value",
                    prefs("immortal_notes").getString("text_note", null) == syntheticNote)
                tap("Clear note")
                poll("note_confirm_clear_restores_absent_key_and_empty_editor") {
                    !prefs("immortal_notes").contains("text_note") &&
                        hostIsActive() && fieldEquals("Note", "")
                }
                noteSaveStarted = false
                checkWorkspace(tool("notes"))
                demand("note_original_config_restored", fingerprint("immortal_notes") == initialHash)
                notCovered.remove(NOTE_GAP)
            }
            permissionCancel("notes")
            memo()
            // Any unsaved draft is discarded by finishing Home; cleanup never restores user text.
        }

        private fun memo() {
            if (!micBefore) {
                skips.put("note_memo_cycle", "microphone_not_previously_granted_no_recording")
                return
            }
            if (memoFileExisted) {
                skips.put("note_memo_cycle", "original_memo_file_present_including_empty_file_preserved")
                return
            }
            val file = NotesConfig.audioFile(context)
            if (file.exists()) {
                skips.put("note_memo_cycle", "memo_file_appeared_before_record_preserved")
                return
            }
            poll("memo_empty_native_record_ready") {
                has("Some things are easier to say.") &&
                    findControl("Record a memo", clickable = true)?.enabled == true
            }
            val record = requireControl("Record a memo")
            demand("memo_microphone_still_granted", microphoneGranted())
            demand("memo_original_file_still_absent", !file.exists())
            // Preparation can create the file before its callback: own it before the native tap.
            memoFixtureOwned = true
            tapBounds(record.bounds)
            poll("memo_native_recording_started") {
                has("Recording your memo…") && !has("Starting recording…") &&
                    findControl("Stop recording", clickable = true)?.enabled == true
            }
            val recordingObservedAt = SystemClock.uptimeMillis()
            poll("memo_short_recording_hold_elapsed", timeout = 1_600L) {
                SystemClock.uptimeMillis() - recordingObservedAt >= 1_100L
            }
            tap("Stop recording")
            poll("memo_native_stop_saved_and_waiting") {
                has("Voice memo saved.") && has("A voice memo is waiting.") &&
                    !has("Stop recording") && !has("Saving your voice memo…") &&
                    findControl("Play memo", clickable = true)?.enabled == true
            }
            demand("memo_private_file_created_nonempty", file.isFile && file.length() > 0L)
            val memoFingerprint = audioFingerprint()
            ownedMemoFingerprint = memoFingerprint
            tap("Play memo")
            poll("memo_native_playback_started") {
                has("Playing your voice memo.") && !has("Preparing your voice memo…") &&
                    findControl("Stop playback", clickable = true)?.enabled == true
            }
            // Stop promptly after observing actual playback of our short recording.
            tapBounds(requireControl("Stop playback").bounds, settleMs = 0)
            poll("memo_native_playback_stopped") {
                has("Playback stopped.") && has("A voice memo is waiting.") &&
                    !has("Stop playback") && !has("Playing your voice memo.") &&
                    findControl("Play memo", clickable = true)?.enabled == true
            }
            demand("memo_play_stop_preserves_exact_synthetic_sha256", audioFingerprint() == memoFingerprint)
            tap("Delete memo"); waitLabel("Delete voice memo?")
            tap("Cancel"); checkWorkspace(tool("notes"))
            demand("memo_delete_cancel_preserves_exact_synthetic_sha256",
                file.isFile && file.length() > 0L && audioFingerprint() == memoFingerprint)
            tap("Delete memo"); waitLabel("Delete voice memo?")
            demand("memo_confirm_delete_targets_owned_file",
                memoFixtureOwned && !memoFileExisted && audioFingerprint() == memoFingerprint)
            tap("Delete memo")
            poll("memo_confirm_delete_file_absent_and_record_ready") {
                !file.exists() && has("Voice memo deleted.") &&
                    has("Some things are easier to say.") &&
                    findControl("Record a memo", clickable = true)?.enabled == true
            }
            demand("memo_original_audio_fingerprint_restored", audioFingerprint() == audioBefore)
            checkWorkspace(tool("notes"))
            // Keep ownership until final host disposal/barrier also verifies no late file recreation.
            notCovered.remove(MEMO_GAP)
            if (options.getString("toolsCaptureNotes") == "true") {
                if (!noteKeyExisted && !memoFileExisted &&
                    !prefs("immortal_notes").contains("text_note") && fieldEquals("Note", "") &&
                    !file.exists() && audioFingerprint() == audioBefore) {
                    screenshot("notes-memo-deleted")
                } else {
                    skips.put("note_workspace_capture", "original_or_current_private_content_preserved")
                }
            }
        }

        private fun intercom() {
            setText("Other Portal's address", "")
            tap("Listen"); waitLabel("Enter the other Portal's IP address or hostname.")
            demand("intercom_blank_address_is_local_error", !has("Stop listening"))
            setText("Other Portal's address", "invalid/address")
            tap("Listen"); waitLabel("Enter the other Portal's IP address or hostname.")
            demand("intercom_invalid_address_does_not_connect", !has("Stop listening"))
            demand("intercom_microphone_stays_idle", has("Microphone idle") && !has("Stop broadcasting"))
            permissionCancel("intercom")
            setText("Other Portal's address", "")
        }

        private fun permissionCancel(id: String) {
            if (microphoneGranted()) {
                skips.put("${id}_permission_cancel", "already_granted_no_microphone_action")
                return
            }
            val control = findControl("Allow microphone", clickable = true)
            if (control == null) {
                skips.put("${id}_permission_cancel", "prompt_not_available_no_permission_injection")
                return
            }
            tapBounds(control.bounds)
            // A prior "don't ask again" may return directly. Never grant, deny or alter flags.
            val stop = SystemClock.uptimeMillis() + 2_000L
            var prompt = false
            while (SystemClock.uptimeMillis() < stop) {
                bounded()
                val windows = automation.windows
                try {
                    prompt = windows.any { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        it.id != hostWindow }
                } finally { windows.forEach { it.recycle() } }
                if (prompt) break
                SystemClock.sleep(POLL_MS)
            }
            if (prompt) {
                // BACK cancels the system prompt; it does not approve or reject a permission button.
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                poll("${id}_permission_prompt_cancelled") { hostIsActive() }
            } else skips.put("${id}_permission_prompt", "os_did_not_show_prompt")
            waitLabel(if (id == "notes") "Microphone access is needed to record a voice memo."
                else "Microphone access is needed to broadcast this room.")
            demand("${id}_permission_remains_ungranted", !microphoneGranted())
            demand("${id}_permission_cancel_does_not_start_audio",
                !has("Stop recording") && !has("Stop broadcasting"))
            checkWorkspace(tool(id))
        }

        private fun speed() {
            val speed = Tool("speed", "Speed test", "Connection")
            try {
                if (!advancedBefore) {
                    // Observe the category row before enabling, even with retained library scroll.
                    seek { findControl("All tools", region = libraryRegion()) != null }
                    demand("advanced_category_hidden", !has("Advanced"))
                    if (options.getString("toolsEnableAdvanced") != "true") {
                        skips.put("speed_cancel", "advanced_disabled_requires_toolsEnableAdvanced_true")
                        return
                    }
                    // Set ownership before apply so finally also repairs an interrupted apply.
                    advancedFixtureOwned = true
                    val applied = SettingsDomains.immortal.apply(context,
                        JSONObject().put("advancedSettings", true))
                    demand("advanced_fixture_applied_through_registry", applied == setOf("advancedSettings"))
                    poll("advanced_category_visible_after_registry_apply") {
                        ImmortalSettings.advancedSettings(context) && has("Advanced")
                    }
                }
                if (options.getString("toolsSpeedCancel") != "true") {
                    skips.put("speed_cancel", "requires_toolsSpeedCancel_true")
                    return
                }
                openLibrary(speed)
                checkWorkspace(speed)
                poll("speed_initial_ready_without_transfer") {
                    has("Ready to test") && findControl("Start test", clickable = true)?.enabled == true &&
                        !has("Cancel test")
                }
                fun cancelRun(startLabel: String, assertion: String) {
                    tap(startLabel)
                    poll("${assertion}_native_running", timeout = 2_000L) {
                        findControl("Cancel test", clickable = true)?.enabled == true && has("Run again") &&
                            (has("Measuring latency…") || has("Testing download…") || has("Testing upload…"))
                    }
                    tap("Cancel test")
                    poll("${assertion}_native_cancelled") {
                        !has("Cancel test") && has("Test stopped or incomplete") &&
                            findControl("Run again", clickable = true)?.enabled == true &&
                            has("The test was stopped. Run it again when you’re ready.")
                    }
                }
                cancelRun("Start test", "speed_start_cancel")
                demand("speed_cancel_stops_trial", !has("Cancel test") && has("Run again"))
                cancelRun("Run again", "speed_run_again_cancel")
                checkWorkspace(speed)
                returnLibrary()
                checkLibrary(speed)
                demand("speed_native_cancel_returns_tools_root", ownedWorkspace == null && hostIsActive())
                notCovered.remove(SPEED_GAP)
            } finally {
                try {
                    // Returning disposes/cancels even if a response failed before Cancel appeared.
                    if (ownedWorkspace == "speed") returnLibrary()
                } finally {
                    restoreAdvancedFixture()
                }
            }
        }

        private fun performance() {
            stage = "performance_warmup"
            val bounds = linkedMapOf<String, Rect>()
            openLibrary(tool("alarm"))
            // Warm the library and each body once, then retain rail bounds outside timing.
            localTools.forEach { id -> tapRail(tool(id)); checkWorkspace(tool(id)) }
            demand("performance_converter_is_local",
                run {
                    tapRail(tool("converter"))
                    has("Calculated on the device. No internet needed.")
                })
            tapRail(tool("lamp"))
            localTools.forEach { id ->
                bounds[id] = requireControl(tool(id).title, clickable = false, region = railRegion()).bounds
            }
            demand("performance_cached_rail_controls", bounds.size == 4)
            val injection = JSONArray()
            val trials = JSONArray()
            report.put("frameTrials", trials).put("clickInjectionDurations", injection)
            repeat(3) { trial ->
                stage = "performance_trial_${trial + 1}"
                SystemClock.sleep(400)
                shell("gfxinfo ${context.packageName} reset")
                val trialStart = SystemClock.uptimeMillis()
                var cycles = 0
                var switches = 0
                var lastTool = "lamp"
                // 160 switches normally fill Android's ~120-frame history. Time and input count
                // remain capped on slower hardware; insufficient history is reported, never invented.
                trialLoop@ while (cycles < 40 && SystemClock.uptimeMillis() - trialStart < TRIAL_MS) {
                    for ((id, rect) in bounds) {
                        if (SystemClock.uptimeMillis() - trialStart >= TRIAL_MS) break@trialLoop
                        bounded()
                        injection.put(tapBounds(rect, settleMs = 120, measured = true)
                            .put("trial", trial + 1).put("tool", id))
                        ownedWorkspace = id
                        switches++
                        lastTool = id
                    }
                    cycles++
                }
                val elapsed = SystemClock.uptimeMillis() - trialStart
                // No accessible tree/readiness polling between reset and this raw frame capture.
                val raw = shell("gfxinfo ${context.packageName} framestats")
                privateText("trial-${trial + 1}-gfxinfo.txt", raw)
                val frames = parseFrames(raw)
                trials.put(frames.json().put("trial", trial + 1).put("switches", switches)
                    .put("loopElapsedMs", elapsed).put("minimumFrameTarget", 120)
                    .put("minimumFrameTargetMet", frames.durationsMs.size >= 120))
                demand("trial_${trial + 1}_has_valid_frames", frames.durationsMs.isNotEmpty())
                checks.put("trial_${trial + 1}_at_least_120_frames", frames.durationsMs.size >= 120)
                if (lastTool != "lamp") tapBounds(bounds.getValue("lamp"))
                ownedWorkspace = "lamp"
                checkWorkspace(tool("lamp"))
            }
            report.put("timedLoopHierarchyReads", 0)
                .put("trialSafety", "UiAutomation.windows identity/layer/overlap guard for every click")
                .put("trialBoundMs", TRIAL_MS).put("trialMaximumSwitches", 160)
                .put("frameHistoryLimitation", "gfxinfo may retain fewer than 120 valid frames; flags/invalid rows excluded")
            stage = "performance_memory"
            val memory = JSONArray()
            report.put("fiveCycleMemoryBlocks", memory)
            val beforePss = pss()
            var lastPss = beforePss
            repeat(5) { block ->
                repeat(5) { bounds.values.forEach { tapBounds(it, settleMs = 120) } }
                lastPss = pss()
                memory.put(JSONObject().put("block", block + 1).put("cyclesInBlock", 5)
                    .put("completedCycles", (block + 1) * 5).put("pssKb", lastPss))
                checkWorkspace(tool("lamp"))
            }
            report.put("pssBeforeKb", beforePss).put("pssAfterKb", lastPss)
                .put("pssDeltaKb", lastPss - beforePss).put("fiveCycleMemoryBlocks", memory)
                .put("pssDefinition", "Debug.getMemoryInfo totalPss for instrumented target process; includes test overhead; no forced GC")
            stage = "performance_idle"
            SystemClock.sleep(500)
            shell("gfxinfo ${context.packageName} reset")
            val idleStart = SystemClock.uptimeMillis()
            SystemClock.sleep(2_000)
            val idleRaw = shell("gfxinfo ${context.packageName} framestats")
            privateText("idle-gfxinfo.txt", idleRaw)
            val idle = parseFrames(idleRaw)
            demand("idle_gfxinfo_schema_present", idle.tables > 0)
            report.put("idle", idle.json().put("noInteractionMs", SystemClock.uptimeMillis() - idleStart))
            checks.put("idle_no_rendered_frames", idle.durationsMs.isEmpty() && idle.flagged == 0)
            checkWorkspace(tool("lamp"))
            screenshot("performance-final")
            demand("completed", true)
        }

        private fun pss(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

        private fun checkWorkspace(tool: Tool, ready: Boolean = true) {
            poll("${tool.id}_same_window") { withRoot(::hostWindowMatches) }
            checkChrome()
            val region = workspaceRegion()
            val title = requireControl(tool.title, clickable = false, region = region)
            val original = headingBounds
            if (original == null) headingBounds = Rect(title.bounds)
            else poll("${tool.id}_fixed_workspace_heading") {
                val observed = findControl(tool.title, region = region)
                lookupDiagnostics.put("expectedBounds", boundsJson(original))
                observed?.bounds?.let { it.top == original.top && it.left == original.left } == true
            }
            // Android exposes a selected Role.Tab without ACTION_CLICK: it is already active.
            val selected = requireControl(tool.title, clickable = false, region = railRegion())
            demand("${tool.id}_selected_rail", selected.selected)
            demand("${tool.id}_one_workspace_heading", countLabels(tool.title, region, staticOnly = true) == 1)
            demand("${tool.id}_bottom_dock_return_present",
                findControl("Tools", clickable = false, preferBottom = true)?.bounds == chrome.getValue("Tools"))
            if (ready) {
                seek { has(tool.ready) }
                waitLabel(tool.ready)
            }
        }

        private fun checkChrome() {
            chrome.forEach { (label, rect) ->
                poll("chrome_${label.replace(' ', '_')}_retained") {
                    val actual = findControl(label, clickable = label != "immortal" && label != "Tools",
                        preferBottom = label == "Tools")
                    lookupDiagnostics.put("label", label).put("expectedBounds", boundsJson(rect))
                        .put("observedBounds", boundsJson(actual?.bounds))
                        .put("controlPresent", actual != null)
                    actual?.bounds == rect
                }
            }
        }

        private fun checkLibrary(tool: Tool) {
            poll("${tool.id}_back_same_window") { withRoot(::hostWindowMatches) }
            checkChrome()
            waitLibrary("${tool.id}_bottom_dock_return_to_library")
            poll("${tool.id}_library_scroll_retained") {
                findControl(tool.title, clickable = true, region = libraryRegion()) != null
            }
        }

        private fun openLibrary(tool: Tool) {
            // The list's scroll is retained. Reset by bounded native swipes, never config injection.
            if (ownedWorkspace != null) returnLibrary()
            repeat(7) {
                if (findControl(tool.title, clickable = true, region = libraryRegion()) != null) {
                    val at = SystemClock.uptimeMillis()
                    tap(tool.title, region = libraryRegion())
                    ownedWorkspace = tool.id
                    waitLabel(tool.ready)
                    demand("${tool.id}_discoverable_in_library", true)
                    readiness.put(JSONObject().put("tool", tool.id)
                        .put("observedMs", SystemClock.uptimeMillis() - at))
                    return
                }
                swipe(libraryRegion(), upward = true)
            }
            // The previous entry may leave the library near its bottom.
            repeat(7) {
                swipe(libraryRegion(), upward = false)
                if (findControl(tool.title, clickable = true, region = libraryRegion()) != null) {
                    val at = SystemClock.uptimeMillis()
                    tap(tool.title, region = libraryRegion())
                    ownedWorkspace = tool.id
                    waitLabel(tool.ready)
                    demand("${tool.id}_discoverable_in_library", true)
                    readiness.put(JSONObject().put("tool", tool.id)
                        .put("observedMs", SystemClock.uptimeMillis() - at))
                    return
                }
            }
            demand("${tool.id}_discoverable_in_library", false)
        }

        private fun tapRail(tool: Tool) {
            val region = railRegion()
            if (findControl(tool.title, clickable = false, region = region)?.selected != true)
                tap(tool.title, region = region)
            ownedWorkspace = tool.id
            waitLabel(tool.ready)
            poll("${tool.id}_rail_same_window") { withRoot(::hostWindowMatches) }
        }

        private fun returnLibrary() {
            val origin = ownedWorkspace ?: "library"
            tapBottomTools()
            waitLibrary("${origin}_bottom_dock_return_to_library")
            checkChrome()
        }

        private fun tapBottomTools() {
            val dock = Rect(screen.left, chrome.getValue("Home").top, screen.right, screen.bottom)
            tapBounds(requireControl("Tools", clickable = false, preferBottom = true, region = dock).bounds)
        }

        private fun waitLibrary(assertion: String) {
            poll(assertion) {
                withRoot(::hostWindowMatches) &&
                    (has("Small things that make the day easier.") ||
                        // The lazy heading may be offscreen when library scroll is retained.
                        // A standalone chevron belongs to library tiles, not workspace selectors.
                        findControl("›", clickable = true, region = libraryRegion()) != null)
            }
            ownedWorkspace = null
        }

        private fun dp(value: Int) = kotlin.math.round(value * context.resources.displayMetrics.density).toInt()

        private fun railRegion() = Rect(screen.left + dp(56), chrome.getValue("immortal").bottom,
            screen.left + dp(56 + 300), chrome.getValue("Home").top - dp(20))

        private fun workspaceRegion() = Rect(screen.left + dp(56 + 300 + 40),
            chrome.getValue("immortal").bottom, screen.right, chrome.getValue("Home").top)

        private fun libraryRegion() = Rect(screen.left + screen.width() / 12,
            chrome.getValue("immortal").bottom + 45, screen.right - screen.width() / 12,
            chrome.getValue("Home").top - 24)

        private fun tool(id: String) = ordinary.first { it.id == id }

        private fun enterFullscreen(
            activityClass: Class<out Activity>, label: String, heading: String, action: (Activity) -> Unit,
        ) {
            val monitor = instrumentation.addMonitor(activityClass.name, null, false)
            monitors += monitor
            tap(label)
            poll("fullscreen_${stage}_launched") { monitor.lastActivity != null }
            val activity = checkNotNull(monitor.lastActivity)
            fullscreen += activity
            waitLabel(heading)
            withRoot { demand("${stage}_explicit_fullscreen_window", it.windowId != hostWindow &&
                it.packageName?.toString() == context.packageName) }
            action(activity)
            poll("${stage}_fullscreen_return") { hostIsActive() }
        }

        private fun setText(label: String, value: String) {
            seek { findControl(label, editable = true) != null }
            withRoot { root ->
                var done = false
                walk(root) { node, ancestor, _ ->
                    val field = if (node.isEditable) node else ancestor?.takeIf { it.isEditable }
                    if (!done && field != null && node.isVisibleToUser && matches(node, label)) {
                        done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                        })
                    }
                }
                demand("${stage}_set_${label.replace(' ', '_')}", done)
            }
            SystemClock.sleep(100)
        }

        private fun fieldEquals(label: String, synthetic: String): Boolean = withRoot { root ->
            var equal = false
            walk(root) { node, ancestor, _ ->
                val field = if (node.isEditable) node else ancestor?.takeIf { it.isEditable }
                if (field != null && node.isVisibleToUser && matches(node, label))
                    equal = field.text?.toString() == synthetic
            }
            equal
        }

        /** Compose omits plain layout containers; pair the caption with its selector by bounds. */
        private fun openChoice(title: String) {
            check(title == "Minute" || title == "Icon")
            var chosen: Rect? = null
            seek {
                val caption = findControl(title)?.bounds ?: return@seek false
                val tolerance = context.resources.displayMetrics.density * 12
                val candidates = withRoot { root ->
                    val controls = mutableSetOf<Rect>()
                    walk(root) { node, ancestor, _ ->
                        if (!node.isVisibleToUser || node.isEditable) return@walk
                        val text = node.text?.toString() ?: return@walk
                        if (!text.endsWith("  ›")) return@walk
                        val control = if (node.isClickable) node else ancestor ?: return@walk
                        if (!control.isEnabled) return@walk
                        val rect = Rect().also(control::getBoundsInScreen)
                        if (kotlin.math.abs(rect.left - caption.left) <= tolerance &&
                            rect.top >= caption.bottom - tolerance &&
                            rect.top - caption.bottom <= 100 * context.resources.displayMetrics.density)
                            controls += rect
                    }
                    controls
                }
                if (candidates.size == 1) chosen = candidates.single()
                chosen != null
            }
            demand("${stage}_${title.lowercase(Locale.ROOT)}_selector_unique", chosen != null)
            tapBounds(checkNotNull(chosen))
        }

        /** Only a unique owned marker and its same-row action can be touched. */
        private fun tapAssociated(
            label: String, marker: (AccessibilityNodeInfo) -> Boolean, verticalMargin: Int = 0,
        ) {
            var chosen: Rect? = null
            seek {
                withRoot { root ->
                    val markers = mutableSetOf<Rect>()
                    val buttons = mutableSetOf<Rect>()
                    walk(root) { node, ancestor, _ ->
                        if (!node.isVisibleToUser || node.isEditable) return@walk
                        if (marker(node)) {
                            val rect = Rect().also(node::getBoundsInScreen)
                            if (!rect.isEmpty) markers += rect
                        }
                        if (matches(node, label)) {
                            val control = if (node.isClickable) node else ancestor ?: return@walk
                            if (control.isEnabled) buttons += Rect().also(control::getBoundsInScreen)
                        }
                    }
                    if (markers.size == 1) {
                        val clock = markers.single()
                        val candidates = buttons.filter { button ->
                            button.left >= clock.right &&
                                button.centerY() in (clock.top - verticalMargin)..(clock.bottom + verticalMargin)
                        }
                        if (candidates.size == 1) chosen = candidates.single()
                    }
                    chosen != null
                }
            }
            demand("${stage}_associated_control_unique", chosen != null)
            tapBounds(checkNotNull(chosen))
        }

        private fun tap(
            label: String, region: Rect? = null,
        ) {
            seek { findControl(label, clickable = true, region = region)?.enabled == true }
            tapBounds(requireControl(label, region = region).bounds)
        }

        private fun tapPrefix(prefix: String) {
            seek { findControl(prefix, prefix = true, clickable = true)?.enabled == true }
            tapBounds(requireControlPrefix(prefix).bounds)
        }

        private fun requireControlPrefix(prefix: String): Control {
            var found: Control? = null
            poll("${stage}_control_${labelKey(prefix)}_prefix_present") {
                found = findControl(prefix, prefix = true, clickable = true)
                found != null
            }
            return checkNotNull(found)
        }

        private fun allowedDescriptionPrefix(prefix: String): String = withRoot { root ->
            var result: String? = null
            walk(root) { node, _, _ ->
                val description = node.contentDescription?.toString()
                if (description?.startsWith(prefix) == true) result = description
            }
            // Only brightness/warmth percentages may be retained this way.
            check(prefix == "Set brightness, " || prefix == "Set warmth, ")
            checkNotNull(result)
        }

        private fun allowedNumericDescription(title: String): String = withRoot { root ->
            check(title in listOf("Month", "Day", "Year"))
            var result: String? = null
            walk(root) { node, _, _ ->
                val description = node.contentDescription?.toString()
                if (description?.matches(Regex("$title, [0-9]+")) == true) result = description
            }
            checkNotNull(result)
        }

        private fun requireControl(
            label: String, clickable: Boolean = true, preferBottom: Boolean = false,
            enabled: Boolean = true, region: Rect? = null,
        ): Control {
            var found: Control? = null
            poll("${stage}_control_${labelKey(label)}_present") {
                found = findControl(label, clickable, preferBottom, region = region)
                found?.let { !enabled || it.enabled } == true
            }
            // Return the successful observation, not another potentially stale tree read.
            return checkNotNull(found)
        }

        private fun labelKey(label: String) =
            label.replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').take(100)

        private fun boundsJson(rect: Rect?): Any = if (rect == null) JSONObject.NULL
            else JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

        private fun hostWindowMatches(root: AccessibilityNodeInfo): Boolean {
            lookupDiagnostics = JSONObject().put("expectedHostWindowId", hostWindow)
                .put("rootWindowId", root.windowId).put("expectedBounds", boundsJson(screen))
                .put("observedBounds", boundsJson(screenOrRoot(root)))
            return root.windowId == hostWindow
        }

        private fun touchable(node: AccessibilityNodeInfo) = node.isClickable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

        private fun findControl(
            label: String, clickable: Boolean = false, preferBottom: Boolean = false,
            prefix: Boolean = false, editable: Boolean = false, region: Rect? = null,
        ): Control? = try { withRoot { root ->
            step = "${stage}:lookup:${labelKey(label)}"
            val results = mutableListOf<Control>()
            walk(root) { node, clickableAncestor, inheritedSelection ->
                if (!node.isVisibleToUser || !matches(node, label, prefix))
                    return@walk
                val control = if (editable) {
                    if (node.isEditable) node else clickableAncestor?.takeIf { it.isEditable } ?: return@walk
                } else if (clickable) {
                    if (touchable(node)) node else clickableAncestor ?: return@walk
                } else node
                // clickable=false means clickability is not required. Merged clickable
                // nodes may carry a static caption; excluding them hides valid labels.
                val rect = Rect().also(control::getBoundsInScreen)
                if (rect.isEmpty || !screenOrRoot(root).contains(rect.centerX(), rect.centerY())) return@walk
                if (region != null && !region.contains(rect.centerX(), rect.centerY())) return@walk
                results += Control(rect, control.isEnabled, control.isSelected || inheritedSelection)
            }
            val found = if (preferBottom) results.maxByOrNull { it.bounds.centerY() }
            else results.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            lookupDiagnostics = JSONObject().put("label", label).put("clickRequired", clickable)
                .put("editableRequired", editable).put("rootWindowId", root.windowId)
                .put("expectedHostWindowId", hostWindow).put("rootBounds", boundsJson(screenOrRoot(root)))
                .put("searchRegion", boundsJson(region)).put("controlPresent", found != null)
                .put("observedBounds", boundsJson(found?.bounds))
            found
        } } catch (_: UiUnavailable) {
            lookupDiagnostics = JSONObject().put("label", label).put("rootUnavailable", true)
            null
        }

        private fun matches(node: AccessibilityNodeInfo, label: String, prefix: Boolean = false): Boolean {
            fun match(value: CharSequence?): Boolean {
                val text = value?.toString() ?: return false
                return if (prefix) text.startsWith(label) else text == label ||
                    text.split('\n').any { it == label }
            }
            // Never inspect an editable household value to discover a control.
            return match(node.contentDescription) || (!node.isEditable && match(node.text))
        }

        private fun countLabels(label: String, region: Rect, staticOnly: Boolean): Int = withRoot { root ->
            var count = 0
            walk(root) { node, clickable, _ ->
                if (node.isVisibleToUser && matches(node, label) &&
                    (!staticOnly || (!node.isClickable && clickable == null))) {
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (region.contains(rect.centerX(), rect.centerY())) count++
                }
            }
            count
        }

        private fun has(label: String) = findControl(label) != null || findControl(label, clickable = true) != null
        private fun hasPrefix(prefix: String) = findControl(prefix, prefix = true) != null ||
            findControl(prefix, clickable = true, prefix = true) != null
        private fun waitLabel(label: String, timeout: Long = WAIT_MS) =
            poll("${stage}_accessible_heading", timeout) { has(label) }
        private fun waitPrefix(prefix: String) = poll("${stage}_accessible_control") { hasPrefix(prefix) }

        private fun seek(found: () -> Boolean) {
            if (found()) return
            // Let storage/recomposition settle before deciding a control needs scrolling.
            repeat(6) { SystemClock.sleep(POLL_MS); if (found()) return }
            repeat(5) {
                swipe(activeBodyRegion(), upward = true)
                if (found()) return
            }
            repeat(5) {
                swipe(activeBodyRegion(), upward = false)
                if (found()) return
            }
            demand("${stage}_control_reachable", false)
        }

        private fun activeBodyRegion(): Rect = withRoot { root ->
            val bounds = Rect().also(root::getBoundsInScreen)
            if (root.windowId == hostWindow && ownedWorkspace != null) workspaceRegion().apply {
                top += (headingBounds?.height() ?: 60) + 160
            } else Rect(bounds.left + bounds.width() / 6, bounds.top + bounds.height() / 4,
                bounds.right - bounds.width() / 6, bounds.bottom - bounds.height() / 4)
        }

        private fun screenOrRoot(root: AccessibilityNodeInfo) = Rect().also(root::getBoundsInScreen)

        /** Tree traversal is functional/setup-only; all acquired children are recycled. */
        private fun walk(
            root: AccessibilityNodeInfo,
            visitor: (AccessibilityNodeInfo, AccessibilityNodeInfo?, Boolean) -> Unit,
        ) {
            var visited = 0
            fun visit(
                node: AccessibilityNodeInfo,
                parentControl: AccessibilityNodeInfo?,
                parentSelected: Boolean,
                depth: Int,
            ) {
                if (depth > 55 || ++visited > MAX_NODES) return
                // Selected Compose tabs expose state on a non-clickable parent of their label.
                val selected = parentSelected || node.isSelected
                visitor(node, parentControl, selected)
                val nearest = if (touchable(node)) node else parentControl
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { visit(child, nearest, selected, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, null, false, 0)
        }

        private fun <T> withRoot(action: (AccessibilityNodeInfo) -> T): T {
            bounded()
            val root = automation.rootInActiveWindow ?: throw UiUnavailable()
            try {
                // The system permission prompt is observed via windows, never dumped/traversed.
                if (root.packageName?.toString() != context.packageName) throw UiUnavailable()
                return action(root)
            } finally { root.recycle() }
        }

        private fun tapBounds(rect: Rect, settleMs: Long = 180, measured: Boolean = false): JSONObject {
            bounded()
            val guardAt = SystemClock.elapsedRealtimeNanos()
            guardWindow(rect, strictHost = measured || command == "toolsPerformance")
            val guardMs = (SystemClock.elapsedRealtimeNanos() - guardAt) / 1_000_000.0
            val downTime = SystemClock.uptimeMillis()
            val sequenceAt = SystemClock.elapsedRealtimeNanos()
            val downMs = inject(MotionEvent.ACTION_DOWN, downTime, rect.exactCenterX(), rect.exactCenterY())
            val upMs = try {
                SystemClock.sleep(32)
                inject(MotionEvent.ACTION_UP, downTime, rect.exactCenterX(), rect.exactCenterY())
            } catch (error: Throwable) {
                runCatching { inject(MotionEvent.ACTION_CANCEL, downTime, rect.exactCenterX(), rect.exactCenterY()) }
                throw error
            }
            val sequenceMs = (SystemClock.elapsedRealtimeNanos() - sequenceAt) / 1_000_000.0
            SystemClock.sleep(settleMs)
            return JSONObject().put("windowGuardMs", guardMs).put("injectDownCallMs", downMs)
                .put("injectUpCallMs", upMs)
                .put("pointerSequenceMs", sequenceMs).put("requestedHoldMs", 32)
                .put("requestedSettleMs", settleMs)
        }

        private fun inject(action: Int, down: Long, x: Float, y: Float): Double {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            val at = SystemClock.elapsedRealtimeNanos()
            try {
                if (!automation.injectInputEvent(event, true)) demand("${stage}_touch_injection_accepted", false)
                return (SystemClock.elapsedRealtimeNanos() - at) / 1_000_000.0
            } finally { event.recycle() }
        }

        private fun swipe(rect: Rect, upward: Boolean) {
            bounded()
            guardWindow(rect, strictHost = command == "toolsPerformance")
            val x = rect.exactCenterX()
            val y0 = rect.top + rect.height() * if (upward) .80f else .25f
            val y1 = rect.top + rect.height() * if (upward) .25f else .80f
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, down, x, y0)
            try {
                repeat(18) { step ->
                    inject(MotionEvent.ACTION_MOVE, down, x, y0 + (y1 - y0) * (step + 1) / 18)
                    SystemClock.sleep(16)
                }
                inject(MotionEvent.ACTION_UP, down, x, y1)
            } catch (error: Throwable) {
                runCatching { inject(MotionEvent.ACTION_CANCEL, down, x, y1) }
                throw error
            }
            SystemClock.sleep(200)
        }

        private fun hostIsActive(): Boolean {
            val windows = automation.windows
            try { return windows.singleOrNull { it.isActive }?.id == hostWindow }
            finally { windows.forEach { it.recycle() } }
        }

        /**
         * No root/child traversal here. Initial package/bounds are trusted only while the
         * same application window is active and no new overlay can intercept the touch.
         * Functional dialogs/fullscreen are separately package-checked by withRoot.
         */
        private fun guardWindow(rect: Rect, strictHost: Boolean) {
            if (!strictHost) awaitFunctionalWindow(rect)
            val windows = automation.windows
            try {
                step = "${stage}:window_guard"
                val active = windows.singleOrNull { it.isActive }
                    ?: fail("${stage}_single_active_window")
                if (active.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (strictHost && active.id != hostWindow)) demand("${stage}_guard_window_identity", false)
                val activeBounds = Rect().also(active::getBoundsInScreen)
                lookupDiagnostics = JSONObject().put("activeWindowId", active.id)
                    .put("expectedHostWindowId", hostWindow).put("expectedBounds", boundsJson(rect))
                    .put("observedBounds", boundsJson(activeBounds))
                if (!activeBounds.contains(rect.centerX(), rect.centerY()))
                    demand("${stage}_touch_inside_active_window", false)
                if (!strictHost) withRoot {
                    if (it.windowId != active.id) demand("${stage}_root_matches_active_window", false)
                }
                windows.forEach { window ->
                    if (window.id == active.id) return@forEach
                    val bounds = Rect().also(window::getBoundsInScreen)
                    if (window.layer > active.layer && bounds.contains(rect.centerX(), rect.centerY()))
                        demand("${stage}_touch_unobstructed", false)
                    if (strictHost && !(window.id in systemWindows &&
                        window.type == AccessibilityWindowInfo.TYPE_SYSTEM && !window.isFocused))
                        demand("${stage}_guard_no_new_windows", false)
                }
            } finally { windows.forEach { it.recycle() } }
        }

        /** Android dialog captions appear before its animated window bounds have settled. */
        private fun awaitFunctionalWindow(rect: Rect) {
            var previousId = -1
            var previousBounds: Rect? = null
            poll("${stage}_functional_window_geometry_ready") {
                val windows = automation.windows
                try {
                    val active = windows.singleOrNull { it.isActive }
                    if (active == null || active.type != AccessibilityWindowInfo.TYPE_APPLICATION)
                        return@poll false
                    val bounds = Rect().also(active::getBoundsInScreen)
                    val contains = !bounds.isEmpty && bounds.contains(rect.centerX(), rect.centerY())
                    val sameRoot = contains && withRoot { it.windowId == active.id }
                    val stable = active.id == hostWindow ||
                        (active.id == previousId && bounds == previousBounds)
                    previousId = active.id
                    previousBounds = bounds
                    sameRoot && stable
                } finally { windows.forEach { it.recycle() } }
            }
        }

        private fun screenshot(name: String) {
            bounded()
            val bitmap = automation.takeScreenshot()
            demand("${name}_screenshot_captured", bitmap != null)
            bitmap ?: return
            try {
                File(folder, "$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                artifacts.put("$relative/$name.png")
            } finally { bitmap.recycle() }
        }

        private fun privateText(name: String, text: String) {
            File(folder, name).writeText(text)
            artifacts.put("$relative/$name")
        }

        private fun shell(service: String): String {
            bounded()
            check(context.packageName.matches(Regex("[a-zA-Z0-9_.]+")))
            // Android's own dumpsys timeout bounds its service call. Never invoke host adb/input.
            return automation.executeShellCommand("dumpsys -t 5 $service").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 2 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            }
        }

        private fun parseFrames(raw: String): Frames {
            val durations = mutableListOf<Double>()
            var flagged = 0
            var invalid = 0
            var tables = 0
            var header: List<String>? = null
            raw.lineSequence().forEach { line ->
                val cells = line.trim().split(',').map { it.trim() }
                if (cells.firstOrNull() == "Flags" && "IntendedVsync" in cells && "FrameCompleted" in cells) {
                    header = cells
                    tables++
                } else if (line.trim() == "---PROFILEDATA---") {
                    header = null
                } else {
                    val columns = header ?: return@forEach
                    if (cells.size < columns.size || cells.firstOrNull()?.toLongOrNull() == null) return@forEach
                    val flags = cells[columns.indexOf("Flags")].toLongOrNull()
                    val intended = cells[columns.indexOf("IntendedVsync")].toLongOrNull()
                    val completed = cells[columns.indexOf("FrameCompleted")].toLongOrNull()
                    when {
                        intended == null || completed == null || intended <= 0 || completed <= intended ||
                            intended == Long.MAX_VALUE || completed == Long.MAX_VALUE -> invalid++
                        flags == null -> invalid++
                        flags != 0L -> flagged++
                        else -> durations += (completed - intended) / 1_000_000.0
                    }
                }
            }
            return Frames(durations, flagged, invalid, tables)
        }

        private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        /** Preference contents go directly to SHA-256; no raw backup/report or user-text lookup. */
        private fun fingerprint(name: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }
            DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
                val values = prefs(name).all
                values.keys.sorted().forEach { key ->
                    val value = values[key]
                    out.writeUTF(key)
                    out.writeUTF(if (value is Set<*>) "set" else value?.javaClass?.name ?: "null")
                    val text = if (value is Set<*>) value.map { it.toString() }.sorted().joinToString("\u0000")
                        else value?.toString().orEmpty()
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun fingerprints() = configNames.associateWith(::fingerprint)

        private fun audioFingerprint(): String {
            val file = NotesConfig.audioFile(context)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(if (file.exists()) 1.toByte() else 0.toByte())
            if (file.exists()) file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Only collection IDs and numeric timer metadata are inspected, never names/URLs. */
        private fun timerMetadata(): List<TimerMeta> {
            val array = JSONArray(prefs("immortal_timers").getString("timers", "[]") ?: "[]")
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TimerMeta(item.getString("id"), item.getLong("dur"), item.getLong("end"))
            }
        }

        private fun cameraIds(): Set<Long> {
            val array = JSONArray(prefs("immortal_cameras").getString("cameras", "[]") ?: "[]")
            return (0 until array.length()).map { array.getJSONObject(it).getLong("id") }.toSet()
        }

        private fun countdownIds(): List<Long> {
            val array = JSONArray(prefs("immortal_countdown").getString("events", "[]") ?: "[]")
            return (0 until array.length()).map { array.getJSONObject(it).getLong("id") }
        }

        private fun ownedCountdownIds(): List<Long> {
            val array = JSONArray(prefs("immortal_countdown").getString("events", "[]") ?: "[]")
            return (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val id = item.getLong("id")
                // Exclude every baseline ID before comparing only our synthetic label.
                id.takeIf { id !in countdownBaseline && item.optString("label") == countdownName }
            }
        }

        private fun discoverOwnedCountdown() {
            if (!countdownAddStarted || ownedCountdownId != null) return
            val candidates = ownedCountdownIds()
            if (candidates.size == 1 && countdownIds().count { it == candidates.single() } == 1) {
                ownedCountdownId = candidates.single()
                countdownKeyOwned = true
            }
        }

        private fun discoverOwnedTimer() {
            if (timerStartAt == 0L || ownedTimer != null) return
            val candidates = timerMetadata().filter { it.id !in timerBaseline &&
                it.duration == timerDuration && it.end in
                (timerStartAt + timerDuration)..(timerStartAt + timerDuration + 5_000L) }
            if (candidates.size == 1) {
                ownedTimer = candidates.single()
                timerKeyOwned = true
            }
        }

        private fun discoverOwnedCamera() {
            if (!cameraAddStarted || ownedCameraId != null) return
            val array = JSONArray(prefs("immortal_cameras").getString("cameras", "[]") ?: "[]")
            val owned = (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val id = item.getLong("id")
                // Inspect synthetic fields only after excluding every original household ID.
                id.takeIf { id !in cameraBaseline && item.optString("name") == cameraName &&
                    item.optString("url") == "rtsp://127.0.0.1:1/tools-probe" }
            }
            if (owned.size == 1) {
                ownedCameraId = owned.single()
                cameraKeyOwned = true
            }
        }

        /** Remove only an empty key created by our add/remove cycle on an initially absent key. */
        private fun pruneOwnedEmptyKeys() {
            listOf(Triple("immortal_timers", "timers", timerKeyExisted),
                Triple("immortal_cameras", "cameras", cameraKeyExisted),
                Triple("immortal_countdown", "events", countdownKeyExisted)).forEach { (name, key, existed) ->
                val preferences = prefs(name)
                val owned = when (name) {
                    "immortal_timers" -> timerKeyOwned
                    "immortal_cameras" -> cameraKeyOwned
                    else -> countdownKeyOwned
                }
                if (owned && !existed && preferences.getString(key, null) == "[]") {
                    check(preferences.edit().remove(key).commit())
                }
            }
        }

        private fun restoreAdvancedFixture() {
            if (!advancedFixtureOwned) return
            val applied = SettingsDomains.immortal.apply(context,
                JSONObject().put("advancedSettings", advancedBefore))
            demand("advanced_fixture_restored_through_registry",
                applied == setOf("advancedSettings") &&
                    ImmortalSettings.advancedSettings(context) == advancedBefore)
            val preferences = prefs("immortal_settings")
            // The registry just restored false. Prune only this probe's added default key.
            if (!advancedKeyExisted && preferences.contains("advanced_settings") &&
                !preferences.getBoolean("advanced_settings", true)) {
                check(preferences.edit().remove("advanced_settings").commit())
            }
            demand("advanced_original_key_presence_restored",
                preferences.contains("advanced_settings") == advancedKeyExisted)
            advancedFixtureOwned = false
        }

        private fun microphoneGranted() =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        private fun awaitOwnedMemoHostDestroyed() {
            val activity = checkNotNull(host)
            val stop = SystemClock.uptimeMillis() + WAIT_MS
            var destroyed = false
            // Finish alone does not synchronously dispose Compose's AudioNote owner.
            // Bound recovery separately so it can still release audio after a probe deadline failure.
            while (!destroyed && SystemClock.uptimeMillis() < stop) {
                instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
                if (!destroyed) SystemClock.sleep(POLL_MS)
            }
            check(destroyed)
        }

        private fun awaitMemoAudioDrain() {
            val barrier = AudioNoteWork() // Default executor is shared with the UI's native owner.
            val completed = CountDownLatch(1)
            var drained = false
            try {
                // drain() consumes pending release/committed deletion before leaving its executor
                // task. Enqueue this read ONLY after host destruction has enqueued owner release.
                // read()/call() use unbounded Future.get(); readAsync permits a bounded wait.
                barrier.readAsync(false, { true }, { callback -> callback() }) { result ->
                    drained = result
                    completed.countDown()
                }
                check(completed.await(WAIT_MS, TimeUnit.MILLISECONDS) && drained)
            } finally {
                // A timed-out read never schedules deletion; retirement drops its late callback.
                barrier.close {}
            }
        }

        private fun cleanUp() {
            fun cleanupCheck(name: String, action: () -> Unit) {
                try { action(); checks.put(name, true) }
                catch (_: Throwable) { checks.put(name, false); failed = true }
            }
            cleanupCheck("owned_fullscreen_closed") {
                fullscreen.asReversed().forEach { activity ->
                    instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
                }
            }
            cleanupCheck("lamp_ephemeral_toggle_restored") {
                if (lampRedChanged && hostIsActive()) {
                    // Best effort before destroying the isolated host; never touches preferences.
                    findControl("Red night light", clickable = true)?.let { tapBounds(it.bounds) }
                }
                lampRedChanged = false
            }
            cleanupCheck("owned_host_closed_drafts_discarded") {
                host?.let { activity ->
                    instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
                }
            }
            cleanupCheck("owned_memo_audio_drained_and_removed") {
                if (memoFixtureOwned) {
                    check(!memoFileExisted)
                    awaitOwnedMemoHostDestroyed()
                    awaitMemoAudioDrain()
                    val file = NotesConfig.audioFile(context)
                    if (file.exists()) {
                        // Saved fixtures must still match exactly. A failed preparation/start
                        // has no saved hash; initial + pre-tap absence and ownership cover that file.
                        ownedMemoFingerprint?.let { check(audioFingerprint() == it) }
                        check(file.isFile && file.delete())
                    }
                    check(!file.exists())
                    memoFixtureOwned = false
                    ownedMemoFingerprint = null
                }
            }
            cleanupCheck("owned_timer_removed") {
                discoverOwnedTimer()
                ownedTimer?.let { TimerConfig.remove(context, it.id) }
                ownedTimer = null
            }
            cleanupCheck("owned_camera_removed") {
                discoverOwnedCamera()
                ownedCameraId?.let { CameraConfig.remove(context, it) }
                ownedCameraId = null
                cameraAddStarted = false
            }
            cleanupCheck("owned_countdown_removed") {
                discoverOwnedCountdown()
                if (countdownAddStarted) {
                    val candidates = ownedCountdownIds()
                    if (candidates.isNotEmpty()) {
                        // Refuse ambiguity or a changed ID/label instead of deleting another event.
                        check(candidates.size == 1 && candidates.single() == ownedCountdownId)
                        val id = candidates.single()
                        check(id !in countdownBaseline && countdownIds().count { it == id } == 1)
                        CountdownConfig.remove(context, id)
                        check(ownedCountdownIds().isEmpty())
                    }
                }
                ownedCountdownId = null
                countdownAddStarted = false
            }
            cleanupCheck("owned_note_removed") {
                if (!noteKeyExisted && noteSaveStarted &&
                    prefs("immortal_notes").getString("text_note", null) == syntheticNote) {
                    NotesConfig.clearText(context)
                    check(!prefs("immortal_notes").contains("text_note"))
                }
                noteSaveStarted = false
            }
            cleanupCheck("owned_advanced_fixture_restored") { restoreAdvancedFixture() }
            cleanupCheck("owned_empty_keys_restored") { if (before.isNotEmpty()) pruneOwnedEmptyKeys() }
            cleanupCheck("original_configuration_fingerprints_match") {
                val after = fingerprints()
                val result = JSONObject()
                before.forEach { (name, hash) ->
                    val same = after[name] == hash
                    result.put(name, JSONObject().put("beforeSha256", hash)
                        .put("afterSha256", after[name]).put("unchanged", same))
                    if (!same) failed = true
                }
                report.put("configurationFingerprints", result)
                check(before.isNotEmpty() && after == before)
            }
            cleanupCheck("original_audio_fingerprint_matches") {
                val after = audioFingerprint()
                report.put("audioFingerprint", JSONObject().put("beforeSha256", audioBefore)
                    .put("afterSha256", after).put("unchanged", after == audioBefore))
                check(after == audioBefore)
            }
            cleanupCheck("microphone_permission_preserved") { check(microphoneGranted() == micBefore) }
            cleanupCheck("advanced_preference_preserved") {
                check(ImmortalSettings.advancedSettings(context) == advancedBefore &&
                    prefs("immortal_settings").contains("advanced_settings") == advancedKeyExisted)
            }
            monitors.forEach { instrumentation.removeMonitor(it) }
        }

        private fun poll(name: String, timeout: Long = WAIT_MS, condition: () -> Boolean) {
            step = name
            val stop = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < stop) {
                bounded()
                val satisfied = try { condition() } catch (_: UiUnavailable) { false }
                if (satisfied) { checks.put(name, true); return }
                SystemClock.sleep(POLL_MS)
            }
            demand(name, false)
        }

        private fun demand(name: String, value: Boolean) {
            checks.put(name, value)
            if (!value) fail(name)
        }

        private fun fail(name: String): Nothing {
            checks.put(name, false)
            throw ProbeFailure(name)
        }

        private fun bounded() {
            if (SystemClock.uptimeMillis() >= deadline) demand("probe_deadline_180s", false)
        }
    }
}
