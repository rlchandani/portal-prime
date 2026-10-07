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
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONObject
import java.io.File

/** Tests the real shared header, visual theme, retained draft and persistence without pairing. */
internal object AppearanceDeviceChecks {
    fun run(probe: Instrumentation, automation: UiAutomation): Bundle {
        val context = probe.targetContext
        val prefs = context.getSharedPreferences("immortal_settings", Context.MODE_PRIVATE)
        val original = ImmortalSettings.settingsTheme(context)
        val keyExisted = prefs.contains("settings_theme")
        val checks = JSONObject()
        val folder = File(context.cacheDir, "portal-ux-e2e/appearance-${SystemClock.uptimeMillis()}")
            .apply { mkdirs() }
        val homeMonitor = probe.addMonitor(HomeActivity::class.java.name, null, false)
        val settingsMonitor = probe.addMonitor(ImmortalSettingsActivity::class.java.name, null, false)
        var host: Activity? = null
        fun verify(name: String, pass: Boolean) {
            checks.put(name, pass)
            check(pass) { name }
        }
        fun root(): AccessibilityNodeInfo = checkNotNull(automation.rootInActiveWindow)
        fun waitFor(label: String): AccessibilityNodeInfo {
            val until = SystemClock.uptimeMillis() + 8_000
            do {
                val current = automation.rootInActiveWindow
                if (current?.packageName?.toString() == context.packageName &&
                    find(current, label) != null) return current
                SystemClock.sleep(80)
            } while (SystemClock.uptimeMillis() < until)
            error("Control unavailable")
        }
        fun tap(label: String) {
            val node = checkNotNull(find(waitFor(label), label))
            val bounds = Rect().also(node::getBoundsInScreen)
            check(!bounds.isEmpty)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (action == MotionEvent.ACTION_UP) SystemClock.sleep(32)
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            SystemClock.sleep(300)
        }
        fun expectChrome(name: String, dark: Boolean) {
            val theme = if (dark) "Switch to day theme" else "Switch to night theme"
            val current = waitFor(theme)
            val labels = listOf("Connect your phone", "Sleep screen", theme,
                "Search apps and settings", "Settings")
            val boxes = labels.map {
                val node = find(current, it)
                verify("$name: $it", node != null)
                Rect().also(checkNotNull(node)::getBoundsInScreen)
            }
            var chromeMatches = false
            var panelMatches = false
            val deadline = SystemClock.uptimeMillis() + 2_000
            do {
              val image = checkNotNull(automation.takeScreenshot())
              try {
                verify("$name: controls fit and do not overlap", boxes.all {
                    it.left >= 0 && it.top >= 0 && it.right <= image.width && it.bottom <= image.height
                } && boxes.zipWithNext().all { (first, second) -> first.right <= second.left })
                val remote = boxes.first()
                val pixel = image.getPixel(remote.centerX(), remote.top + 14)
                val expected = if (dark) Color.rgb(54, 54, 54) else Color.rgb(229, 235, 242)
                chromeMatches =
                    kotlin.math.abs(Color.red(pixel) - Color.red(expected)) <= 10 &&
                    kotlin.math.abs(Color.green(pixel) - Color.green(expected)) <= 10 &&
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) <= 10
                if (name.startsWith("Converter")) {
                    val title = checkNotNull(find(current, "Convert"))
                    val panel = Rect().also(title::getBoundsInScreen)
                    val surface = image.getPixel(panel.left + 12, panel.top - 14)
                    val brightness = (Color.red(surface) + Color.green(surface) + Color.blue(surface)) / 3
                    panelMatches = if (dark) brightness < 110 else brightness > 240
                } else panelMatches = true
              } finally { image.recycle() }
              if (!chromeMatches || !panelMatches) SystemClock.sleep(80)
            } while ((!chromeMatches || !panelMatches) && SystemClock.uptimeMillis() < deadline)
            verify("$name: visual theme matches preference", chromeMatches)
            if (name.startsWith("Converter")) verify("$name: workspace panel follows theme", panelMatches)
            verify("$name: saved appearance", ImmortalSettings.settingsTheme(context) == if (dark) "dark" else "light")
            verify("$name: same Activity", homeMonitor.hits == 1 && homeMonitor.lastActivity === host)
        }
        fun capture(name: String) {
            // Allow one settled frame; accessibility may update before the screen capture buffer.
            SystemClock.sleep(500)
            checkNotNull(automation.takeScreenshot()).let { image ->
                try {
                    File(folder, "$name.png").outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
            }
        }
        fun toggle(dark: Boolean, name: String) {
            tap(if (dark) "Switch to night theme" else "Switch to day theme")
            expectChrome(name, dark)
        }
        try {
            probe.runOnMainSync {
                SettingsDomains.immortal.apply(context, JSONObject().put("settingsTheme", "light"))
            }
            probe.startActivitySync(Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                .also { host = it }
            waitFor("Favorite apps")
            expectChrome("Home day", false)
            toggle(true, "Home night")
            tap("Apps")
            expectChrome("Apps night", true)
            toggle(false, "Apps day")
            tap("Tools")
            waitFor("A few minutes, or a few hours.")
            expectChrome("Tools day", false)
            capture("tools-day")
            tap("Converter")
            waitFor("Swap units")
            val input = checkNotNull(find(root(), "Value")).let { node ->
                if (node.isEditable) node else editable(node) ?: editable(root())
            }
            verify("Converter draft can be edited", input != null &&
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "7.25")
                }))
            toggle(true, "Converter night")
            verify("Draft survives night toggle", find(root(), "7.25") != null)
            capture("converter-night")
            toggle(false, "Converter day")
            verify("Draft survives day toggle", find(root(), "7.25") != null)
            capture("converter-day")
            tap("Home")
            expectChrome("Home after tool", false)
            tap("Tools")
            waitFor("Swap units")
            verify("Tool and draft survive tab return", find(root(), "7.25") != null)
            tap("Settings")
            waitFor("Dark appearance")
            verify("Settings shares day theme", find(root(), "Dark appearance") != null)
            tap("Dark appearance")
            waitFor("Light appearance")
            verify("Settings writes shared appearance", ImmortalSettings.settingsTheme(context) == "dark")
            probe.runOnMainSync { checkNotNull(settingsMonitor.lastActivity).finish() }
            expectChrome("Return from Settings night", true)
            verify("Settings round trip preserves draft", find(root(), "7.25") != null)
            probe.runOnMainSync { checkNotNull(host).recreate() }
            waitFor("Switch to day theme")
            verify("Appearance survives Activity recreation", ImmortalSettings.settingsTheme(context) == "dark" &&
                find(root(), "Switch to day theme") != null)
            host = homeMonitor.lastActivity
            tap("Tools")
            waitFor("A few minutes, or a few hours.")
            capture("tools-night")
            // Bounded native theme-switch sample; no hierarchy reads inside this loop.
            val control = checkNotNull(find(root(), "Switch to day theme"))
            val bounds = Rect().also(control::getBoundsInScreen)
            val transitions = java.util.concurrent.atomic.AtomicInteger()
            val observer = prefs.registeredThemeTransitionCounter { transitions.incrementAndGet() }
            automation.executeShellCommand("dumpsys gfxinfo ${context.packageName} reset").close()
            try { repeat(16) {
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    if (action == MotionEvent.ACTION_UP) SystemClock.sleep(32)
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                SystemClock.sleep(450)
            } } finally { observer() }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "dumpsys gfxinfo ${context.packageName} framestats")).use {
                File(folder, "theme-frames.txt").writeBytes(it.readBytes())
            }
            verify("Repeated theme switching ends in correct theme",
                ImmortalSettings.settingsTheme(context) == "dark" &&
                    find(root(), "Switch to day theme") != null)
            verify("All sixteen measured theme transitions applied", transitions.get() == 16)
            checks.put("completed", true)
        } catch (error: Throwable) {
            checks.put("completed", false).put("errorType", error.javaClass.simpleName)
            File(folder, "failure.txt").printWriter().use { error.printStackTrace(it) }
        } finally {
            probe.runOnMainSync {
                settingsMonitor.lastActivity?.takeUnless { it.isFinishing }?.finish()
                host?.takeUnless { it.isFinishing }?.finish()
                SettingsDomains.immortal.apply(context, JSONObject().put("settingsTheme", original))
                // Test cleanup restores absence too; all production writes still use the registry.
                if (!keyExisted) prefs.edit().remove("settings_theme").apply()
            }
            checks.put("originalAppearanceRestored", ImmortalSettings.settingsTheme(context) == original &&
                prefs.contains("settings_theme") == keyExisted)
            probe.removeMonitor(homeMonitor)
            probe.removeMonitor(settingsMonitor)
        }
        File(folder, "report.json").writeText(checks.toString())
        return Bundle().apply {
            putBoolean("appearanceCompleted", checks.optBoolean("completed") &&
                checks.optBoolean("originalAppearanceRestored"))
            putString("assertions", checks.toString())
            putString("evidence", "cache/portal-ux-e2e/${folder.name}")
        }
    }

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let {
            find(it, label)?.let { found -> return found }
        }
        return null
    }

    private fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let {
            editable(it)?.let { found -> return found }
        }
        return null
    }

    private fun android.content.SharedPreferences.registeredThemeTransitionCounter(
        changed: () -> Unit,
    ): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "settings_theme") changed()
        }
        registerOnSharedPreferenceChangeListener(listener)
        return { unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
