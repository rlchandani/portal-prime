/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * command=pinnedApp. An explicit test-APK Activity is the external pinned app; its retained WebView
 * is generated HTML only. IPC inspects that nonce-owned fixture, and accessibility reads package,
 * window ID/type/layer/bounds only. No household UI text, screenshots, personal photos or files.
 * The fixture HTML/IPC never use network. On the user-authorized owned emulator, the existing
 * default-feed renderer may attempt public feeds before its bundled fallback; that is reported.
 *
 * One 59s elapsed-time budget includes 15s reserved for cleanup, within the requested 60s limit. Main requests are cancelled before
 * admission after a timeout; fixture IPC and UiAutomation calls run on bounded daemon workers.
 * Three real injected cover/wake gestures must retain document UUID, nonempty form, nonzero scroll,
 * Activity/task identity and lifecycle counts. An uncovered control tap proves the counter works
 * before and after each wake; wake taps must deliver neither native touches nor DOM clicks below.
 *
 * Production hook contract (main owns production): nullable Kotlin Function2 properties named
 * fixtureFrameFactory and optionally fixtureDreamFactory, both (Context, () -> Unit) -> View.
 * The host passes its NORMAL reveal/exit callback and skips PhotoFrameController entirely while a
 * factory is installed. Public JVM property accessors are intentionally discovered so missing
 * optional Dream support is a reported skip; hardware needs the overlay factory. The authorized
 * owned-emulator path also accepts the unchanged default source with no configured personal source.
 * No reflection into private production fields or replacement accessibility host is used.
 *
 * Genuine Dream coverage, with a generated factory or safe owned emulator and Somnambulator on
 * API 29+, uses a normal OS start and proves dumpsys dreams ownership/non-test state + nativeCover.
 * A real KEYCODE_WAKEUP must trigger the actual stop callback, recover one accessibility overlay,
 * retain the pin and avoid preview Activity launches. A real tap then reveals the original fixture.
 * It is reported separately from overlay coverage. Only three secure Dream keys are temporarily
 * selected, then restored exactly, including null/absence. Dream bridge keys/volatile markers are
 * also restored after returning Home. Unpin is guarded by OUR fixture package + saved window ID;
 * finishAndRemoveTask runs only inside the nonce-owned fixture. A pre-existing pin aborts untouched.
 *
 * This validates window ownership and generated-document retention with the reported renderer. It does not
 * validate PhotoFrameController's photo renderer/swipe recognizer, natural idle/presence Dream
 * scheduling, process-death recovery or household app behavior. No preview Activity stands in for
 * a real Dream. PortalUxInstrumentation dispatch/exit code is owned by the main implementation.
 */
internal object PinnedAppDeviceChecks {
    // One second of headroom for result assembly after the final bounded cleanup call.
    private const val LIMIT_MS = 59_000L
    private const val CLEANUP_MS = 15_000L
    private const val CALL_MS = 2_000L
    private const val POLL_MS = 80L
    private const val CYCLES = 3
    private val fixtureComponent = ComponentName(PinFixtureProvider.TEST_PACKAGE,
        PinFixtureActivity::class.java.name)
    private val fixtureUri = Uri.parse("content://${PinFixtureProvider.AUTHORITY}")
    private val somnambulator = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")
    private val secureKeys = listOf("screensaver_enabled", "screensaver_components",
        "screensaver_default_component")
    private val bridgeKeys = setOf("bridge_at", "in_stock_handoff", "portal_home_handoff")

    fun run(instrumentation: Instrumentation): Bundle = Probe(instrumentation).run()

    private class CheckFailure : RuntimeException()
    private class DeadlineFailure : RuntimeException()
    private class MissingGeneratedFrameFactory : RuntimeException()
    private class DreamReplyFailure : RuntimeException()
    private data class Window(val id: Int, val pkg: String?, val type: Int, val layer: Int,
        val active: Boolean, val focused: Boolean, val bounds: Rect)
    private data class Markers(val userExitAt: Long, val bridgeAt: Long,
        val inStock: Boolean, val portalHome: Boolean)
    private data class Saved(val markers: Markers, val bridge: Map<String, Any?>,
        val secure: Map<String, String?>)
    private data class Fixture(val native: Bundle, val document: JSONObject) {
        val clicks get() = document.getInt("clicks")
        val x get() = native.getFloat("tapX")
        val y get() = native.getFloat("tapY")
    }
    private data class DreamState(val ours: Boolean, val stopped: Boolean, val owned: Boolean)
    private data class Cover(val curtain: Curtain?, val windowId: Int, val shown: Int, val dismissed: Int)

    /** Deliberate nullable property API; never access or make private fields accessible. */
    private class FactorySlot(private val getter: Method, private val setter: Method) {
        fun read(): Any? = getter.invoke(PinnedAppScreensaver)
        fun write(value: Any?) { setter.invoke(PinnedAppScreensaver, value) }
        companion object {
            fun find(property: String): FactorySlot? {
                val suffix = property.replaceFirstChar { it.uppercaseChar() }
                fun match(name: String, prefix: String) = name == prefix || name.startsWith("$prefix\$")
                val methods = PinnedAppScreensaver::class.java.methods
                val get = methods.singleOrNull { match(it.name, "get$suffix") && it.parameterCount == 0 }
                    ?: return null
                val set = methods.singleOrNull { match(it.name, "set$suffix") && it.parameterCount == 1 }
                    ?: return null
                return FactorySlot(get, set)
            }
        }
    }

    private class Curtain(context: Context, exit: () -> Unit) : FrameLayout(context) {
        val downs = AtomicInteger()
        val ups = AtomicInteger()
        val attaches = AtomicInteger()
        val detaches = AtomicInteger()
        init {
            setBackgroundColor(Color.rgb(22, 32, 51))
            addView(TextView(context).apply {
                text = "Generated pinned-app curtain\nTap to return to the generated fixture"
                textSize = 28f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) downs.incrementAndGet()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    ups.incrementAndGet()
                    exit()
                }
                true
            }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); attaches.incrementAndGet() }
        override fun onDetachedFromWindow() { detaches.incrementAndGet(); super.onDetachedFromWindow() }
        fun contains(x: Float, y: Float): Boolean {
            val location = IntArray(2).also(::getLocationOnScreen)
            return isAttachedToWindow && isShown &&
                x >= location[0] && y >= location[1] &&
                x < location[0] + width && y < location[1] + height
        }
    }

    private class Probe(private val instrumentation: Instrumentation) {
        private val context = instrumentation.targetContext
        private val began = SystemClock.elapsedRealtime()
        private val finalDeadline = began + LIMIT_MS
        private val deadline = finalDeadline - CLEANUP_MS
        private val mainHandler = Handler(Looper.getMainLooper())
        private val workers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "pinned-app-fixture").apply { isDaemon = true }
        }
        private val result = Bundle()
        private val checks = JSONObject()
        private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private var automation: UiAutomation? = null
        private var previousAutomationFlags: Int? = null
        private var stage = "preflight"
        private var saved: Saved? = null
        private var admitted = false
        private var admissionAttempted = false
        private var launchAttempted = false
        private var expectedWindow: Int? = null
        private var overlaySlot: FactorySlot? = null
        private var dreamSlot: FactorySlot? = null
        private var overlayPrevious: Any? = null
        private var dreamPrevious: Any? = null
        private var overlayInstalled = false
        private var dreamInstalled = false
        private var dreamStartAttempted = false
        private var completedCycles = 0
        private var emulatorDefaultFeed = false
        private var screensaverBefore: Map<String, Any?>? = null
        private var previewMonitor: Instrumentation.ActivityMonitor? = null
        private val ownedCoverWindowIds = mutableSetOf<Int>()
        private val curtains = mutableListOf<Curtain>() // main only
        private val dreamCurtains = mutableListOf<Curtain>() // main only
        private val overlayFactory: (Context, () -> Unit) -> View = { ctx, exit ->
            Curtain(ctx, exit).also(curtains::add)
        }
        private val dreamFactory: (Context, () -> Unit) -> View = { ctx, exit ->
            Curtain(ctx, exit).also(dreamCurtains::add)
        }

        fun run(): Bundle {
            var passed = false
            result.putBoolean("realDreamLifecycleCovered", false)
            result.putBoolean("realDreamStartedObserved", false)
            result.putBoolean("realDreamInterruptionOccurred", false)
            result.putBoolean("realDreamRecoveryWiringVerified", false)
            result.putBoolean("realDreamTapReturnCovered", false)
            result.putString("realDreamCoverage", "not_attempted")
            try {
                verify("debug_target_and_external_test_apk", context.packageName == PinFixtureProvider.TARGET_PACKAGE &&
                    instrumentation.context.packageName == PinFixtureProvider.TEST_PACKAGE &&
                    context.packageName != instrumentation.context.packageName)
                verify("instrumentation_off_main", Looper.myLooper() != Looper.getMainLooper())
                val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
                    (Build.PRODUCT.contains("sdk") || Build.FINGERPRINT.contains("generic"))
                verify("portal_or_owned_emulator", Build.VERSION.SDK_INT in 28..29 || emulator && Build.VERSION.SDK_INT >= 29)
                // Starting instrumentation can restart the target process while Android still
                // has the service listed as enabled. This explicit test action requests a rebind
                // and waits for its asynchronous callback before inspecting the foreground app.
                main(deadline) { SettingsGuard.ensureBarWatchConnected(context) }
                verify("provisioned_pin_host_available", await(5_000L) { pinStatus().optBoolean("available") })
                val status = pinStatus()
                verify("no_preexisting_pin_or_frame", !status.optBoolean("pinned") &&
                    !status.optBoolean("covered") && !status.optBoolean("nativeCover") &&
                    main(deadline) { !ImmortalDreamNavigation.hasOwnedSurface() })
                verify("outside_overnight_window", main(deadline) { !SleepScheduler.isOvernightNow(context) })
                overlaySlot = FactorySlot.find("fixtureFrameFactory")
                dreamSlot = FactorySlot.find("fixtureDreamFactory")
                val config = main(deadline) { ScreensaverConfig.load(context) }
                // Explicit user authorization permits the normal controller on the owned emulator.
                // This source can try public feeds before bundled fallback; never claim otherwise.
                emulatorDefaultFeed = emulator && config.source == ScreensaverConfig.SOURCE_DEFAULT &&
                    config.folderPath.isNullOrBlank() && config.albumUrl.isNullOrBlank() &&
                    config.immichUrl.isNullOrBlank() && config.smbHost.isNullOrBlank() &&
                    config.davUrl.isNullOrBlank() && config.webUrl.isNullOrBlank() &&
                    config.calendarUrl.isNullOrBlank() && !config.gestureWave
                if (overlaySlot == null && !emulatorDefaultFeed) throw MissingGeneratedFrameFactory()
                result.putString("frameRenderer", if (overlaySlot != null) "generated_factory" else "owned_emulator_existing_default_feed")
                result.putBoolean("defaultFeedMayAttemptPublicNetwork", overlaySlot == null)
                screensaverBefore = remote(deadline) {
                    context.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE).all.toMap()
                }
                val activityInfo = remote(deadline) { context.packageManager.getActivityInfo(fixtureComponent, 0) }
                verify("explicit_external_fixture_component", activityInfo.packageName == PinFixtureProvider.TEST_PACKAGE &&
                    activityInfo.processName == "${PinFixtureProvider.TEST_PACKAGE}:pin_fixture" && activityInfo.exported)
                val provider = remote(deadline) { context.packageManager.resolveContentProvider(PinFixtureProvider.AUTHORITY, 0) }
                verify("fixture_provider_same_external_process", provider != null && provider.packageName == PinFixtureProvider.TEST_PACKAGE &&
                    provider.processName == activityInfo.processName && provider.exported)
                saved = Saved(main(deadline) { markers() }, remote(deadline) {
                    context.getSharedPreferences("dream_bridge", Context.MODE_PRIVATE).all.filterKeys { it in bridgeKeys }
                }, remote(deadline) { secureKeys.associateWith { Settings.Secure.getString(context.contentResolver, it) } })
                automation = remote(deadline) {
                    instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                }
                remote(deadline) {
                    val ui = checkNotNull(automation)
                    val info = ui.serviceInfo
                    previousAutomationFlags = info.flags
                    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    ui.serviceInfo = info
                }
                main(deadline) {
                    overlayPrevious = overlaySlot?.read()
                    dreamPrevious = dreamSlot?.read()
                    verify("no_other_fixture_factory_owner", overlayPrevious == null && dreamPrevious == null)
                    // Set the ownership guard BEFORE a bounded setter can fail its reply.
                    overlaySlot?.let { slot ->
                        overlayInstalled = true
                        slot.write(overlayFactory)
                    }
                }
                previewMonitor = instrumentation.addMonitor(PhotoFramePreviewActivity::class.java.name, null, false)
                stage = "admit_external_fixture"
                admissionAttempted = true
                admitted = ipc("begin", deadline).getBoolean("admitted")
                verify("nonce_run_admitted", admitted)
                stage = "launch_explicit_external_fixture"
                launchAttempted = true
                main(deadline) {
                    context.startActivity(Intent().setComponent(fixtureComponent)
                        .putExtra(PinFixtureActivity.EXTRA_NONCE, nonce)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
                verify("generated_document_ready", await(6_000L) { fixtureOrNull()?.let {
                    it.native.getBoolean("resumed") && it.native.getBoolean("windowFocused") &&
                        fixtureWindow()?.pkg == PinFixtureProvider.TEST_PACKAGE
                } == true })
                var baseline = fixture()
                verify("initial_lifecycle_once", baseline.native.getInt("creates") == 1 &&
                    baseline.native.getInt("newIntents") == 0 && baseline.native.getInt("destroys") == 0)
                verify("generated_form_and_nonzero_scroll", baseline.document.getString("formValue").startsWith("generated-form-") &&
                    baseline.document.getDouble("scrollY") > 0 && baseline.document.getInt("clicks") == 0)
                verify("javascript_document_boot_uuid", Regex("[a-f0-9-]{36}")
                    .matches(baseline.document.getString("documentUuid")))
                expectedWindow = checkNotNull(fixtureWindow()).id
                stage = "prove_uncovered_counter"
                baseline = deliberateTap(baseline, "initial")
                stage = "pin_fixture"
                verify("pin_current_succeeded", main(deadline) {
                    val applied = PinnedAppScreensaver.command("pin")
                    verify("api_pin_immediately_updates_control_selection", applied.optJSONObject("pinControl")?.optBoolean("selected") == true)
                    applied.optBoolean("ok")
                })
                assertPin("initial_pin")
                repeat(CYCLES) { cycle ->
                    val label = "overlay_${cycle + 1}"
                    stage = "$label:show"
                    val cover = showCover(baseline, label)
                    assertPin("${label}_covered_pin")
                    unchanged(baseline, fixture(), "${label}_while_covered")
                    stage = "$label:wake_tap"
                    guardTap(baseline, cover)
                    injectTap(baseline.x, baseline.y, deadline)
                    verify("${label}_owning_frame_no_longer_covers", await(2_000L) { coverRemoved(cover) })
                    verify("${label}_normal_reveal_callback_once", pinStatus().optInt("dismissedCount") == cover.dismissed + 1)
                    cover.curtain?.let { curtain ->
                        verify("${label}_curtain_received_real_gesture", curtain.downs.get() == 1 && curtain.ups.get() == 1)
                    }
                    assertPin("${label}_retained_pin")
                    assertRevealedPin("${label}_retained_pin")
                    verify("${label}_original_window_revealed", await(2_000L) {
                        fixtureWindow()?.let { it.id == expectedWindow && (it.active || it.focused) } == true
                    })
                    watch(450L, deadline) { unchanged(baseline, fixture(), "${label}_wake") }
                    baseline = deliberateTap(baseline, "${label}_revealed")
                    verify("${label}_no_preview_activity_launched", previewMonitor?.hits == 0)
                    completedCycles++
                }
                baseline = genuineDreamIfSupported(baseline)
                unchanged(baseline, fixture(), "final_retained_session")
                verify("all_three_cover_wake_cycles", completedCycles == CYCLES)
                verify("photo_source_settings_unchanged_including_absence", remote(deadline) {
                    context.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE).all == screensaverBefore
                })
                passed = true
            } catch (error: Throwable) {
                result.putString("failedStage", stage)
                result.putString("errorType", error.javaClass.simpleName)
            } finally {
                val cleaned = cleanup()
                workers.shutdownNow()
                result.putBoolean("pinnedAppCleanupCompleted", cleaned)
                result.putBoolean("pinnedAppCompleted", passed && cleaned)
                result.putInt("coverCycles", completedCycles)
                result.putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
                result.putLong("budgetMs", LIMIT_MS)
                result.putString("assertions", synchronized(checks) { checks.toString() })
                result.putString("coverageLimitations", "Generated HTML retention is checked. " +
                    "Renderer mode and public-feed fallback are reported separately. Natural idle/presence scheduling, " +
                    "photo correctness, swipes, process death, household apps and direct native-Dream tap return are not covered.")
            }
            return result
        }

        private fun pinStatus(until: Long = deadline): JSONObject = main(until) { PinnedAppScreensaver.readStatus() }

        private fun showCover(state: Fixture, label: String): Cover {
            val before = pinStatus()
            val oldCount = main(deadline) { curtains.size }
            verify("${label}_show_frame_succeeded", main(deadline) { PinnedAppScreensaver.command("show").optBoolean("ok") })
            verify("${label}_owned_overlay_visible", await(2_500L) {
                pinStatus().optBoolean("covered") && ownOverlayAt(state.x, state.y) != null
            })
            val status = pinStatus()
            val curtain = main(deadline) {
                if (overlayInstalled) {
                    verify("${label}_one_generated_curtain", curtains.size == oldCount + 1)
                    curtains.last().also {
                        verify("${label}_generated_curtain_attached_once", it.isAttachedToWindow && it.attaches.get() == 1)
                    }
                } else null
            }
            val cover = Cover(curtain, checkNotNull(ownOverlayAt(state.x, state.y)).id,
                status.getInt("shownCount"), status.getInt("dismissedCount"))
            ownedCoverWindowIds += cover.windowId
            verify("${label}_one_overlay_owner_created", cover.shown == before.getInt("shownCount") + 1)
            verify("${label}_show_is_idempotent", main(deadline) {
                PinnedAppScreensaver.command("show").optBoolean("ok") &&
                    PinnedAppScreensaver.readStatus().getInt("shownCount") == cover.shown &&
                    (!overlayInstalled || curtains.size == oldCount + 1)
            })
            // Normal controller's welcome greeting consumes its first tap for ~3s. Let it expire;
            // this does not alter welcome, source, mode or any other application preference.
            val welcomeDelay = welcomeDelayMs()
            if (welcomeDelay > 0) watch(welcomeDelay, deadline) {
                unchanged(state, fixture(), "${label}_welcome_wait")
            }
            return cover
        }

        private fun welcomeDelayMs(): Long = main(deadline) {
            val config = ScreensaverConfig.load(context)
            if (!overlayInstalled && config.welcomeEnabled && !ScreensaverConfig.usesEngine(config.mode))
                WelcomeConfig.load(context).durationMs.toLong() + 650L
            else 0L
        }

        private fun ownOverlayAt(x: Float, y: Float): Window? = windows().singleOrNull {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.pkg == context.packageName &&
                it.bounds.contains(x.toInt(), y.toInt())
        }

        private fun coverRemoved(cover: Cover): Boolean = !pinStatus().optBoolean("covered") &&
            main(deadline) {
                !PinnedAppScreensaver.hasFrame() && (cover.curtain?.let { !it.isShown } ?: true)
            } // Hide-first reveal admits the app immediately; final cleanup checks disposal separately.

        private fun deliberateTap(before: Fixture, label: String): Fixture {
            guardTap(before, null)
            injectTap(before.x, before.y, deadline)
            verify("${label}_uncovered_click_delivered", await(2_000L) {
                fixtureOrNull()?.clicks == before.clicks + 1
            })
            val after = fixture()
            retained(before, after, label)
            verify("${label}_one_native_touch_pair", after.native.getInt("touchDowns") == before.native.getInt("touchDowns") + 1 &&
                after.native.getInt("touchUps") == before.native.getInt("touchUps") + 1)
            verify("${label}_one_dom_click", after.clicks == before.clicks + 1)
            return after
        }

        private fun retained(before: Fixture, after: Fixture, label: String) {
            verify("${label}_same_document_uuid", after.document.getString("documentUuid") == before.document.getString("documentUuid"))
            verify("${label}_same_form_value", after.document.getString("formValue") == before.document.getString("formValue"))
            verify("${label}_same_scroll", after.document.getDouble("scrollX") == before.document.getDouble("scrollX") &&
                after.document.getDouble("scrollY") == before.document.getDouble("scrollY"))
            verify("${label}_same_activity_and_task", after.native.getString("activityUuid") == before.native.getString("activityUuid") &&
                after.native.getInt("taskId") == before.native.getInt("taskId"))
            verify("${label}_no_second_create_or_new_intent", after.native.getInt("creates") == 1 &&
                after.native.getInt("newIntents") == 0 && after.native.getInt("destroys") == 0)
        }

        private fun unchanged(before: Fixture, after: Fixture, label: String) {
            retained(before, after, label)
            verify("${label}_no_underlying_dom_click", after.clicks == before.clicks)
            verify("${label}_no_underlying_native_touch", after.native.getInt("touchDowns") == before.native.getInt("touchDowns") &&
                after.native.getInt("touchUps") == before.native.getInt("touchUps"))
        }

        private fun assertPin(label: String) {
            val status = pinStatus()
            verify("${label}_exact_fixture_window", status.optBoolean("pinned") &&
                status.optString("targetPackage") == PinFixtureProvider.TEST_PACKAGE &&
                status.optInt("targetWindowId", -1) == expectedWindow && !status.optBoolean("lost"))
            verify("${label}_pin_interface_agrees", main(deadline) { PinnedAppScreensaver.hasPin() })
        }

        private fun assertRevealedPin(label: String) {
            val status = pinStatus()
            verify("${label}_owning_frame_gone_pin_window_present", !status.optBoolean("covered") &&
                !status.optBoolean("nativeCover") && status.optBoolean("targetWindowPresent"))
        }

        private fun fixtureOrNull(until: Long = deadline): Fixture? {
            val native = ipc("inspect", until)
            if (!native.getBoolean("authorized") || !native.getBoolean("pageReady") ||
                !native.getBoolean("present") || !native.getBoolean("tapInsideFixture")) return null
            val document = native.getString("document") ?: return null
            return Fixture(native, JSONObject(document))
        }
        private fun fixture() = checkNotNull(fixtureOrNull())
        private fun ipc(method: String, until: Long): Bundle = remote(until) {
            checkNotNull(context.contentResolver.call(fixtureUri, method, nonce, null))
        }

        @Suppress("DEPRECATION") // Portal Android 9/10 pooled accessibility objects.
        private fun windows(until: Long = deadline): List<Window> = remote(until) {
            val acquired = checkNotNull(automation).windows
            try {
                acquired.map { window ->
                    val root = window.root
                    val pkg = try { root?.packageName?.toString() } finally { root?.let { runCatching { it.recycle() } } }
                    Window(window.id, pkg, window.type, window.layer, window.isActive, window.isFocused,
                        Rect().also(window::getBoundsInScreen))
                }
            } finally { acquired.forEach { runCatching { it.recycle() } } }
        }
        private fun fixtureWindow(until: Long = deadline): Window? = windows(until)
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.pkg == PinFixtureProvider.TEST_PACKAGE }
            .let { candidates -> candidates.singleOrNull() }

        /** Verify known geometry and refuse an overlapping foreign/system window before input. */
        private fun guardTap(state: Fixture, cover: Cover?) {
            val x = state.x.toInt()
            val y = state.y.toInt()
            verify("fixture_tap_coordinates_finite", state.x.isFinite() && state.y.isFinite())
            val visible = windows()
            val app = checkNotNull(visible.singleOrNull { it.id == expectedWindow && it.pkg == PinFixtureProvider.TEST_PACKAGE })
            verify("fixture_tap_within_original_window", app.bounds.contains(x, y))
            val above = visible.filter { it.id != app.id && it.layer > app.layer && it.bounds.contains(x, y) }
            if (cover == null) {
                verify("uncovered_tap_has_no_obscurer", above.isEmpty() && (app.focused || app.active))
            } else {
                verify("wake_tap_only_owned_fixture_cover", above.isNotEmpty() && above.all {
                    it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.pkg == context.packageName && it.id == cover.windowId
                } && main(deadline) { cover.curtain?.contains(state.x, state.y) ?: true })
            }
        }

        private fun injectTap(x: Float, y: Float, until: Long) = remote(until) {
            val ui = checkNotNull(automation)
            val down = SystemClock.uptimeMillis()
            var downAccepted = false
            var upAccepted = false
            fun send(action: Int): Boolean {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { return ui.injectInputEvent(event, false) } finally { event.recycle() }
            }
            try {
                remaining(until)
                downAccepted = send(MotionEvent.ACTION_DOWN)
                check(downAccepted)
                Thread.sleep(40L)
                remaining(until)
                upAccepted = send(MotionEvent.ACTION_UP)
                check(upAccepted)
            } finally {
                // Terminate an admitted gesture if the deadline/IPC failed between DOWN and UP.
                if (downAccepted && !upAccepted) runCatching { send(MotionEvent.ACTION_CANCEL) }
            }
        }

        private fun genuineDreamIfSupported(before: Fixture): Fixture {
            val slot = dreamSlot
            if (!main(deadline) { ScreensaverConfig.load(context).enabled }) {
                result.putString("realDreamCoverage", "skipped_app_screensaver_disabled")
                return before
            }
            if (Build.VERSION.SDK_INT > 29) {
                // Our strict DreamManager identity parser targets Portal firmware (API 28/29).
                // Newer emulators use DreamRecord output; never infer ownership from a start reply.
                // The external Activity/WebView cycles above still run and verify real windows.
                result.putString("realDreamCoverage", "skipped_newer_platform_dream_state_format")
                return before
            }
            val installed = remote(deadline) { runCatching {
                context.packageManager.getActivityInfo(somnambulator, 0).exported
            }.getOrDefault(false) }
            if ((slot == null && !emulatorDefaultFeed) || Build.VERSION.SDK_INT < 29 || !installed) {
                result.putString("realDreamCoverage", when {
                    slot == null && !emulatorDefaultFeed -> "skipped_no_generated_dream_factory"
                    Build.VERSION.SDK_INT < 29 -> "skipped_android9_shell_identity_unavailable"
                    else -> "skipped_somnambulator_unavailable"
                })
                return before
            }
            // Leave enough work time for two greeting lifetimes plus OS start/stop/return checks.
            val dreamWorkNeeded = 14_000L + welcomeDelayMs() * 2
            if (remaining(deadline) < dreamWorkNeeded) {
                result.putString("realDreamCoverage", "skipped_insufficient_work_budget")
                return before
            }
            stage = "real_dream:preflight"
            verify("real_dream_system_initially_stopped", osDream(deadline).stopped)
            main(deadline) {
                slot?.let {
                    dreamInstalled = true
                    it.write(dreamFactory)
                }
                // Expire user-exit and Calls grace so a real OS interruption exercises recovery.
                // Restore every marker and persisted bridge key exactly during final cleanup.
                DreamPolicy.userExitAt = 0L
                DreamPolicy.bridgeAt = 0L
                DreamPolicy.inStockHandoff = false
                DreamPolicy.portalHomeHandoff = false
            }
            result.putString("dreamRenderer", if (slot != null) "generated_factory" else "owned_emulator_existing_default_feed")
            if (slot == null) result.putBoolean("defaultFeedMayAttemptPublicNetwork", true)
            val dreamComponent = ComponentName(context.packageName, PhotoDreamService::class.java.name)
            writeSecure(mapOf("screensaver_enabled" to "1",
                "screensaver_components" to dreamComponent.flattenToShortString(),
                "screensaver_default_component" to dreamComponent.flattenToShortString()), deadline)
            val overlay = showCover(before, "real_dream_prior_overlay")
            val attachBefore = pinStatus().optInt("nativeAttachCount", -1)
            val tapBefore = pinStatus().optInt("nativeTapCount", -1)
            stage = "real_dream:normal_os_start"
            dreamStartAttempted = true
            val reply = shell("am start --user current -n com.android.systemui/.Somnambulator", deadline)
            verify("real_dream_os_start_accepted", reply.lineSequence().any { it.trim().startsWith("Starting:") } &&
                !reply.contains("Error:") && !reply.contains("Permission Denial") && !reply.contains("Exception"))
            verify("real_dream_normal_os_owned_component", await(5_000L) { osDream(deadline).ours &&
                pinStatus().optBoolean("nativeCover") && main(deadline) {
                    !dreamInstalled || dreamCurtains.singleOrNull()?.isAttachedToWindow == true
                }
            })
            result.putBoolean("realDreamStartedObserved", true)
            result.putString("realDreamCoverage", "started_normal_os_dream")
            val native = main(deadline) { dreamCurtains.singleOrNull() }
            verify("real_dream_one_native_attach_callback", pinStatus().optInt("nativeAttachCount", -1) == attachBefore + 1)
            verify("real_dream_overlay_owner_removed", coverRemoved(overlay))
            assertPin("real_dream_covered_pin")
            unchanged(before, fixture(), "real_dream_covered")
            stage = "real_dream:os_force_wake"
            verify("real_dream_still_owns_os_before_force_wake", osDream(deadline).ours)
            injectWake(deadline)
            verify("real_dream_os_interruption_observed", await(3_500L) { osDream(deadline).stopped &&
                !pinStatus().optBoolean("nativeCover") && main(deadline) {
                    native?.let { !it.isAttachedToWindow && it.detaches.get() == 1 } ?: true
                }
            })
            result.putBoolean("realDreamInterruptionOccurred", true)
            // No direct Dream-policy or attach/detach invocation substitutes for the OS callback.
            verify("real_dream_callback_recovered_overlay", await(3_000L) {
                pinStatus().optBoolean("covered") && ownOverlayAt(before.x, before.y) != null
            })
            val status = pinStatus()
            verify("real_dream_no_native_tap_callback", status.optInt("nativeTapCount", -1) == tapBefore)
            verify("real_dream_one_recovery_renderer", status.getInt("shownCount") == overlay.shown + 1)
            verify("real_dream_no_preview_activity_launch", previewMonitor?.hits == 0)
            assertPin("real_dream_recovered_pin")
            unchanged(before, fixture(), "real_dream_recovered")
            val recovered = Cover(main(deadline) { if (overlayInstalled) curtains.last() else null },
                checkNotNull(ownOverlayAt(before.x, before.y)).id, status.getInt("shownCount"), status.getInt("dismissedCount"))
            ownedCoverWindowIds += recovered.windowId
            val welcomeDelay = welcomeDelayMs()
            if (welcomeDelay > 0) watch(welcomeDelay, deadline) {
                unchanged(before, fixture(), "real_dream_recovered_welcome_wait")
            }
            stage = "real_dream:recovered_overlay_wake_tap"
            guardTap(before, recovered)
            injectTap(before.x, before.y, deadline)
            verify("real_dream_recovery_overlay_removed", await(2_000L) { coverRemoved(recovered) })
            verify("real_dream_recovery_reveal_callback_once", pinStatus().getInt("dismissedCount") == recovered.dismissed + 1)
            assertPin("real_dream_retained_pin")
            assertRevealedPin("real_dream_retained_pin")
            verify("real_dream_original_window_revealed", await(2_000L) { fixtureWindow()?.id == expectedWindow })
            watch(450L, deadline) { unchanged(before, fixture(), "real_dream_recovery_wake") }
            val after = deliberateTap(before, "real_dream_recovery_revealed")
            result.putBoolean("realDreamLifecycleCovered", true)
            result.putBoolean("realDreamRecoveryWiringVerified", true)
            result.putString("realDreamCoverage", "normal_os_start_force_wake_callback_overlay_recovery_verified")
            return after
        }

        private fun injectWake(until: Long) = remote(until) {
            val ui = checkNotNull(automation)
            val downTime = SystemClock.uptimeMillis()
            var downAccepted = false
            var upAccepted = false
            fun send(action: Int, flags: Int = 0): Boolean = ui.injectInputEvent(KeyEvent(downTime,
                SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_WAKEUP, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD), false)
            try {
                remaining(until)
                downAccepted = send(KeyEvent.ACTION_DOWN)
                check(downAccepted)
                remaining(until)
                upAccepted = send(KeyEvent.ACTION_UP)
                check(upAccepted)
            } finally {
                if (downAccepted && !upAccepted) runCatching { send(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED) }
            }
        }

        private fun osDream(until: Long): DreamState {
            // Dream state only; never dump Activity tasks, window contents or household UI.
            val values = shell("dumpsys -t 1 dreams", until).lineSequence().map { it.trim() }
                .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (!values.containsKey("mCurrentDreamName") || !values.containsKey("mCurrentDreamToken"))
                throw DreamReplyFailure()
            val component = ComponentName(context.packageName, PhotoDreamService::class.java.name)
            fun at(key: String): ComponentName? = values[key]?.let {
                ComponentName.unflattenFromString(it.removePrefix("ComponentInfo{").removeSuffix("}"))
            }
            val current = at("mCurrentDreamName")
            val bound = at("mName")
            val owns = current == component || bound == component
            return DreamState(current == component && values["mCurrentDreamToken"] != "null" &&
                values["mCurrentDreamIsTest"] == "false" && values["mCurrentDreamIsWaking"] == "false" &&
                values["mSentStartBroadcast"] == "true" && bound == component && values["mBound"] == "true" && values["mService"]?.let { it != "null" } == true,
                values["mCurrentDreamName"] == "null" && values["mCurrentDreamToken"] == "null" && bound == null, owns)
        }

        private fun shell(command: String, until: Long): String {
            val pipe = AtomicReference<ParcelFileDescriptor?>()
            val abandoned = AtomicBoolean()
            try {
                return remote(until) {
                    val descriptor = checkNotNull(automation).executeShellCommand("timeout 1 $command")
                    pipe.set(descriptor)
                    try {
                        if (abandoned.get()) throw DeadlineFailure()
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(2048)
                            while (true) {
                                remaining(until)
                                val size = input.read(buffer)
                                if (size < 0) break
                                if (out.size() + size > 32 * 1024) throw DreamReplyFailure()
                                out.write(buffer, 0, size)
                            }
                            out.toString("UTF-8")
                        }
                    } finally { pipe.set(null); runCatching { descriptor.close() } }
                }
            } finally {
                abandoned.set(true)
                runCatching { pipe.getAndSet(null)?.close() }
            }
        }

        private fun cleanup(): Boolean {
            var cleaned = true
            var mayReturnHome = false
            val navigationDeadline = finalDeadline - 5_000L
            fun attempt(label: String, action: () -> Unit) {
                try { action(); synchronized(checks) { checks.put(label, true) } }
                catch (error: Throwable) {
                    synchronized(checks) { checks.put(label, false) }
                    result.putString("${label}ErrorType", error.javaClass.simpleName)
                    cleaned = false
                }
            }
            if (saved != null) {
                attempt("cleanup_owned_dream_stopped") {
                    if (dreamStartAttempted) {
                        val dream = osDream(navigationDeadline)
                        if (!dream.stopped) {
                            check(dream.owned) // Never wake another package's Dream.
                            main(navigationDeadline) {
                                val pin = PinnedAppScreensaver.status()
                                check(!pin.optBoolean("pinned") || expectedWindow != null &&
                                    pin.optString("targetPackage") == PinFixtureProvider.TEST_PACKAGE &&
                                    pin.optInt("targetWindowId", -1) == expectedWindow)
                                DreamPolicy.userExitAt = System.currentTimeMillis()
                                check(ImmortalDreamNavigation.dismissForNavigation())
                            }
                            check(await(2_000L, navigationDeadline) { osDream(navigationDeadline).stopped })
                        }
                    }
                }
                attempt("cleanup_only_fixture_pin_released") {
                    main(navigationDeadline) {
                        val status = PinnedAppScreensaver.status()
                        if (expectedWindow != null && status.optBoolean("pinned")) {
                            check(status.optString("targetPackage") == PinFixtureProvider.TEST_PACKAGE &&
                                status.optInt("targetWindowId", -1) == expectedWindow)
                            check(PinnedAppScreensaver.command("unpin").optBoolean("ok"))
                        }
                        check(!PinnedAppScreensaver.hasPin() && !PinnedAppScreensaver.hasFrame())
                        mayReturnHome = true
                    }
                }
                if (launchAttempted) attempt("cleanup_returned_home") {
                    check(mayReturnHome) // Home navigation must not release a newly foreign pin.
                    main(navigationDeadline) {
                        check(!PinnedAppScreensaver.hasPin())
                        DreamPolicy.userExitAt = System.currentTimeMillis()
                        check(context.openApprovedHomeTab(ApprovedLauncherTab.HOME))
                    }
                    check(await(2_500L, navigationDeadline) {
                        windows(navigationDeadline).any { it.pkg == context.packageName &&
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.focused || it.active) }
                    })
                    watch(350L, navigationDeadline) {
                        check(windows(navigationDeadline).any { it.pkg == context.packageName && it.focused })
                    }
                }
                if (admissionAttempted) attempt("cleanup_only_fixture_task_removed") {
                    check(ipc("finish", navigationDeadline).getBoolean("finishRequested"))
                    check(await(2_000L, navigationDeadline) {
                        val state = ipc("inspect", navigationDeadline)
                        !state.getBoolean("present") && state.getInt("destroys") == state.getInt("creates")
                    })
                    check(ipc("end", navigationDeadline).getBoolean("ended"))
                    admitted = false
                }
                attempt("cleanup_owned_cover_resources_disposed") {
                    check(await(2_000L, navigationDeadline) {
                        !pinStatus(navigationDeadline).optBoolean("covered") &&
                            pinStatus(navigationDeadline).optInt("pendingCleanupCount", 0) == 0 &&
                            windows(navigationDeadline).none { it.id in ownedCoverWindowIds } &&
                            main(navigationDeadline) { (curtains + dreamCurtains).none { it.isAttachedToWindow } }
                    })
                }
                attempt("cleanup_generated_factories_restored") {
                    main(finalDeadline) {
                        check((curtains + dreamCurtains).none { it.isAttachedToWindow })
                        if (overlayInstalled) {
                            val slot = checkNotNull(overlaySlot)
                            check(slot.read() === overlayFactory)
                            slot.write(overlayPrevious)
                            check(slot.read() === overlayPrevious)
                            overlayInstalled = false
                        }
                        if (dreamInstalled) {
                            val slot = checkNotNull(dreamSlot)
                            check(slot.read() === dreamFactory)
                            slot.write(dreamPrevious)
                            check(slot.read() === dreamPrevious)
                            dreamInstalled = false
                        }
                    }
                }
                attempt("cleanup_exact_dream_bridge_restored_including_absence") {
                    main(finalDeadline) {
                        val original = checkNotNull(saved)
                        val prefs = context.getSharedPreferences("dream_bridge", Context.MODE_PRIVATE)
                        val edit = prefs.edit()
                        bridgeKeys.forEach(edit::remove)
                        original.bridge.forEach { (key, value) -> when (value) {
                            is Long -> edit.putLong(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Int -> edit.putInt(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                            is Set<*> -> edit.putStringSet(key, value.map { checkNotNull(it as? String) }.toSet())
                            else -> throw CheckFailure()
                        } }
                        check(edit.commit())
                        DreamPolicy.userExitAt = original.markers.userExitAt
                        DreamPolicy.bridgeAt = original.markers.bridgeAt
                        DreamPolicy.inStockHandoff = original.markers.inStock
                        DreamPolicy.portalHomeHandoff = original.markers.portalHome
                        check(markers() == original.markers && prefs.all.filterKeys { it in bridgeKeys } == original.bridge)
                    }
                }
                attempt("cleanup_exact_secure_settings_restored_including_absence") {
                    val original = checkNotNull(saved).secure
                    val changed = remote(finalDeadline) { original.filter { (key, value) ->
                        Settings.Secure.getString(context.contentResolver, key) != value
                    } }
                    if (changed.isNotEmpty()) writeSecure(changed, finalDeadline)
                    check(remote(finalDeadline) { secureKeys.all {
                        Settings.Secure.getString(context.contentResolver, it) == original[it]
                    } })
                }
            }
            attempt("cleanup_preview_monitor_removed") {
                previewMonitor?.let(instrumentation::removeMonitor)
                previewMonitor = null
            }
            attempt("cleanup_photo_source_settings_unchanged") {
                screensaverBefore?.let { original ->
                    check(remote(finalDeadline) {
                        context.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE).all == original
                    })
                }
            }
            attempt("cleanup_automation_flags_restored") {
                previousAutomationFlags?.let { flags -> remote(finalDeadline) {
                    val ui = checkNotNull(automation)
                    ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags }
                } }
            }
            return cleaned
        }

        private fun markers() = Markers(DreamPolicy.userExitAt, DreamPolicy.bridgeAt,
            DreamPolicy.inStockHandoff, DreamPolicy.portalHomeHandoff)

        private fun writeSecure(values: Map<String, String?>, until: Long) = remote(until) {
            val ui = checkNotNull(automation)
            if (Build.VERSION.SDK_INT >= 29) ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                for ((key, value) in values) {
                    remaining(until)
                    check(key in secureKeys && Settings.Secure.putString(context.contentResolver, key, value))
                }
                check(values.all { (key, value) -> Settings.Secure.getString(context.contentResolver, key) == value })
            } finally {
                if (Build.VERSION.SDK_INT >= 29) ui.dropShellPermissionIdentity()
            }
        }

        private fun await(duration: Long, until: Long = deadline, ready: () -> Boolean): Boolean {
            val end = minOf(until, SystemClock.elapsedRealtime() + duration)
            do {
                remaining(until)
                if (ready()) return true
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                SystemClock.sleep(minOf(POLL_MS, left))
            } while (true)
        }

        private fun watch(duration: Long, until: Long, assertion: () -> Unit) {
            val end = SystemClock.elapsedRealtime() + duration
            if (end > until) throw DeadlineFailure()
            do {
                remaining(until)
                assertion()
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return
                SystemClock.sleep(minOf(POLL_MS, left))
            } while (true)
        }

        /** A timed-out queued main request is removed and cannot mutate a later session. */
        private fun <T> main(until: Long, action: () -> T): T {
            remaining(until)
            val task = FutureTask<T> { remaining(until); action() }
            check(mainHandler.post(task))
            try { return task.get(minOf(CALL_MS, remaining(until)), TimeUnit.MILLISECONDS) }
            catch (error: ExecutionException) { throw error.cause ?: error }
            catch (_: TimeoutException) { throw DeadlineFailure() }
            finally { task.cancel(false); mainHandler.removeCallbacks(task) }
        }

        private fun <T> remote(until: Long, action: () -> T): T {
            remaining(until)
            val task = workers.submit<T> { remaining(until); action() }
            try { return task.get(minOf(CALL_MS, remaining(until)), TimeUnit.MILLISECONDS) }
            catch (error: ExecutionException) { throw error.cause ?: error }
            catch (_: TimeoutException) { throw DeadlineFailure() }
            finally { task.cancel(true) }
        }

        private fun remaining(until: Long): Long {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return (until - SystemClock.elapsedRealtime()).takeIf { it > 0 } ?: throw DeadlineFailure()
        }
        private fun verify(label: String, value: Boolean) {
            synchronized(checks) { checks.put(label, value) }
            if (!value) throw CheckFailure()
        }
    }
}
