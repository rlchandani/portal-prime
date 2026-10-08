/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * command=widgetPaging: physical content swipes in one test-owned Home Activity.
 *
 * Requires an already migrated, saved layout with at least two widget pages. No fixture
 * widgets, settings, app-widget IDs or permissions are written. Right-to-left advances and
 * left-to-right goes back, on release at 64 dp; boundaries, cancellation and vertical intent
 * are tested.
 * Customize paging and a held reorder preview/cancel use existing, uniquely described
 * built-in cards when safe. A committed reorder is deliberately outside this probe.
 *
 * Bounds come from live native semantics, never Portal-specific screen coordinates.
 * After the initial Home launch, a catch-all ActivityMonitor blocks and records any
 * Activity launch attempt. Every gesture checks the original host window. Raw hierarchy,
 * screenshots and exception details stay in private cache. Results contain fixed labels,
 * booleans, page numbers and relative evidence paths, never household data or raw prefs.
 * Main owns compilation, installation and device execution.
 */
internal object WidgetPagingDeviceChecks {
    private const val REGION = "Home widgets"
    private val counterPattern = Regex("^Widget page ([1-9][0-9]*) of ([1-9][0-9]*)$")
    private const val SETTLE_MS = 320L // Includes the 180 ms page slide and a rendered frame.
    private val cardLabels = mapOf(
        "today" to "Today widget", "photo_memory" to "Photo Memory widget",
        "weather" to "Weather widget", "world_clock" to "World Clock widget",
        "calendar" to "Calendar widget", "timers" to "Timers widget",
        "alarm" to "Alarm widget", "crypto" to "Crypto widget", "todo" to "To-Do widget",
        "news" to "News widget", "stocks" to "Stocks widget", "options" to "Options widget",
        "ha" to "Home Assistant widget", "quick_controls" to "Quick Controls widget",
    )
    private val builtInCards = cardLabels.values.toSet()
    private val holdCards = setOf(
        "Today widget", "Photo Memory widget", "World Clock widget", "Weather widget",
        "Calendar widget",
    )

    data class Page(val number: Int, val count: Int)
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) {
        fun reversed() = Swipe(endX, endY, startX, startY)
        fun bounds() = Rect(
            minOf(startX, endX).toInt(), minOf(startY, endY).toInt(),
            maxOf(startX, endX).toInt() + 1, maxOf(startY, endY).toInt() + 1,
        )
    }
    private data class Card(val label: String, val bounds: Rect)
    private data class PagerAction(val id: Int, val next: Boolean)

    fun run(instrumentation: Instrumentation, automation: UiAutomation): Bundle =
        Probe(instrumentation, automation).run()

    /** Shared by functional checks and touchPaging; only native counter semantics qualify. */
    fun page(root: AccessibilityNodeInfo): Page? {
        val pages = mutableListOf<Page>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val match = counterPattern.matchEntire(node.contentDescription?.toString().orEmpty())
                ?: return@walk
            val number = match.groupValues[1].toIntOrNull() ?: return@walk
            val count = match.groupValues[2].toIntOrNull() ?: return@walk
            if (number in 1..count && node.text?.toString() == "$number of $count")
                pages += Page(number, count)
        }
        return pages.singleOrNull()
    }

    fun region(root: AccessibilityNodeInfo): Rect {
        val regions = mutableListOf<Rect>()
        walk(root) { node ->
            if (node.isVisibleToUser && node.contentDescription?.toString() == REGION)
                Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }?.let(regions::add)
        }
        check(regions.size == 1) { "Unique native widget region required" }
        val screen = Rect().also(root::getBoundsInScreen)
        return regions.single().also { check(screen.contains(it)) }
    }

    /**
     * Start over a visible card body, away from its bottom edit controls and screen edges.
     * right is physical movement: true is left-to-right; false is right-to-left.
     */
    fun horizontalSwipe(root: AccessibilityNodeInfo, density: Float, right: Boolean,
        distanceDp: Float = 128f): Swipe {
        val area = region(root)
        val distance = distanceDp * density
        val margin = 24f * density
        check(area.width() > distance + margin * 2)
        val choices = cards(root, area).filter {
            it.bounds.height() >= 96 * density && it.bounds.width() >= 96 * density
        }.sortedWith(compareByDescending<Card> { it.bounds.width() }.thenBy { it.bounds.top })
        for (card in choices) {
            val y = card.bounds.top + minOf(40f * density, card.bounds.height() / 3f)
            val minimumX = area.left + margin + if (right) 0f else distance
            val maximumX = area.right - margin - if (right) distance else 0f
            val x = card.bounds.exactCenterX().coerceIn(minimumX, maximumX)
            if (!card.bounds.contains(x.toInt(), y.toInt())) continue
            return Swipe(x, y, x + if (right) distance else -distance, y)
        }
        error("Safe visible widget content required")
    }

    /**
     * One downTime, monotonic real event times, actual touchscreen MOVE events, and an UP or
     * CANCEL. The optional callback inspects the page while the pointer is still held.
     */
    fun swipe(automation: UiAutomation, path: Swipe, cancel: Boolean = false,
        holdMs: Long = 0, beforeRelease: (() -> Unit)? = null) {
        val down = SystemClock.uptimeMillis()
        var pressed = false
        var x = path.startX
        var y = path.startY
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        try {
            inject(MotionEvent.ACTION_DOWN)
            pressed = true
            if (holdMs > 0) SystemClock.sleep(holdMs)
            repeat(10) { index ->
                SystemClock.sleep(16)
                val fraction = (index + 1) / 10f
                x = path.startX + (path.endX - path.startX) * fraction
                y = path.startY + (path.endY - path.startY) * fraction
                inject(MotionEvent.ACTION_MOVE)
            }
            beforeRelease?.invoke()
            inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
            pressed = false
        } finally {
            if (pressed) runCatching { inject(MotionEvent.ACTION_CANCEL) }
        }
    }

    private class Probe(
        private val instrumentation: Instrumentation,
        private val automation: UiAutomation,
    ) {
        private val context = instrumentation.targetContext
        private val density = context.resources.displayMetrics.density
        private val touchSlopDp = ViewConfiguration.get(context).scaledTouchSlop / density
        private val checks = JSONObject()
        private val skips = JSONObject()
        private val stamp = SystemClock.uptimeMillis()
        private val folder = File(context.cacheDir, "portal-ux-e2e/widget-paging-$stamp")
        private val relative = "cache/portal-ux-e2e/widget-paging-$stamp"
        private val prefs = context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)
        // Preserve the exact JSON string, unknown fields, key presence and other widget prefs.
        private val before = prefs.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        private val prefsFile = File(context.applicationInfo.dataDir,
            "shared_prefs/immortal_home_widgets.xml")
        private val diskBefore = prefsFile.takeIf { it.exists() }?.readBytes()
        private val savedWidgets = HomeWidgetStore.load(context)
        private val launchAttempts = AtomicInteger()
        private var launchMonitor: Instrumentation.ActivityMonitor? = null
        private var host: Activity? = null
        private var hostWindow = -1
        private var systemWindows = emptySet<Int>()
        private var editing = false
        private var failed = false

        fun run(): Bundle {
            try {
                check(folder.mkdirs() || folder.isDirectory)
                verify("existing_migrated_layout",
                    prefs.contains("widgets") && prefs.getBoolean("dashboard_cards_migrated", false))
                host = instrumentation.startActivitySync(Intent().setClassName(context.packageName,
                    "com.immortal.launcher.HomeActivity").apply {
                    putExtra("approved_home_tab", "home")
                    // startActivitySync needs a new instance; Home is singleTask and may be reused.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                launchMonitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        launchAttempts.incrementAndGet()
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }.also(instrumentation::addMonitor)
                val first = awaitInitialHome()
                verify("initial_home_page_one", first.number == 1)
                verify("existing_multiple_pages", first.count in 2..30)
                verify("counter_matches_saved_page_count",
                    first.count == HomePresentation.page(savedWidgets, 0).count)
                withRoot { root ->
                    hostWindow = root.windowId
                    verify("host_window_valid", hostWindow >= 0)
                    region(root)
                }
                val windows = automation.windows
                try {
                    systemWindows = windows.filter { it.id != hostWindow &&
                        it.type == AccessibilityWindowInfo.TYPE_SYSTEM && !it.isActive &&
                        !it.isFocused }.map { it.id }.toSet()
                } finally { windows.forEach { it.recycle() } }
                verify("layout_unchanged_on_home_entry", layoutUnchanged())
                assertPage(first, "initial")
                capture("home-page-one")
                val pageOneCards = geometry()

                gesture(right = true, expected = first, name = "right_clamps_first")
                gesture(right = false, expected = first, name = "below_64dp_left",
                    distanceDp = 63f)
                gesture(right = false, expected = first, name = "below_accumulated_64dp_left",
                    distanceDp = touchSlopDp + 62f)
                gesture(right = false, expected = first, name = "cancel_left", cancel = true)
                val second = Page(2, first.count)
                gesture(right = false, expected = second, name = "left_advances_on_release",
                    distanceDp = touchSlopDp + 66f, checkRelease = true)
                capture("home-page-two")
                val pageTwoCards = geometry()
                if (expectedCardLabels(1) != expectedCardLabels(2))
                    verify("page_two_has_different_visible_content", pageOneCards != pageTwoCards)
                else skips.put("distinct_page_content",
                    "Visible widget kinds and bounds are identical on the first two pages")
                verticalIntent(second, "page_two_")
                gesture(right = true, expected = second, name = "below_64dp_right",
                    distanceDp = 63f)
                gesture(right = true, expected = second, name = "below_accumulated_64dp_right",
                    distanceDp = touchSlopDp + 62f)
                gesture(right = true, expected = second, name = "cancel_right", cancel = true)
                gesture(right = true, expected = first, name = "right_returns_on_release",
                    distanceDp = touchSlopDp + 66f, checkRelease = true)

                for (number in 2..first.count)
                    gesture(false, Page(number, first.count), "left_to_page_$number")
                val last = Page(first.count, first.count)
                repeat(2) { gesture(false, last, "left_clamps_last_$it") }
                for (number in first.count - 1 downTo 1)
                    gesture(true, Page(number, first.count), "right_to_page_$number")
                repeat(2) { gesture(true, first, "right_clamps_first_$it") }

                accessibilityPaging(first, second)
                verticalIntent(first)
                customize(first, second)
                assertPage(first, "final")
                verify("final_home_mode", withRoot { has(it, "Customize") && !has(it, "Done") })
                verify("exact_widget_preferences_unchanged", layoutUnchanged())
                capture("home-final")
                verify("completed", true)
            } catch (error: Throwable) {
                failed = true
                checks.put("completed", false).put("errorType", error.javaClass.simpleName)
                runCatching {
                    folder.mkdirs()
                    File(folder, "failure.txt").printWriter().use { error.printStackTrace(it) }
                    capture("failure")
                }
            } finally {
                // Never repair or overwrite a changed household layout. Fail and leave evidence.
                if (editing) runCatching { tap("Done"); editing = false }
                    .onFailure { failed = true; checks.put("edit_mode_cleanup", false) }
                runCatching {
                    val activity = host
                    if (activity != null) instrumentation.runOnMainSync {
                        if (!activity.isFinishing) activity.finish()
                    }
                    instrumentation.waitForIdleSync()
                }.onFailure { failed = true; checks.put("host_cleanup", false) }
                launchMonitor?.let(instrumentation::removeMonitor)
                val unchanged = layoutUnchanged()
                val diskUnchanged = diskBefore?.let {
                    prefsFile.exists() && prefsFile.readBytes().contentEquals(it)
                } ?: !prefsFile.exists()
                checks.put("exact_widget_preferences_unchanged_after_cleanup", unchanged)
                    .put("exact_widget_preferences_file_unchanged", diskUnchanged)
                    .put("no_activity_launch_attempts", launchAttempts.get() == 0)
                if (!unchanged || !diskUnchanged || launchAttempts.get() != 0) failed = true
                checks.put("completed", !failed && checks.optBoolean("completed"))
                runCatching {
                    File(folder, "report.json").writeText(JSONObject()
                        .put("assertions", checks).put("skips", skips)
                        .put("thresholdDefinition", "64 dp accumulated after device touch slop")
                        .put("settleMs", SETTLE_MS)
                        .put("notCovered", JSONArray(listOf(
                            "Exact 180 ms slide duration and visual trajectory",
                            "Committed reorder, resize, removal or app-widget provider actions",
                        ))).toString(2))
                }.onFailure { failed = true; checks.put("completed", false) }
            }
            return Bundle().apply {
                putBoolean("widgetPagingCompleted", !failed && checks.optBoolean("completed"))
                putString("assertions", checks.toString())
                putString("skips", skips.toString())
                putString("evidence", relative)
                putString("report", "$relative/report.json")
            }
        }

        private fun verify(label: String, pass: Boolean) {
            checks.put(label, pass)
            check(pass) { label }
        }

        private fun layoutUnchanged() = prefs.all == before

        /** The saved presentation predicts card kinds; swipe direction is asserted separately. */
        private fun expectedCardLabels(number: Int): List<String> {
            val presentation = HomePresentation.page(savedWidgets, number - 1)
            return HomePresentation.pageWidgets(savedWidgets, presentation).map {
                if (it.isAppWidget) "App widget" else cardLabels[it.kind] ?: "Widget widget"
            }.sorted()
        }

        private fun <T> withRoot(block: (AccessibilityNodeInfo) -> T): T {
            val root = checkNotNull(automation.rootInActiveWindow) { "Native root unavailable" }
            try {
                check(root.packageName?.toString() == context.packageName) { "Unexpected UI owner" }
                if (hostWindow >= 0) check(root.windowId == hostWindow) { "Host window changed" }
                return block(root)
            } finally { root.recycle() }
        }

        private fun awaitInitialHome(): Page {
            val deadline = SystemClock.uptimeMillis() + 8_000
            do {
                val current = automation.rootInActiveWindow
                if (current != null) try {
                    if (current.packageName?.toString() == context.packageName &&
                        has(current, "Favorite apps") && has(current, REGION)) {
                        page(current)?.let { SystemClock.sleep(SETTLE_MS); return it }
                    }
                } finally { current.recycle() }
                SystemClock.sleep(80)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Native Home widget semantics unavailable")
        }

        private fun guard(path: Rect? = null) {
            check(launchAttempts.get() == 0) { "Unexpected Activity launch attempted" }
            val windows = automation.windows
            try {
                val active = checkNotNull(windows.singleOrNull { it.isActive })
                check(active.id == hostWindow && active.type == AccessibilityWindowInfo.TYPE_APPLICATION)
                val hostBounds = Rect().also(active::getBoundsInScreen)
                if (path != null) check(hostBounds.contains(path))
                windows.filter { it.id != hostWindow }.forEach { window ->
                    check(window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                        window.id in systemWindows && !window.isActive && !window.isFocused)
                    if (path != null && window.layer > active.layer) {
                        val bounds = Rect().also(window::getBoundsInScreen)
                        check(!Rect.intersects(bounds, path)) { "Overlay intersects gesture" }
                    }
                }
            } finally { windows.forEach { it.recycle() } }
        }

        private fun assertPage(expected: Page, name: String) {
            guard()
            withRoot { root ->
                verify("${name}_native_page_counter", page(root) == expected)
                val visible = cards(root, region(root))
                val expectedLabels = expectedCardLabels(expected.number).groupingBy { it }.eachCount()
                verify("${name}_expected_widget_content", visible.isNotEmpty() &&
                    visible.groupingBy { it.label }.eachCount().all { (label, count) ->
                        count <= (expectedLabels[label] ?: 0)
                    })
                var actions: List<PagerAction>? = null
                walk(root) { node ->
                    if (node.isVisibleToUser && node.contentDescription?.toString() ==
                        "Widget page ${expected.number} of ${expected.count}")
                        actions = pagerActions(node)
                }
                verify("${name}_custom_actions_match_bounds",
                    actions?.count { it.next } == (if (expected.number < expected.count) 1 else 0) &&
                    actions?.count { !it.next } == (if (expected.number > 1) 1 else 0))
                verify("${name}_removed_buttons_absent", !has(root, "More widgets") &&
                    !has(root, "Previous"))
                verify("${name}_home_chrome_retained", has(root, "Favorite apps"))
            }
            verify("${name}_layout_unchanged", layoutUnchanged())
        }

        private fun gesture(right: Boolean, expected: Page, name: String,
            distanceDp: Float = 128f, cancel: Boolean = false, checkRelease: Boolean = false) {
            val original = withRoot { checkNotNull(page(it)) }
            val path = withRoot { horizontalSwipe(it, density, right, distanceDp) }
            guard(path.bounds())
            swipe(automation, path, cancel = cancel, beforeRelease = {
                guard(path.bounds())
                if (checkRelease || cancel)
                    verify("${name}_unchanged_before_release", withRoot { page(it) } == original)
            })
            SystemClock.sleep(SETTLE_MS)
            assertPage(expected, name)
        }

        private fun accessibilityPaging(first: Page, second: Page) {
            fun apply(next: Boolean, expected: Page, name: String) {
                guard()
                val applied = withRoot { root ->
                    var done = false
                    walk(root) { node ->
                        if (!done && node.isVisibleToUser &&
                            counterPattern.matches(node.contentDescription?.toString().orEmpty())) {
                            val actions = pagerActions(node)
                            val action = actions.singleOrNull { it.next == next }
                            if (action != null) done = node.performAction(action.id)
                        }
                    }
                    done
                }
                verify("${name}_custom_action_available_and_applied", applied)
                SystemClock.sleep(SETTLE_MS)
                assertPage(expected, name)
            }
            apply(true, second, "accessible_next")
            gesture(right = true, expected = first, name = "right_returns_after_accessible_next",
                checkRelease = true)
            gesture(right = false, expected = second, name = "left_advances_before_accessible_previous",
                checkRelease = true)
            apply(false, first, "accessible_previous")
        }

        private fun verticalIntent(first: Page, prefix: String = "") {
            val area = withRoot(::region)
            val scrollable = withRoot { root ->
                var found: Rect? = null
                walk(root) { node ->
                    if (node.isVisibleToUser && node.isScrollable) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (bounds.intersect(area) && bounds.height() >= 200 * density &&
                            bounds.width() >= 96 * density &&
                            (found == null || bounds.height() > checkNotNull(found).height()))
                            found = bounds
                    }
                }
                found
            }
            val body = scrollable ?: withRoot { cards(it, area).maxByOrNull { it.bounds.height() } }
                ?.bounds ?: error("Visible widget body required for vertical intent")
            val distance = minOf(160f * density, body.height() * .45f)
            val x = body.exactCenterX()
            val startY = body.exactCenterY() + distance / 2
            val path = Swipe(x, startY, x + 8 * density, startY - distance)
            val beforeGeometry = geometry()
            val scrollEvents = AtomicInteger()
            automation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                    event.windowId == hostWindow && event.packageName?.toString() == context.packageName) {
                    val source = event.source
                    if (source != null) try {
                        val bounds = Rect().also(source::getBoundsInScreen)
                        if (Rect.intersects(area, bounds) && event.scrollY > 0)
                            scrollEvents.incrementAndGet()
                    } finally { source.recycle() }
                }
            }
            try {
                guard(path.bounds())
                swipe(automation, path)
                SystemClock.sleep(600) // Let vertical fling settle before evidence/restoration.
                assertPage(first, "${prefix}vertical_up_does_not_page")
                if (scrollable != null)
                    verify("${prefix}vertical_scroll_still_moves_content",
                        scrollEvents.get() > 0 || geometry() != beforeGeometry)
                else skips.put("${prefix}vertical_scroll_movement",
                    "No visible widget viewport advertises vertical scrolling")
                guard(path.bounds())
                swipe(automation, path.reversed())
                SystemClock.sleep(600)
                assertPage(first, "${prefix}vertical_down_does_not_page")
            } finally { automation.setOnAccessibilityEventListener(null) }
        }

        private fun customize(first: Page, second: Page) {
            val beforeCards = geometry()
            val candidate = withRoot { root ->
                val area = region(root)
                cards(root, area).filter { it.label in holdCards &&
                    it.bounds.width() >= 160 * density && it.bounds.height() >= 128 * density }
                    .groupBy { it.label }.values.filter { it.size == 1 }.map { it.single() }
                    .maxByOrNull { it.bounds.width() }
            }
            if (candidate != null) {
                val x = candidate.bounds.exactCenterX()
                val y = candidate.bounds.top + 40 * density
                val hold = Swipe(x, y, x, y)
                guard(hold.bounds())
                swipe(automation, hold, cancel = true,
                    holdMs = ViewConfiguration.getLongPressTimeout().toLong() + 120,
                    beforeRelease = {
                        editing = withRoot { has(it, "Done") }
                        verify("stationary_long_press_enters_customize", editing)
                        verify("stationary_long_press_does_not_page", withRoot { page(it) } == first)
                    })
                SystemClock.sleep(SETTLE_MS)
            } else {
                skips.put("stationary_long_press", "No unique safe built-in card body is visible")
                tap("Customize")
                editing = true
            }
            verify("customize_visible", withRoot { has(it, "Done") && has(it, "Add widget") })
            assertPage(first, "customize_entry")
            verify("home_customize_geometry_matches", geometry() == beforeCards)
            val editOne = geometry()
            gesture(false, second, "customize_left_advances", checkRelease = true)
            val editTwo = geometry()
            tap("Done")
            editing = false
            assertPage(second, "done_retains_page_two")
            verify("customize_home_page_two_geometry_matches", geometry() == editTwo)
            tap("Customize")
            editing = true
            assertPage(second, "customize_retains_page_two")
            gesture(true, first, "customize_right_returns", checkRelease = true)
            verify("customize_round_trip_geometry_matches", geometry() == editOne)
            gesture(false, first, "customize_short_left", distanceDp = 63f)
            gesture(false, first, "customize_cancel_left", cancel = true)
            gesture(true, first, "customize_right_clamps_first")
            heldReorderCancel(first)
            tap("Done")
            editing = false
            assertPage(first, "done_retains_page_one")
        }

        private fun heldReorderCancel(first: Page) {
            val area = withRoot(::region)
            val candidates = withRoot { root ->
                cards(root, region(root)).filter { it.label in builtInCards &&
                    it.bounds.width() >= 160 * density && it.bounds.height() >= 128 * density &&
                    maxOf(it.bounds.top + 24 * density, area.top + 80 * density) <
                    minOf(it.bounds.bottom - 80 * density, area.bottom - 80 * density) }
                    .groupBy { it.label }.values.filter { it.size == 1 }.map { it.single() }
            }
            val pair = candidates.flatMap { source -> candidates.filter { it != source }.map {
                source to it
            } }.firstOrNull { (source, target) ->
                abs(source.bounds.exactCenterX() - target.bounds.exactCenterX()) > 80 * density
            }
            if (pair == null) {
                skips.put("held_reorder_preview_cancel",
                    "Two unique visible built-in card bodies are required")
                return
            }
            val (source, target) = pair
            val baseline = geometry()
            // Both body points were filtered away from auto-scroll bands and edit footers.
            fun bodyY(card: Card) = (maxOf(card.bounds.top + 24 * density, area.top + 80 * density) +
                minOf(card.bounds.bottom - 80 * density, area.bottom - 80 * density)) / 2
            val path = Swipe(source.bounds.exactCenterX(), bodyY(source),
                target.bounds.exactCenterX(), bodyY(target))
            guard(path.bounds())
            swipe(automation, path, cancel = true,
                holdMs = ViewConfiguration.getLongPressTimeout().toLong() + 120,
                beforeRelease = {
                    guard(path.bounds())
                    val moved = withRoot { root ->
                        cards(root, region(root)).singleOrNull { it.label == source.label }?.bounds
                    }
                    verify("held_reorder_preview_moves_card", moved != null &&
                        abs(moved.left - source.bounds.left) + abs(moved.top - source.bounds.top) >
                        24 * density)
                    verify("held_reorder_does_not_page", withRoot { page(it) } == first)
                    verify("held_reorder_not_persisted_before_release", layoutUnchanged())
                })
            SystemClock.sleep(SETTLE_MS)
            assertPage(first, "held_reorder_cancel")
            verify("held_reorder_cancel_restores_geometry", geometry() == baseline)
        }

        private fun tap(label: String) {
            val bounds = withRoot { root ->
                val controls = mutableSetOf<Rect>()
                walk(root) { node ->
                    if (node.isVisibleToUser && node.isClickable && node.isEnabled && has(node, label))
                        Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }?.let(controls::add)
                }
                check(controls.size == 1) { "Unique native mode control required" }
                controls.single()
            }
            guard(bounds)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (action == MotionEvent.ACTION_UP) SystemClock.sleep(32)
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            SystemClock.sleep(SETTLE_MS)
        }

        private fun geometry(): List<Card> = withRoot { cards(it, region(it))
            .sortedWith(compareBy<Card> { it.label }.thenBy { it.bounds.top }.thenBy { it.bounds.left }) }

        private fun capture(name: String) {
            withRoot { File(folder, "$name.json").writeText(snapshot(it).toString()) }
            automation.takeScreenshot()?.let { image ->
                try {
                    File(folder, "$name.png").outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
            }
        }
    }

    private fun cards(root: AccessibilityNodeInfo, area: Rect): List<Card> {
        val result = mutableListOf<Card>()
        walk(root) { node ->
            val label = node.contentDescription?.toString()
            if (node.isVisibleToUser && (label in builtInCards || label == "App widget")) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.intersect(area) && !bounds.isEmpty)
                    result += Card(checkNotNull(label), bounds)
            }
        }
        return result
    }

    private fun pagerActions(node: AccessibilityNodeInfo): List<PagerAction> =
        node.actionList.mapNotNull { action ->
            // Compose custom actions carry labels and resource IDs; do not mistake generic
            // scroll actions for the explicitly announced page navigation actions.
            val label = action.label?.toString()?.lowercase(java.util.Locale.ROOT).orEmpty()
            when {
                action.id < 0x01000000 -> null
                "next" in label -> PagerAction(action.id, true)
                "previous" in label -> PagerAction(action.id, false)
                else -> null
            }
        }

    private fun has(root: AccessibilityNodeInfo, label: String): Boolean {
        var found = false
        walk(root) { node ->
            if (node.isVisibleToUser &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label))
                found = true
        }
        return found
    }

    /** Borrow the root; recycle each child after its bounded traversal. */
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        var visited = 0
        fun descend(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || visited++ >= 5_000) return
            visit(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { descend(child, depth + 1) } finally { child.recycle() }
            }
        }
        descend(root, 0)
    }

    private fun snapshot(root: AccessibilityNodeInfo): JSONObject {
        var visited = 0
        fun descend(node: AccessibilityNodeInfo, depth: Int): JSONObject {
            if (depth > 60 || visited++ >= 5_000) return JSONObject().put("truncated", true)
            val bounds = Rect().also(node::getBoundsInScreen)
            val children = JSONArray()
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { children.put(descend(child, depth + 1)) } finally { child.recycle() }
            }
            return JSONObject().put("text", if (node.isPassword) "" else node.text?.toString().orEmpty())
                .put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty())
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                .put("visible", node.isVisibleToUser).put("children", children)
        }
        return descend(root, 0)
    }
}
