/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.ApprovedDarkPalette
import com.immortal.launcher.ui.theme.ApprovedLightPalette
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Fixed public inputs and independent expectations; never invokes a speed-test worker. */
internal object SpeedResultsFixtures {
    const val NONCE = "speed_results_fixture_nonce"
    const val PACKAGE = "com.immortal.launcher.debug.test"
    const val ACTIVITY = "com.immortal.launcher.PinFixtureActivity"
    val labels = listOf("Ping", "Jitter", "Download", "Upload")
    const val LIVE = "Live estimates. The test is still running."
    const val STOPPED = "Test stopped. These are partial estimates."
    const val INCOMPLETE = "Some measurements are unavailable. Completed results are shown."
    data class Case(val name: String, val outcome: SpeedTestDisplayStatus,
        val values: SpeedTestValues, val mask: Int, val expected: List<String>, val caption: String?)
    private fun fmt(value: Double, unit: String) = String.format(Locale.getDefault(), "%.1f %s", value, unit)
    private fun ms(value: Double) = fmt(value, "ms")
    private fun mbps(value: Double) = fmt(value, "Mbps")
    fun cases(): List<Case> = listOf(
        Case("ready", SpeedTestDisplayStatus.READY, SpeedTestValues(13.2, 2.5, 42.3, 8.4), 0,
            listOf("—", "—", "—", "—"), null),
        Case("live", SpeedTestDisplayStatus.RUNNING, SpeedTestValues(13.2, 2.5, 42.3, Double.POSITIVE_INFINITY), 0,
            listOf(ms(13.2) + " · Live", ms(2.5) + " · Live", mbps(42.3) + " · Live", "—"), LIVE),
        Case("cancelled_zero_jitter", SpeedTestDisplayStatus.STOPPED, SpeedTestValues(13.2, 0.0, 42.3, 0.0), 4,
            listOf(ms(13.2) + " · Partial", ms(0.0) + " · Partial", mbps(42.3) + " · Partial", "Not measured"), STOPPED),
        Case("failed_ping", SpeedTestDisplayStatus.INCOMPLETE, SpeedTestValues(13.2, 2.5, 42.3, 8.4), 1,
            listOf("Unavailable", "Unavailable", mbps(42.3), mbps(8.4)), INCOMPLETE),
        Case("failed_download_nonfinite_upload", SpeedTestDisplayStatus.INCOMPLETE,
            SpeedTestValues(13.2, 0.0, 42.3, Double.POSITIVE_INFINITY), 2,
            listOf(ms(13.2), ms(0.0), "Unavailable", "Unavailable"), INCOMPLETE),
        Case("failed_upload_nonfinite_jitter", SpeedTestDisplayStatus.INCOMPLETE,
            SpeedTestValues(13.2, Double.NaN, 42.3, 8.4), 4,
            listOf(ms(13.2), "Unavailable", mbps(42.3), "Unavailable"), INCOMPLETE),
        Case("multiple_failed_nonfinite_ping", SpeedTestDisplayStatus.INCOMPLETE,
            SpeedTestValues(Double.NEGATIVE_INFINITY, 2.5, -1.0, 0.0), 6,
            listOf("Unavailable", "Unavailable", "Unavailable", "Unavailable"), INCOMPLETE),
        Case("complete_zero_jitter", SpeedTestDisplayStatus.COMPLETE, SpeedTestValues(13.2, 0.0, 42.3, 8.4), 0,
            listOf(ms(13.2), ms(0.0), mbps(42.3), mbps(8.4)), null)
    )
    fun marker(nonce: String, name: String, dark: Boolean) =
        "Speed fixture $nonce $name ${if (dark) "dark" else "light"}"
}

/** Hosted by the existing exported test Activity, avoiding any production or manifest change. */
internal class SpeedResultsFixtureHost(private val activity: ComponentActivity, private val nonce: String) {
    private var name by mutableStateOf("ready")
    private var dark by mutableStateOf(false)
    fun apply(next: Intent) {
        if (next.getStringExtra(SpeedResultsFixtures.NONCE) != nonce) return
        if (next.getBooleanExtra("speed_results_fixture_finish", false)) {
            activity.finishAndRemoveTask()
            return
        }
        val wanted = next.getStringExtra("speed_results_fixture_case")
        if (SpeedResultsFixtures.cases().none { it.name == wanted }) return
        name = wanted!!
        dark = next.getBooleanExtra("speed_results_fixture_dark", false)
    }
    fun show(next: Intent) {
        apply(next)
        activity.setContent {
            val palette = if (dark) ApprovedDarkPalette else ApprovedLightPalette
            CompositionLocalProvider(LocalApprovedPalette provides palette) {
                key(name, dark) {
                    val fixture = SpeedResultsFixtures.cases().single { it.name == name }
                    Column(Modifier.fillMaxSize().background(palette.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(SpeedResultsFixtures.marker(nonce, name, dark), color = palette.secondary)
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                            ToolsSpeedResults(fixture.values, fixture.outcome, fixture.mask)
                        }
                    }
                }
            }
        }
    }
}

/** Public native assertions are accumulated even when the intentionally red baseline fails. */
internal object SpeedResultsUiDeviceChecks {
    private data class VisibleText(val text: String, val bounds: Rect)
    fun run(probe: Instrumentation): Bundle {
        val assertions = JSONArray()
        val results = JSONArray()
        val fixtures = SpeedResultsFixtures.cases()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        var automation: UiAutomation? = null
        var started = false
        var checkCount = 0
        var passedCount = 0
        var uiPassed = 0
        var executed = 0
        fun record(case: String, theme: String, label: String, passed: Boolean) {
            checkCount++
            if (passed) passedCount++
            assertions.put(JSONObject().put("case", case).put("theme", theme)
                .put("assertion", label).put("passed", passed))
        }
        fun launch(name: String, dark: Boolean, finish: Boolean = false) = Intent()
            .setComponent(ComponentName(SpeedResultsFixtures.PACKAGE, SpeedResultsFixtures.ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SpeedResultsFixtures.NONCE, nonce).putExtra("speed_results_fixture_case", name)
            .putExtra("speed_results_fixture_dark", dark).putExtra("speed_results_fixture_finish", finish)
        // Only allowlisted public text and on-screen rectangles survive traversal, never raw UI.
        fun read(allowlist: Set<String>): List<VisibleText> {
            val root = automation?.rootInActiveWindow ?: return emptyList()
            try {
                if (root.packageName?.toString() != SpeedResultsFixtures.PACKAGE) return emptyList()
                val screen = Rect().also(root::getBoundsInScreen)
                val found = mutableListOf<VisibleText>()
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 40) return
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (node.isVisibleToUser && !bounds.isEmpty && screen.contains(bounds)) {
                        for (raw in listOf(node.text, node.contentDescription)) {
                            raw?.toString()?.lines()?.filter { it in allowlist }?.forEach {
                                found += VisibleText(it, Rect(bounds))
                            }
                        }
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { visit(child, depth + 1) } finally { child.recycle() }
                    }
                }
                visit(root, 0)
                return found
            } finally { root.recycle() }
        }
        fun scroll(back: Boolean): Boolean {
            val root = automation?.rootInActiveWindow ?: return false
            try {
                if (root.packageName?.toString() != SpeedResultsFixtures.PACKAGE) return false
                fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
                    if (depth > 40) return false
                    if (node.isScrollable && node.performAction(if (back)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
                    }
                    return false
                }
                return visit(root, 0)
            } finally { root.recycle() }
        }
        val automationError = runCatching {
            automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }.exceptionOrNull()
        record("session", "both", "accessibility services retained", automation != null && automationError == null)
        for (dark in listOf(false, true)) for (fixture in fixtures) {
            val theme = if (dark) "dark" else "light"
            val before = passedCount
            val marker = SpeedResultsFixtures.marker(nonce, fixture.name, dark)
            val captions = setOf(SpeedResultsFixtures.LIVE, SpeedResultsFixtures.STOPPED, SpeedResultsFixtures.INCOMPLETE)
            val publicValues = listOf(13.2 to "ms", 2.5 to "ms", 0.0 to "ms", 42.3 to "Mbps", 8.4 to "Mbps")
                .flatMap { (value, unit) ->
                    val formatted = String.format(Locale.getDefault(), "%.1f %s", value, unit)
                    listOf(formatted, "$formatted · Live", "$formatted · Partial")
                }.toSet() + setOf("—", "Unavailable", "Not measured")
            val allowlist = SpeedResultsFixtures.labels.toSet() + publicValues + captions + setOf(marker, "Results")
            var launchError: Throwable? = null
            val seen = mutableListOf<VisibleText>()
            val matchedRows = mutableSetOf<String>()
            var marked = false
            try {
                val intent = launch(fixture.name, dark)
                if (!started) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                probe.context.startActivity(intent)
                started = true
                val deadline = SystemClock.elapsedRealtime() + if (executed == 0) 6_000 else 2_000
                do {
                    marked = read(setOf(marker)).any { it.text == marker }
                    if (!marked) SystemClock.sleep(65)
                } while (!marked && SystemClock.elapsedRealtime() < deadline)
                // Re-read each scroll position; row/value pairing below prevents repeated
                // placeholders from satisfying a different metric's expectation.
                if (marked) {
                    repeat(3) { scroll(back = true) }
                    repeat(5) {
                        val frame = read(allowlist)
                        seen += frame
                        for ((index, label) in SpeedResultsFixtures.labels.withIndex()) {
                            if (frame.any { metric -> metric.text == label && frame.any { value ->
                                value.text == fixture.expected[index] &&
                                    value.bounds.top < metric.bounds.bottom && metric.bounds.top < value.bounds.bottom
                            } }) matchedRows += label
                        }
                        if (scroll(back = false)) SystemClock.sleep(90)
                    }
                }
            } catch (failure: Throwable) { launchError = failure }
            record(fixture.name, theme, "public fixture marker visible within screen", marked && launchError == null)
            record(fixture.name, theme, "production Results label visible within screen", seen.any { it.text == "Results" })
            for (label in SpeedResultsFixtures.labels) {
                record(fixture.name, theme, "$label label visible within screen", seen.any { it.text == label })
                record(fixture.name, theme, "$label expected public value visible in metric row", label in matchedRows)
            }
            val visibleCaptions = seen.filter { it.text in captions }.map { it.text }.toSet()
            record(fixture.name, theme, "status caption contract", visibleCaptions ==
                (fixture.caption?.let { setOf(it) } ?: emptySet<String>()))
            val qualifiers = setOf(" · Live", " · Partial")
            val permitted = when (fixture.outcome) {
                SpeedTestDisplayStatus.RUNNING -> " · Live"
                SpeedTestDisplayStatus.STOPPED -> " · Partial"
                else -> null
            }
            record(fixture.name, theme, "no wrong status qualifier", seen.none { value ->
                qualifiers.any { value.text.endsWith(it) && it != permitted }
            })
            val passed = passedCount - before == 12
            if (passed) uiPassed++
            executed++
            results.put(JSONObject().put("case", fixture.name).put("theme", theme).put("passed", passed)
                .put("actualProductionComposable", true).put("checkCount", 12)
                .put("passedCount", passedCount - before).apply {
                    launchError?.let { put("failureType", it.javaClass.simpleName) }
                })
        }
        var closed = !started
        if (started) runCatching {
            probe.context.startActivity(launch(fixtures.last().name, true, finish = true))
            val deadline = SystemClock.elapsedRealtime() + 3_000
            do {
                val root = automation?.rootInActiveWindow
                try { closed = root == null || root.packageName?.toString() != SpeedResultsFixtures.PACKAGE }
                finally { root?.recycle() }
                if (!closed) SystemClock.sleep(65)
            } while (!closed && SystemClock.elapsedRealtime() < deadline)
        }
        record("session", "both", "owned public fixture closed", closed)
        val failureCount = checkCount - passedCount
        val completed = executed == fixtures.size * 2
        val passed = completed && failureCount == 0
        val json = JSONObject().put("completed", completed).put("passed", passed)
            .put("checkCount", checkCount).put("passedCount", passedCount).put("failureCount", failureCount)
            .put("uiCases", executed).put("uiPassed", uiPassed).put("plannedUiCases", fixtures.size * 2)
            .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
            .put("rawUiPersisted", false).put("screenshotsCaptured", false).put("cases", results)
            .put("assertions", assertions)
        return Bundle().apply {
            putString("speedResultsJson", json.toString())
            putBoolean("speedResultsCompleted", completed)
            putBoolean("speedResultsPassed", passed)
            putInt("checkCount", checkCount); putInt("passedCount", passedCount)
            putInt("failureCount", failureCount); putInt("uiCases", executed); putInt("uiPassed", uiPassed)
            putBoolean("fixturesPublic", true); putBoolean("networkUsed", false); putBoolean("stateMutations", false)
        }
    }
}
