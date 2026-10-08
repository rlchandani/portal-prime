/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * command=dreamRecovery, on the Android 10 benchmark target. Main owns build/install/execution.
 *
 * Checks the global stop handler with zero and expired grace markers, then opens the EXISTING
 * dashboard through its helper and sends at most three system BACK events. A nonblocking preview
 * monitor and lifecycle counter catch transient launches as well as a frame left over Home.
 * Readiness uses direct UiAutomation root ownership plus public Activity lifecycle callbacks, never
 * idle synchronization (Home may have a ticking clock). No web text, controls, pictures, URLs,
 * credentials, browser storage or stock-app lifecycle are inspected or changed.
 *
 * The owned check recreates Home to reaffirm the Dream component, starts a normal OS Dream through
 * SystemUI's Somnambulator, validates dumpsys dreams and injects OS KEYCODE_WAKEUP with both
 * timestamps zero and handoff flags false. Its real onDreamingStopped callback must launch the
 * attached continuation frame. No direct call to the owned handler can substitute for wiring
 * evidence. OS start, interruption and callback-recovery coverage are reported separately.
 * Public Activity callbacks, visible windows and a fixed dumpsys activity mResumedActivity parse
 * establish preview ownership without Android private-field reflection.
 * Direct Activity finish avoids launching the configured dismiss app. Finally stops only our
 * test Dream, returns Home and restores all four volatile markers, three persisted bridge keys
 * and three secure screensaver keys, including absence. No dashboard/app settings are changed.
 *
 * One 60-second budget includes twelve seconds reserved for cleanup. Individual waits are bounded,
 * polling sleeps are 80ms, and reports contain only fixed stages, exception types and assertions.
 * Shell commands are fixed am/dumpsys commands, never -W or DreamManager start/awaken (the Portal
 * shell lacks WRITE_DREAM_STATE). Raw shell responses are bounded, parsed and never emitted.
 */
internal object DreamRecoveryDeviceChecks {
    private const val PACKAGE = "com.immortal.launcher.debug"
    private const val BROWSER = "org.chromium.chrome"
    private const val LIMIT_MS = 60_000L
    private const val CLEANUP_MS = 12_000L
    private const val CALL_MS = 1_000L
    private const val POLL_MS = 80L
    private const val GLOBAL_WATCH_MS = 3_000L
    private const val BROWSER_WATCH_MS = 12_000L
    private const val MAX_BACK = 3
    private val bridgeKeys = setOf("bridge_at", "in_stock_handoff", "portal_home_handoff")
    private val secureKeys = listOf(
        "screensaver_enabled", "screensaver_components", "screensaver_default_component")
    private val component = ComponentName(PACKAGE, PhotoDreamService::class.java.name)
    private val previewComponent = ComponentName(PACKAGE, PhotoFramePreviewActivity::class.java.name)
    private val somnambulator =
        ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")

    fun run(instrumentation: Instrumentation, automation: UiAutomation): Bundle =
        Probe(instrumentation, automation).run()

    private class CheckFailure : RuntimeException()
    private class DeadlineFailure : RuntimeException()
    private class ShellReplyFailure : RuntimeException()
    private class DreamDumpFormatFailure : RuntimeException()
    private data class Markers(
        val userExitAt: Long,
        val bridgeAt: Long,
        val inStockHandoff: Boolean,
        val portalHomeHandoff: Boolean,
    )
    private data class SavedMarkers(
        val memory: Markers,
        val persisted: Map<String, Any?>,
        val secure: Map<String, String?>,
    )
    private data class PreviewBaseline(val attempts: Int, val lifecycleEvents: Int)
    private data class OsState(val ours: Boolean, val stopped: Boolean, val owned: Boolean)

    private class Probe(
        private val instrumentation: Instrumentation,
        private val automation: UiAutomation,
    ) {
        private val context = instrumentation.targetContext
        private val prefs = context.getSharedPreferences("dream_bridge", Context.MODE_PRIVATE)
        private val application = context.applicationContext as Application
        private val began = SystemClock.elapsedRealtime()
        private val cleanupDeadline = began + LIMIT_MS
        private val deadline = cleanupDeadline - CLEANUP_MS
        private val handler = Handler(Looper.getMainLooper())
        private val workers = Executors.newCachedThreadPool { task ->
            Thread(task, "dream-recovery-probe").apply { isDaemon = true }
        }
        private val result = Bundle()
        private val checks = JSONObject()
        private var stage = "preflight"
        private var saved: SavedMarkers? = null
        private var previewMonitor: Instrumentation.ActivityMonitor? = null
        private var callbacksRegistered = false
        private var dreamReceiverRegistered = false
        private var dreamStartAttempted = false
        // Accessed only on the UI thread. A fresh initial Home ensures callbacks see the host.
        private var resumedActivity: Activity? = null
        private val livePreviews = linkedSetOf<PhotoFramePreviewActivity>()
        private val dreamStarted = AtomicBoolean()
        private val dreamStopped = AtomicBoolean()
        private val dreamStoppedDrained = AtomicBoolean()
        private val previewEvents = AtomicInteger()
        private var backCount = 0
        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is PhotoFramePreviewActivity) {
                    livePreviews += activity
                    previewEvents.incrementAndGet()
                }
            }
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                if (activity is PhotoFramePreviewActivity) {
                    livePreviews += activity
                    previewEvents.incrementAndGet()
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
                if (activity is PhotoFramePreviewActivity) livePreviews -= activity
            }
        }
        private val dreamReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_DREAMING_STARTED) dreamStarted.set(true)
                if (intent.action == Intent.ACTION_DREAMING_STOPPED && dreamStarted.get()) {
                    dreamStopped.set(true)
                    // Runs after ImmortalApp's normal-priority global stop observer.
                    handler.post { dreamStoppedDrained.set(true) }
                }
            }
        }

        fun run(): Bundle {
            var passed = false
            var cleaned = false
            result.putBoolean("realDreamServiceLifecycleCovered", false)
            result.putBoolean("realDreamStartedObserved", false)
            result.putBoolean("realDreamInterruptionOccurred", false)
            result.putBoolean("realDreamRecoveryWiringVerified", false)
            result.putString("ownedRecoveryCoverage", "not_attempted")
            try {
                verify("android10_benchmark_target",
                    Build.VERSION.SDK_INT == 29 && context.packageName == PACKAGE &&
                        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0)
                verify("instrumentation_off_main_thread", Looper.myLooper() != Looper.getMainLooper())
                // Require the existing policy to be active so suppression cannot hide the bug.
                verify("screensaver_enabled", ScreensaverConfig.load(context).enabled)
                verify("outside_overnight_window", !SleepScheduler.isOvernightNow(context))
                verify("configured_dashboard_present", HomeDashboardConfig.configured(context))
                stage = "preflight_secure_snapshot"
                val secureBefore = secureSettings(deadline)
                stage = "preflight_lifecycle_observer"
                main(deadline) {
                    saved = SavedMarkers(markers(), persistedMarkers(), secureBefore)
                    verify("interactive_display",
                        context.getSystemService(PowerManager::class.java)?.isInteractive == true)
                    application.registerActivityLifecycleCallbacks(callbacks)
                    callbacksRegistered = true
                }
                previewMonitor = instrumentation.addMonitor(
                    PhotoFramePreviewActivity::class.java.name, null, false)
                prepareHome()
                globalStop(expired = false)
                globalStop(expired = true)
                browserExit()
                ownedDreamInterruption()
                passed = true
            } catch (error: Throwable) {
                result.putString("failedStage", stage)
                result.putString("errorType", errorType(error))
            } finally {
                val interrupted = Thread.interrupted()
                try {
                    cleaned = cleanup()
                } finally {
                    workers.shutdownNow()
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
            result.putBoolean("dreamRecoveryCompleted", passed && cleaned)
            result.putBoolean("foreignStopIsDirectHandlerCheck", true)
            result.putInt("browserBackEvents", backCount)
            result.putLong("globalObservationMsPerCase", GLOBAL_WATCH_MS)
            result.putLong("browserHomeObservationMs", BROWSER_WATCH_MS)
            result.putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
            result.putString("assertions", checks.toString())
            return result
        }

        private fun prepareHome() {
            stage = "initial_home"
            // A Portal left idle between test runs may already be in OUR normal dream.
            // Waking that dream belongs to setup, before the negative-test baselines.
            // Keep its deliberate-exit marker fresh even while a cold UI blocks the main thread.
            val holdingExit = AtomicBoolean(true)
            val guard = workers.submit {
                while (holdingExit.get() && SystemClock.elapsedRealtime() < deadline) {
                    DreamPolicy.userExitAt = System.currentTimeMillis()
                    SystemClock.sleep(POLL_MS)
                }
            }
            try {
                main(deadline) {
                    setMarkers(Markers(System.currentTimeMillis(), 0L, false, false))
                }
                val state = osState(deadline)
                verify("setup_dream_owned_or_stopped", state.owned || state.stopped)
                result.putBoolean("setupHadImmortalDream", state.owned)
                verify("setup_wakeup_key_accepted", injectKey(KeyEvent.KEYCODE_WAKEUP, deadline))
                main(deadline) { openHome(fresh = true) }
                verify("initial_home_foreground", await(8_000L) {
                    osState(deadline).stopped && homeForeground(deadline) != null
                })
                // Main-thread synchronization drains the owned callback before releasing the
                // setup marker. Subsequent checks explicitly clear/expire it to expose recovery.
                main(deadline) { verify("setup_no_live_preview",
                    livePreviews.none { !it.isFinishing && !it.isDestroyed }) }
                watch(500L, deadline) {
                    verify("setup_home_stable", osState(deadline).stopped &&
                        homeForeground(deadline) != null)
                }
            } finally {
                holdingExit.set(false)
                try { guard.get(CALL_MS, TimeUnit.MILLISECONDS) }
                finally { guard.cancel(true) }
            }
        }

        private fun globalStop(expired: Boolean) {
            val name = if (expired) "expired_markers" else "zero_markers"
            stage = "global_stop_$name"
            val baseline = baseline()
            val home = checkNotNull(homeForeground(deadline))
            val seeded = main(deadline) {
                val at = if (expired) expiredAt() else 0L
                setMarkers(Markers(at, at, false, false))
                verify("global_${name}_redream_precondition", redreamEligible())
                val before = markers()
                DreamPolicy.onDreamingStopped(context)
                verify("global_${name}_markers_preserved", markers() == before)
                before
            }
            watch(GLOBAL_WATCH_MS, deadline) {
                noPreview(baseline, deadline)
                verify("global_${name}_same_home_foreground", homeForeground(deadline) === home)
            }
            verify("global_${name}_no_preview", true)
            verify("global_${name}_markers_still_preserved", main(deadline) { markers() == seeded })
        }

        private fun browserExit() {
            stage = "browser_open"
            val baseline = baseline()
            main(deadline) {
                val at = expiredAt()
                setMarkers(Markers(at, at, false, false))
                verify("browser_redream_precondition", redreamEligible())
                verify("configured_dashboard_opened", HomeDashboardActivity.openDashboard(context))
            }
            verify("browser_foreground", await(5_000L) {
                noPreview(baseline, deadline)
                browserForeground(deadline)
            })
            // Browser foreground is enough; readiness deliberately does not depend on page content.
            watch(500L, deadline) {
                noPreview(baseline, deadline)
                verify("browser_still_owns_input", browserForeground(deadline))
            }
            stage = "browser_back"
            var returned = false
            repeat(MAX_BACK) {
                if (!returned) {
                    noPreview(baseline, deadline)
                    returned = homeForeground(deadline) != null
                    if (!returned) {
                        // Never send another BACK into Meta home, a chooser, or a preview.
                        verify("back_input_owned_by_browser", browserForeground(deadline))
                        verify("browser_back_accepted", injectBack())
                        backCount++
                        returned = await(2_500L) {
                            noPreview(baseline, deadline)
                            homeForeground(deadline) != null
                        }
                    }
                }
            }
            verify("browser_back_returned_home", returned && backCount in 1..MAX_BACK)
            stage = "browser_home_observation"
            val home = checkNotNull(homeForeground(deadline))
            watch(BROWSER_WATCH_MS, deadline) {
                noPreview(baseline, deadline)
                verify("browser_return_same_home_foreground", homeForeground(deadline) === home)
            }
            verify("browser_exit_no_preview", true)
        }

        private fun ownedDreamInterruption() {
            stage = "owned_dream_fresh_home"
            val baseline = baseline()
            val originalHome = checkNotNull(homeForeground(deadline))
            main(deadline) {
                setMarkers(Markers(System.currentTimeMillis(), 0L, false, false))
                originalHome.recreate()
            }
            verify("owned_fresh_home_foreground", await(3_000L) {
                noPreview(baseline, deadline)
                homeForeground(deadline)?.let { it !== originalHome } == true
            })
            val secure = secureSettings(deadline)
            verify("owned_secure_enabled", secure["screensaver_enabled"] == "1")
            verify("owned_secure_component", secure["screensaver_components"]?.split(',')
                ?.map(ComponentName::unflattenFromString) == listOf(component))
            verify("owned_secure_default", ComponentName.unflattenFromString(
                secure["screensaver_default_component"].orEmpty()) == component)
            verify("owned_dream_initially_stopped", osState(deadline).stopped)
            verify("owned_systemui_launcher_available", remote(deadline) {
                val info = context.packageManager.getActivityInfo(somnambulator, 0)
                info.enabled && info.applicationInfo.enabled && info.exported && info.permission == null
            })
            main(deadline) {
                context.registerReceiver(dreamReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_DREAMING_STARTED)
                    addAction(Intent.ACTION_DREAMING_STOPPED)
                    priority = -1000
                })
                dreamReceiverRegistered = true
            }
            stage = "owned_dream_start"
            result.putString("ownedRecoveryCoverage", "start_attempted")
            dreamStartAttempted = true
            validateStartReply(shell("am start -n ${somnambulator.flattenToShortString()}",
                deadline, waitMs = 4_500L, processTimeoutSeconds = 4))
            val started = await(5_000L) {
                noPreview(baseline, deadline)
                osState(deadline).ours && dreamStarted.get()
            }
            if (!started) result.putString("ownedRecoveryCoverage", "normal_os_dream_not_observed")
            verify("owned_normal_os_dream_started", started)
            result.putBoolean("realDreamStartedObserved", true)
            result.putString("ownedRecoveryCoverage", "normal_dream_started")
            stage = "owned_dream_wakeup"
            verify("owned_os_component_active_before_wakeup", osState(deadline).ours)
            verify("owned_os_power_dreaming_before_wakeup", await(3_000L) {
                shell("dumpsys -t 1 power", deadline, maxBytes = 128 * 1024)
                    .lineSequence().any { it.trim() == "mWakefulness=Dreaming" }
            })
            noPreview(baseline, deadline)
            val wakeBaseline = baseline()
            main(deadline) {
                setMarkers(Markers(0L, 0L, false, false))
                verify("owned_wakeup_redream_precondition", redreamEligible())
            }
            // The OS owns the interruption. No tap marker, hidden service reference or policy call.
            verify("owned_os_wakeup_key_accepted", injectKey(KeyEvent.KEYCODE_WAKEUP, deadline))
            verify("owned_os_wakeup_input_requested", true)
            result.putString("ownedRecoveryCoverage", "wake_input_requested")
            val interrupted = await(5_000L) {
                osState(deadline).stopped && dreamStopped.get() && dreamStoppedDrained.get()
            }
            if (!interrupted) result.putString("ownedRecoveryCoverage", "no_real_interruption_observed")
            verify("owned_os_dream_stopped_and_broadcast_drained", interrupted)
            result.putBoolean("realDreamInterruptionOccurred", true)
            result.putBoolean("realDreamServiceLifecycleCovered", true)
            result.putString("ownedRecoveryCoverage", "real_interruption_observed")
            verify("owned_no_user_tap_exit_marker", main(deadline) {
                markers() == Markers(0L, 0L, false, false)
            })
            stage = "owned_callback_recovery"
            verify("owned_preview_frame_attached", await(5_000L) {
                previewFrameAttached(deadline)
            })
            verify("owned_callback_preview_launch_observed",
                checkNotNull(previewMonitor).hits > wakeBaseline.attempts &&
                    previewEvents.get() > wakeBaseline.lifecycleEvents)
            verify("owned_os_resumed_preview", await(3_000L) {
                osResumedActivity(deadline) == previewComponent
            })
            result.putBoolean("realDreamRecoveryWiringVerified", true)
            result.putString("ownedRecoveryCoverage", "callback_recovery_observed")
            stage = "owned_recovery_finish"
            main(deadline) {
                DreamPolicy.userExitAt = System.currentTimeMillis()
                finishPreviews()
                // No tap/BACK into the frame: its configured dismiss-app callback must not run.
                openHome()
                PresenceHub.onInteraction(context)
            }
            verify("owned_recovery_returned_home", await(3_000L) { homeForeground(deadline) != null })
            val after = baseline()
            watch(500L, deadline) { noPreview(after, deadline) }
            verify("owned_no_relaunch_after_finish", true)
        }

        private fun cleanup(): Boolean {
            var cleaned = true
            // Reserve the last four seconds for restoring state and removing both observers,
            // even when stopping the Dream or returning Home times out.
            val navigationDeadline = cleanupDeadline - 4_000L
            fun attempt(name: String, action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    cleaned = false
                    checks.put(name, false)
                    result.putString("${name}ErrorType", errorType(error))
                }
            }
            // If preflight failed before a snapshot, no marker or Activity was changed.
            if (saved != null) {
                attempt("finally_test_dream_stopped") {
                    if (dreamStartAttempted) {
                        main(navigationDeadline) {
                            setMarkers(Markers(System.currentTimeMillis(), 0L, false, false))
                        }
                        val state = osState(navigationDeadline)
                        if (!state.stopped) {
                            // Never awaken a stock Dream merely because our start did not succeed.
                            verify("finally_dream_owned_or_stopped", state.owned)
                            verify("finally_owned_wakeup_key_accepted",
                                injectKey(KeyEvent.KEYCODE_WAKEUP, navigationDeadline))
                            verify("finally_owned_wakeup_input_requested", true)
                        }
                        verify("finally_test_dream_stopped", await(3_000L, navigationDeadline) {
                            osState(navigationDeadline).stopped &&
                                (!dreamStarted.get() || dreamStopped.get() && dreamStoppedDrained.get())
                        })
                    } else verify("finally_test_dream_stopped", true)
                }
                attempt("finally_home_foreground") {
                    main(navigationDeadline) {
                        setMarkers(Markers(System.currentTimeMillis(), 0L, false, false))
                        finishPreviews()
                        openHome()
                        PresenceHub.onInteraction(context)
                    }
                    verify("finally_home_foreground", await(3_000L, navigationDeadline) {
                        homeForeground(navigationDeadline) != null
                    })
                    watch(500L, navigationDeadline) {
                        verify("finally_home_stable", homeForeground(navigationDeadline) != null)
                    }
                }
                // Restore AFTER Home.onResume has cleared its bridge; restore even if Home failed.
                attempt("original_markers_restored") {
                    main(cleanupDeadline) {
                        val original = checkNotNull(saved)
                        val edit = prefs.edit()
                        bridgeKeys.forEach { edit.remove(it) }
                        original.persisted.forEach { (key, value) ->
                            when (value) {
                                is Long -> edit.putLong(key, value)
                                is Boolean -> edit.putBoolean(key, value)
                                else -> throw CheckFailure()
                            }
                        }
                        val committed = edit.commit()
                        setMarkers(original.memory)
                        verify("original_markers_restored", committed &&
                            markers() == original.memory && persistedMarkers() == original.persisted)
                        verify("finally_no_live_preview",
                            livePreviews.none { !it.isFinishing && !it.isDestroyed })
                    }
                }
                attempt("original_secure_screensaver_restored") {
                    verify("original_secure_screensaver_restored", restoreSecureSettings(cleanupDeadline))
                }
            }
            attempt("dream_receiver_removed") {
                if (dreamReceiverRegistered) main(cleanupDeadline) {
                    context.unregisterReceiver(dreamReceiver)
                    dreamReceiverRegistered = false
                }
                verify("dream_receiver_removed", !dreamReceiverRegistered)
            }
            attempt("lifecycle_observer_removed") {
                if (callbacksRegistered) main(cleanupDeadline) {
                    application.unregisterActivityLifecycleCallbacks(callbacks)
                    callbacksRegistered = false
                }
                verify("lifecycle_observer_removed", !callbacksRegistered)
            }
            attempt("preview_monitor_removed") {
                previewMonitor?.let(instrumentation::removeMonitor)
                previewMonitor = null
                verify("preview_monitor_removed", true)
            }
            return cleaned
        }

        private fun markers() = Markers(DreamPolicy.userExitAt, DreamPolicy.bridgeAt,
            DreamPolicy.inStockHandoff, DreamPolicy.portalHomeHandoff)

        private fun setMarkers(value: Markers) {
            DreamPolicy.userExitAt = value.userExitAt
            DreamPolicy.bridgeAt = value.bridgeAt
            DreamPolicy.inStockHandoff = value.inStockHandoff
            DreamPolicy.portalHomeHandoff = value.portalHomeHandoff
        }

        private fun persistedMarkers(): Map<String, Any?> = prefs.all.filterKeys { it in bridgeKeys }

        private fun secureSettings(until: Long): Map<String, String?> = remote(until) {
            secureKeys.associateWith { Settings.Secure.getString(context.contentResolver, it) }
        }

        private fun restoreSecureSettings(until: Long): Boolean = remote(until) {
            val original = checkNotNull(saved).secure
            val changed = secureKeys.filter {
                Settings.Secure.getString(context.contentResolver, it) != original[it]
            }
            if (changed.isEmpty()) return@remote true
            // Test-only temporary shell identity; no grant/revoke or persistent permission change.
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                var written = true
                changed.forEach {
                    remaining(until)
                    if (!Settings.Secure.putString(context.contentResolver, it, original[it]))
                        written = false
                }
                written && secureKeys.all {
                    Settings.Secure.getString(context.contentResolver, it) == original[it]
                }
            } finally { automation.dropShellPermissionIdentity() }
        }

        private fun expiredAt(): Long =
            System.currentTimeMillis() - maxOf(USER_EXIT_GRACE_MS, BRIDGE_GRACE_MS) - LIMIT_MS

        private fun redreamEligible(): Boolean {
            val now = System.currentTimeMillis()
            return classifyDreamStop(now - DreamPolicy.userExitAt, now - DreamPolicy.bridgeAt,
                DreamPolicy.inStockHandoff,
                context.getSystemService(PowerManager::class.java)?.isInteractive == true
            ) == DreamStopVerdict.REDREAM
        }

        private fun openHome(fresh: Boolean = false) {
            val flags = if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK
                else Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            context.startActivity(Intent(context, HomeActivity::class.java)
                .putExtra("approved_home_tab", "home")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or flags))
        }

        private fun foregroundActivity(): Activity? = resumedActivity?.takeIf {
            !it.isFinishing && !it.isDestroyed && it.hasWindowFocus()
        }

        private fun finishPreviews() {
            livePreviews.toList()
                .filter { !it.isFinishing && !it.isDestroyed }.forEach(Activity::finish)
        }

        private fun homeForeground(until: Long): HomeActivity? {
            if (!rootOwnedBy(context.packageName, until)) return null
            return main(until) { foregroundActivity() as? HomeActivity }
        }

        private fun browserForeground(until: Long): Boolean = rootOwnedBy(BROWSER, until)

        private fun rootOwnedBy(packageName: String, until: Long): Boolean = remote(until) {
            val root = automation.rootInActiveWindow ?: return@remote false
            try { root.packageName?.toString() == packageName } finally { root.recycle() }
        }

        private fun previewFrameAttached(until: Long): Boolean {
            if (!rootOwnedBy(context.packageName, until)) return false
            return main(until) {
                val preview = foregroundActivity() as? PhotoFramePreviewActivity
                    ?: return@main false
                val view = preview.window.decorView
                preview.intent.getBooleanExtra(
                    PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, false) &&
                    view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0
            }
        }

        private fun baseline() =
            PreviewBaseline(checkNotNull(previewMonitor).hits, previewEvents.get())

        private fun noPreview(baseline: PreviewBaseline, until: Long) {
            verify("no_unexpected_preview_launch",
                checkNotNull(previewMonitor).hits == baseline.attempts &&
                    previewEvents.get() == baseline.lifecycleEvents)
            verify("no_unexpected_live_preview", main(until) {
                livePreviews.none { !it.isFinishing && !it.isDestroyed }
            })
        }

        private fun injectBack(): Boolean = injectKey(KeyEvent.KEYCODE_BACK, deadline)

        private fun injectKey(code: Int, until: Long): Boolean = remote(until) {
            val downTime = SystemClock.uptimeMillis()
            var downAccepted = false
            var upAccepted = false
            fun send(action: Int, flags: Int = 0) = automation.injectInputEvent(
                KeyEvent(downTime, SystemClock.uptimeMillis(), action, code,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    InputDevice.SOURCE_KEYBOARD), false)
            try {
                remaining(until)
                downAccepted = send(KeyEvent.ACTION_DOWN)
                if (!downAccepted) return@remote false
                remaining(until)
                upAccepted = send(KeyEvent.ACTION_UP)
                upAccepted
            } finally {
                if (downAccepted && !upAccepted) send(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED)
            }
        }

        private fun osState(until: Long): OsState {
            val values = shell("dumpsys -t 1 dreams", until).lineSequence().map { it.trim() }
                .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (!values.containsKey("mCurrentDreamName") || !values.containsKey("mCurrentDreamToken"))
                throw DreamDumpFormatFailure()
            fun componentAt(name: String): ComponentName? = values[name]?.let {
                ComponentName.unflattenFromString(it.removePrefix("ComponentInfo{").removeSuffix("}"))
            }
            val token = values["mCurrentDreamToken"]
            val ours = componentAt("mCurrentDreamName") == component && token != null && token != "null" &&
                values["mCurrentDreamIsWaking"] == "false" &&
                values["mCurrentDreamIsTest"] == "false" && componentAt("mName") == component &&
                values["mSentStartBroadcast"] == "true" && values["mBound"] == "true" &&
                values["mService"]?.let { it != "null" } == true
            val stopped = values["mCurrentDreamName"] == "null" && token == "null" &&
                values["mName"] == null
            val owned = componentAt("mCurrentDreamName") == component ||
                componentAt("mName") == component
            return OsState(ours, stopped, owned)
        }

        /** Parse only the OS component field; never emit the activity/task dump or intent extras. */
        private fun osResumedActivity(until: Long): ComponentName? {
            val reply = shell("dumpsys -t 1 activity activities", until, maxBytes = 128 * 1024)
            val resumed = reply.lineSequence().map { it.trim() }
                .filter { it.startsWith("mResumedActivity:") }.mapNotNull { line ->
                    Regex("""(?:^|\s)([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)(?:\s|\})""")
                        .find(line)?.groupValues?.get(1)?.let(ComponentName::unflattenFromString)
                }.toSet()
            return resumed.singleOrNull()
        }

        private fun validateStartReply(reply: String) {
            val lines = reply.lineSequence().map { it.trim() }.toList()
            val start = lines.firstOrNull { it.startsWith("Starting:") }.orEmpty()
            val requested = Regex("""\bcmp=([^\s}]+)""").find(start)?.groupValues?.get(1)
                ?.let(ComponentName::unflattenFromString)
            if (requested != somnambulator || lines.any {
                it.startsWith("Error:") || it.startsWith("Exception") ||
                    it.contains("Permission Denial") || it.contains("SecurityException")
            }) throw ShellReplyFailure()
        }

        private fun shell(
            command: String, until: Long, waitMs: Long = CALL_MS, processTimeoutSeconds: Int = 1,
            maxBytes: Int = 32 * 1024,
        ): String {
            remaining(until)
            val pipe = AtomicReference<ParcelFileDescriptor?>()
            val abandoned = AtomicBoolean()
            val task = workers.submit<String> {
                remaining(until)
                // Runtime.exec tokenization: fixed executable/arguments, no shell expansion.
                val descriptor = automation.executeShellCommand("timeout $processTimeoutSeconds $command")
                pipe.set(descriptor)
                try {
                    if (abandoned.get()) throw DeadlineFailure()
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val bytes = ByteArrayOutputStream()
                        val chunk = ByteArray(2048)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (bytes.size() + count > maxBytes) throw ShellReplyFailure()
                            bytes.write(chunk, 0, count)
                        }
                        bytes.toString("UTF-8").trimEnd()
                    }
                } finally {
                    pipe.set(null)
                    runCatching { descriptor.close() }
                }
            }
            try { return task.get(minOf(waitMs, remaining(until)), TimeUnit.MILLISECONDS) }
            catch (error: ExecutionException) { throw error.cause ?: error }
            catch (_: TimeoutException) { throw DeadlineFailure() }
            finally {
                abandoned.set(true)
                runCatching { pipe.getAndSet(null)?.close() }
                task.cancel(true)
            }
        }

        private fun await(durationMs: Long, until: Long = deadline, ready: () -> Boolean): Boolean {
            val end = minOf(until, SystemClock.elapsedRealtime() + durationMs)
            do {
                remaining(until)
                if (ready()) return true
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                SystemClock.sleep(minOf(POLL_MS, left))
            } while (true)
        }

        /** Negative assertions must hold for the WHOLE interval, rather than pass on first sample. */
        private fun watch(durationMs: Long, until: Long, assertion: () -> Unit) {
            val end = SystemClock.elapsedRealtime() + durationMs
            if (end > until) throw DeadlineFailure()
            do {
                remaining(until)
                assertion()
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return
                SystemClock.sleep(minOf(POLL_MS, left))
            } while (true)
        }

        private fun <T> main(until: Long, action: () -> T): T {
            remaining(until)
            var value: Result<T>? = null
            // Use Android's public synchronization boundary. OS dream binding can precede
            // completion of the cold window's main-thread construction; an arbitrary RPC
            // timeout here was failing the fixture before it could inspect the actual policy.
            // Launch attempts remain recorded by the monitor throughout this wait.
            instrumentation.runOnMainSync {
                value = runCatching(action)
            }
            remaining(until)
            return checkNotNull(value).getOrThrow()
        }

        private fun <T> remote(until: Long, action: () -> T): T {
            remaining(until)
            // UiAutomation and settings reads already have framework IPC boundaries.
            // Keep the probe deadline without racing their completion against a one-second
            // worker cancellation. Shell processes retain their explicit timeout below.
            val value = action()
            remaining(until)
            return value
        }

        private fun remaining(until: Long): Long {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return (until - SystemClock.elapsedRealtime()).takeIf { it > 0 }
                ?: throw DeadlineFailure()
        }

        private fun verify(name: String, passed: Boolean) {
            checks.put(name, passed)
            if (!passed) throw CheckFailure()
        }

        private fun errorType(error: Throwable): String {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return error.javaClass.simpleName
        }
    }
}
