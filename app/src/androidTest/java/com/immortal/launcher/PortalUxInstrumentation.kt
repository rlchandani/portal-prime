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
        val result = Bundle()
        try {
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
            if (noop || tabs || windowSafety) check(hasInitialPage(initial))
        }
        val dockLabels = listOf("Apps", "Tools", "Home")
        val initialWindowId = initial.windowId
        check(initialWindowId >= 0)
        // Retain only existing system chrome. Any additional window aborts the window probe,
        // including a non-focusable overlay; package identity was checked on the initial root.
        val initialSystemWindows = if (windowSafety || settings) {
            val windows = automation.windows
            try {
                windows.filter {
                    it.id != initialWindowId && it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                        !it.isActive && !it.isFocused
                }.map { it.id }.toSet()
            } finally {
                windows.forEach { it.recycle() }
            }
        } else emptySet()

        fun verifyWindow(bounds: Rect) {
            val windows = automation.windows
            try {
                val active = windows.singleOrNull { it.isActive }
                    ?: error("No single active benchmark window")
                check(active.id == initialWindowId &&
                    active.type == AccessibilityWindowInfo.TYPE_APPLICATION)
                windows.forEach { window ->
                    if (window.id != initialWindowId) {
                        check(window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                            window.id in initialSystemWindows && !window.isFocused)
                        val windowBounds = Rect().also(window::getBoundsInScreen)
                        check(window.layer <= active.layer ||
                            !windowBounds.contains(bounds.centerX(), bounds.centerY()))
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
                listOf(display, back)
            }
            else -> {
                val next = Rect().also { find(initial, "More widgets")!!.getBoundsInScreen(it) }
                val previous = Rect().also { find(initial, "Previous")!!.getBoundsInScreen(it) }
                check(!next.isEmpty && !previous.isEmpty)
                when {
                    tabs -> dockLabels.map { label ->
                        Rect().also { find(initial, label)!!.getBoundsInScreen(it) }.also {
                            check(!it.isEmpty)
                        }
                    }
                    noop -> listOf(previous, previous)
                    else -> listOf(next, previous)
                }
            }
        }

        fun roundTrip() {
            gestures.forEachIndexed { index, bounds ->
                if (!windowSafety) {
                    val active = root(automation)
                    check(active.packageName?.toString() == targetContext.packageName)
                    if (settings) {
                        check(active.windowId == initialWindowId)
                        check(find(active, "Appearance") != null && find(active, "Display") != null)
                        if (index == 0) verifySettingsOverview(active)
                        else check(findClickable(active, "Settings") != null)
                    } else if (tabs) {
                        dockLabels.forEach { check(find(active, it) != null) }
                    } else {
                        check(find(active, "Favorite apps") != null)
                        check(find(active, "More widgets") != null && find(active, "Previous") != null)
                    }
                    if (noop) check(hasInitialPage(active))
                }
                tap(bounds)
                if (noop && !windowSafety) check(hasInitialPage(root(automation)))
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
        } else if (windowSafety || tabs) {
            val finalHome = root(automation)
            check(finalHome.packageName?.toString() == targetContext.packageName)
            check(finalHome.windowId == initialWindowId)
            check(find(finalHome, "Favorite apps") != null)
            check(find(finalHome, "More widgets") != null && find(finalHome, "Previous") != null)
            if (tabs) dockLabels.forEach { check(find(finalHome, it) != null) }
            check(hasInitialPage(finalHome))
        }
        File(targetContext.cacheDir, "portal-ux-e2e").apply { mkdirs() }
            .resolve("home-touch-frames.txt").writeBytes(output)
    }

    /** Only the page counter is inspected; its text is never emitted. */
    private fun hasInitialPage(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 60) return false
        if (node.text?.toString()?.matches(Regex("^1 of [1-9][0-9]*$")) == true) return true
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { if (hasInitialPage(it, depth + 1)) return true }
        }
        return false
    }

    private fun observe(automation: UiAutomation) {
        val folder = File(targetContext.cacheDir, "portal-ux-e2e").apply { mkdirs() }
        val request = File(folder, "request.json")
        val response = File(folder, "response.json")
        request.delete()
        response.delete()
        val homeMonitor = addMonitor("com.immortal.launcher.HomeActivity", null, false)
        targetContext.startActivity(Intent().setClassName(targetContext.packageName,
            "com.immortal.launcher.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        sendStatus(0, Bundle().apply { putBoolean("observerReady", true) })
        val remoteEnabledBefore = RemotePairing.isEnabled(targetContext)
        val deadline = SystemClock.uptimeMillis() + 45 * 60_000L
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
            try {
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
                val snapshot = snapshotWindows(automation, active)
                File(folder, "$name.json").writeText(snapshot.toString())
                automation.takeScreenshot()?.let { bitmap ->
                    try {
                        File(folder, "$name.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
                response.writeText(JSONObject().put("name", name).put("success", true).toString())
            } catch (error: Throwable) {
                response.writeText(JSONObject().put("name", name).put("success", false)
                    .put("errorType", error.javaClass.simpleName).toString())
            }
        }
        } finally {
            removeMonitor(homeMonitor)
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
            .put("checked", node.isChecked).put("checkable", node.isCheckable)
            .put("password", node.isPassword).put("visible", node.isVisibleToUser)
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("children", children)
            .apply { if (depth == 0) put("totalNodes", count[0]) }
    }
}
