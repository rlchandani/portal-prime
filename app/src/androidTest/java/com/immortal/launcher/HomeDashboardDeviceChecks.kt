/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** Read-only dashboard navigation. Never taps a web dashboard control or reads login storage. */
internal object HomeDashboardDeviceChecks {
    fun run(instrumentation: Instrumentation, automation: UiAutomation): Bundle {
        val context = instrumentation.targetContext
        val checks = JSONObject()
        var stage = "initial"
        fun verify(name: String, value: Boolean) {
            checks.put(name, value)
            check(value) { name }
        }
        fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
            visit(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { walk(child, visit) } finally { child.recycle() }
            }
        }
        fun has(root: AccessibilityNodeInfo, text: String): Boolean {
            var found = false
            walk(root) { if (it.isVisibleToUser &&
                (it.text?.toString() == text || it.contentDescription?.toString() == text)) found = true }
            return found
        }
        fun awaitRoot(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 12_000
            do {
                val root = automation.rootInActiveWindow
                if (root != null) {
                    if (predicate(root)) return root
                    root.recycle()
                }
                SystemClock.sleep(100)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Requested dashboard navigation state did not appear")
        }
        fun inspect(predicate: (AccessibilityNodeInfo) -> Boolean) {
            val root = awaitRoot(predicate)
            root.recycle()
        }
        fun tap(text: String) {
            stage = "tap:$text"
            val root = awaitRoot { has(it, text) }
            val bounds = mutableListOf<Rect>()
            try { walk(root) { node ->
                if (node.isVisibleToUser &&
                    (node.text?.toString() == text || node.contentDescription?.toString() == text))
                    Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }?.let(bounds::add)
            } } finally { root.recycle() }
            val target = bounds.distinct().single()
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (action == MotionEvent.ACTION_UP) SystemClock.sleep(40)
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    target.exactCenterX(), target.exactCenterY(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
        }
        fun home(tab: String, fresh: Boolean = false) {
            stage = "home:$tab"
            context.startActivity(Intent(context, HomeActivity::class.java)
                .putExtra("approved_home_tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_CLEAR_TOP))
            inspect { it.packageName?.toString() == context.packageName &&
                has(it, if (tab == "apps") "Your apps" else "Favorite apps") }
        }
        val url = HomeDashboardConfig.load(context).url
        val uri = Uri.parse(url)
        val visibleAddress = "${uri.authority}${uri.path}"
        fun dashboard(name: String) {
            stage = name
            var loaded = false
            inspect { root ->
                var address = false
                var content = false
                walk(root) { node ->
                    if (node.isVisibleToUser &&
                        node.text?.toString().orEmpty().contains(visibleAddress)) address = true
                    if (node.isVisibleToUser && node.className?.toString() == "android.webkit.WebView") {
                        var namedContent = 0
                        walk(node) { child ->
                            if (child.isVisibleToUser &&
                                (!child.text.isNullOrBlank() || !child.contentDescription.isNullOrBlank()))
                                namedContent++
                        }
                        if (namedContent >= 5) content = true
                    }
                }
                loaded = root.packageName?.toString() == "org.chromium.chrome" &&
                    address && content && !has(root, "Log in") && !has(root, "Password") &&
                    !has(root, "This site can’t be reached") && !has(root, "Web page not available")
                loaded
            }
            verify(name, loaded)
        }
        return try {
        verify("dashboard_configured", HomeDashboardConfig.configured(context))
        val companionInstalled = runCatching {
            context.packageManager.getPackageInfo("io.homeassistant.companion.android.minimal", 0)
        }.isSuccess
        verify("companion_uninstalled", !companionInstalled)
        home("home", fresh = true)
        inspect { has(it, "Your home dashboard") && has(it, "Open dashboard") }
        verify("custom_dashboard_home_card", true)
        tap("Open dashboard")
        dashboard("home_button_preserves_signed_in_dashboard")
        home("apps")
        var tileVisible = false
        repeat(5) {
            if (!tileVisible) {
                val root = awaitRoot { it.packageName?.toString() == context.packageName }
                try {
                    tileVisible = has(root, "Home dashboard")
                    if (!tileVisible) {
                        var scrolled = false
                        walk(root) { node ->
                            if (!scrolled && node.isVisibleToUser && node.isScrollable)
                                scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    }
                } finally { root.recycle() }
                if (!tileVisible) SystemClock.sleep(250)
            }
        }
        verify("apps_has_dashboard_tile", tileVisible)
        tap("Home dashboard")
        dashboard("apps_tile_preserves_signed_in_dashboard")
        home("home")
        val first = awaitRoot { WidgetPagingDeviceChecks.page(it)?.number == 1 }
        val swipe = try {
            WidgetPagingDeviceChecks.horizontalSwipe(first, context.resources.displayMetrics.density,
                right = false)
        } finally { first.recycle() }
        WidgetPagingDeviceChecks.swipe(automation, swipe)
        inspect { WidgetPagingDeviceChecks.page(it)?.number == 2 && has(it, "Home dashboard") }
        tap("Home dashboard")
        dashboard("quick_controls_preserves_signed_in_dashboard")
        // Reset Home to page one for the user's return, then check the native settings route.
        home("home", fresh = true)
        context.startActivity(approvedSettingsIntent(context, "ha")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inspect { has(it, "Dashboard address") && has(it, "Open dashboard") }
        verify("dashboard_settings_row_visible", true)
        tap("Open dashboard")
        dashboard("settings_preserves_signed_in_dashboard")
        Bundle().apply {
            putBoolean("homeDashboardCompleted", true)
            putString("assertions", checks.toString())
        }
        } catch (failure: Exception) {
            Bundle().apply {
                putBoolean("homeDashboardCompleted", false)
                putString("assertions", checks.toString())
                putString("failedStage", stage)
                putString("errorType", failure.javaClass.simpleName)
            }
        }
    }
}
