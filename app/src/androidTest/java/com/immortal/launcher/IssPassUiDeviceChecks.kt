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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.ApprovedDarkPalette
import com.immortal.launcher.ui.theme.ApprovedLightPalette
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private object IssUiFixtures {
    const val PACKAGE = "com.immortal.launcher.debug.test"
    const val ACTIVITY = "com.immortal.launcher.IssPassFixtureActivity"
    const val ENDED = "These predictions have ended. Refresh passes for the next viewing times."
    const val EMPTY = "No passes above 10° are predicted here in the next 36 hours."
    const val ERROR = "ISS passes couldn’t be calculated. Refresh passes to try again."
    const val UNAVAILABLE = "ISS orbit data isn’t available. Check your connection, then refresh passes."
    val cases = listOf("live_expiry", "hidden_return", "unmount_revisit", "empty_success",
        "unavailable", "error_retained", "loading")
    // Public synthetic direction names distinguish rows even when minute-rounded times coincide.
    const val NEXT_A = "Rises Public A · peak 41° to Public A peak · sets Public A end"
    const val NEXT_B = "Rises Public B · peak 52° to Public B peak · sets Public B end"
    const val ROW_C = "Public C → Public C end · peak 23°"
    const val ROW_A = "Public A → Public A end · peak 41°"
    const val ROW_B = "Public B → Public B end · peak 52°"
    fun passes(now: Long, short: Boolean) = listOf(
        IssPasses.Pass(now - 300, now + 300, now + if (short) 1_500 else 60_000,
            41, "Public A", "Public A peak", "Public A end", true),
        IssPasses.Pass(now + 1_600, now + 2_000, now + if (short) 3_000 else 90_000,
            52, "Public B", "Public B peak", "Public B end", true))
}

/** One external test-owned task. No source loading, target storage, or preference calls. */
class IssPassFixtureActivity : ComponentActivity() {
    private var nonce = ""
    private var scenario by mutableStateOf("live_expiry")
    private var dark by mutableStateOf(false)
    private var passes by mutableStateOf<List<IssPasses.Pass>?>(null)
    private var foregroundAt by mutableStateOf(0L)
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var active by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var started by mutableStateOf(false)
    private var refreshes by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra("iss_fixture_nonce").orEmpty()
        if (!Regex("[a-f0-9]{32}").matches(nonce)) { finishAndRemoveTask(); return }
        apply(intent)
        setContent {
            val palette = if (dark) ApprovedDarkPalette else ApprovedLightPalette
            CompositionLocalProvider(LocalApprovedPalette provides palette) {
                Column(Modifier.fillMaxSize().background(palette.background).padding(12.dp)) {
                    Text(marker(), color = palette.secondary)
                    Text("Fixture refreshes: $refreshes", color = palette.secondary)
                    Text(if (!mounted) "Fixture unmounted" else if (!active) "Fixture hidden" else "Fixture active",
                        color = palette.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApprovedHomeTextButton("Start fixture passes") { if (!started) startPasses() }
                        ApprovedHomeTextButton(if (active) "Hide fixture page" else "Show fixture page") { active = !active }
                        ApprovedHomeTextButton(if (mounted) "Unmount fixture panel" else "Revisit fixture panel") { mounted = !mounted }
                        ApprovedHomeTextButton("Close ISS fixture") { finishAndRemoveTask() }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().then(if (active) Modifier else
                        Modifier.alpha(0f).clearAndSetSemantics {})) {
                        CompositionLocalProvider(LocalApprovedLauncherPageActive provides active) {
                            if (mounted) key(scenario, dark) {
                                ToolsIssPanel(passes = passes, foregroundAt = foregroundAt,
                                    loading = loading, message = message, onRefresh = { refreshes++ })
                            }
                        }
                    }
                }
            }
        }
    }
    private fun marker() = "ISS fixture $nonce $scenario ${if (dark) "dark" else "light"}"
    private fun startPasses() {
        foregroundAt = System.currentTimeMillis()
        val publicPasses = IssUiFixtures.passes(foregroundAt, scenario != "error_retained")
        passes = if (scenario == "error_retained") listOf(
            IssPasses.Pass(foregroundAt - 800, foregroundAt - 100, foregroundAt + 1_500,
                23, "Public C", "Public C peak", "Public C end", false)) + publicPasses else publicPasses
        started = true
    }
    private fun apply(next: Intent) {
        scenario = next.getStringExtra("iss_fixture_case").orEmpty()
        require(scenario in IssUiFixtures.cases)
        dark = next.getBooleanExtra("iss_fixture_dark", false)
        active = true; mounted = true; started = false; refreshes = 0
        foregroundAt = System.currentTimeMillis()
        loading = scenario == "loading"
        passes = if (scenario in setOf("unavailable", "loading")) null else emptyList()
        message = when (scenario) {
            "empty_success" -> IssUiFixtures.EMPTY
            "unavailable" -> IssUiFixtures.UNAVAILABLE
            "error_retained" -> IssUiFixtures.ERROR
            else -> null
        }
    }
    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        if (next.getStringExtra("iss_fixture_nonce") != nonce) return
        intent = next
        if (next.getBooleanExtra("iss_fixture_finish", false)) finishAndRemoveTask() else apply(next)
    }
    override fun onDestroy() { passes = null; nonce = ""; super.onDestroy() }
}

/** Actual panel rendering only: no claims about Tools navigation, location, orbit, or loaders. */
internal object IssPassUiDeviceChecks {
    private data class Seen(val bounds: Rect, val enabled: Boolean, val actionable: Boolean)
    fun run(probe: Instrumentation, options: Bundle): Bundle {
        val mode = options.getString("mode", "candidate")
        val results = JSONArray()
        val checks = JSONObject()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        // One budget includes setup, every case, and cleanup. No per-case clock reset.
        val deadline = SystemClock.elapsedRealtime() + 60_000
        val workDeadline = deadline - 2_000
        var automation: UiAutomation? = null
        var launched = false
        var completed = false
        var allPassed = true
        var failCount = 0
        var unexpectedCaseExceptions = 0
        var publicFailureStage = "none"
        var publicFailureType = "none"
        var current = "live_expiry"
        var dark = false
        fun launch(finish: Boolean = false) = Intent().setComponent(ComponentName(
            IssUiFixtures.PACKAGE, IssUiFixtures.ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("iss_fixture_nonce", nonce).putExtra("iss_fixture_case", current)
            .putExtra("iss_fixture_dark", dark).putExtra("iss_fixture_finish", finish)
        fun marker() = "ISS fixture $nonce $current ${if (dark) "dark" else "light"}"
        fun read(labels: Set<String>): Map<String, Seen> {
            val root = automation?.rootInActiveWindow ?: return emptyMap()
            try {
                if (root.packageName?.toString() != IssUiFixtures.PACKAGE) return emptyMap()
                val found = mutableMapOf<String, Seen>()
                var nodes = 0
                fun visit(node: AccessibilityNodeInfo, parent: Seen? = null, depth: Int = 0, ancestorEnabled: Boolean = true) {
                    if (depth > 45 || ++nodes > 1_500) return
                    val rect = Rect().also(node::getBoundsInScreen)
                    val enabled = node.isEnabled && ancestorEnabled
                    val action = if (node.isClickable) Seen(rect, enabled, true) else parent
                    val texts = listOf(node.text, node.contentDescription).flatMap { it?.toString()?.lines().orEmpty() }
                    if (node.isVisibleToUser && !rect.isEmpty) for (label in labels) {
                        if (label in texts) {
                            val candidate = action ?: Seen(rect, enabled, false)
                            val previous = found[label]
                            if (previous == null || candidate.actionable || !candidate.enabled) found[label] = candidate
                        }
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { visit(child, action, depth + 1, enabled) } finally { child.recycle() }
                    }
                }
                visit(root)
                return found
            } finally { root.recycle() }
        }
        fun waitFor(milliseconds: Long = 1_000, condition: () -> Boolean): Boolean {
            val end = minOf(workDeadline, SystemClock.elapsedRealtime() + milliseconds)
            do {
                if (condition()) return true
                if (SystemClock.elapsedRealtime() >= end) return false
                SystemClock.sleep(50)
            } while (true)
        }
        fun requireUi(label: String, timeoutMillis: Long = 1_500, condition: () -> Boolean) {
            publicFailureStage = label
            check(waitFor(timeoutMillis, condition = condition)) { label }
        }
        fun tap(label: String) {
            requireUi("fixture control") { read(setOf(marker(), label)).keys.containsAll(setOf(marker(), label)) }
            val root = checkNotNull(automation?.rootInActiveWindow)
            try {
                check(root.packageName?.toString() == IssUiFixtures.PACKAGE)
                var visited = 0
                fun click(node: AccessibilityNodeInfo, parent: AccessibilityNodeInfo? = null, depth: Int = 0): Boolean {
                    if (depth > 45 || ++visited > 1_500) return false
                    val owner = if (node.isClickable) node else parent
                    val text = listOf(node.text, node.contentDescription).flatMap { it?.toString()?.lines().orEmpty() }
                    if (label in text && node.isVisibleToUser && owner?.isEnabled == true) {
                        val bounds = Rect().also(owner::getBoundsInScreen)
                        return !bounds.isEmpty && owner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { if (click(child, owner, depth + 1)) return true } finally { child.recycle() }
                    }
                    return false
                }
                check(click(root)) { "fixture action" }
            } finally { root.recycle() }
        }
        fun checkUi(label: String, value: Boolean): Boolean {
            checks.put("$current ${if (dark) "dark" else "light"} $label", value)
            allPassed = allPassed && value
            if (!value) failCount++
            return value
        }
        fun until(at: Long) {
            check(at < workDeadline)
            while (SystemClock.elapsedRealtime() < at) SystemClock.sleep(minOf(50, at - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
        try {
            require(mode == "baseline" || mode == "candidate")
            automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            for (palette in listOf(false, true)) {
                dark = palette
                for (name in IssUiFixtures.cases) {
                    check(SystemClock.elapsedRealtime() < workDeadline)
                    current = name
                    val intent = launch()
                    if (!launched) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    probe.context.startActivity(intent); launched = true
                    requireUi("fixture marker", 6_000) { marker() in read(setOf(marker())) }
                    var passed = true
                    fun assertion(label: String, value: Boolean) { passed = checkUi(label, value) && passed }
                    if (name in setOf("live_expiry", "hidden_return", "unmount_revisit", "error_retained")) {
                        // Anchor after the native action; allow 150 ms for delivery at the final end.
                        tap("Start fixture passes")
                        val afterTap = SystemClock.elapsedRealtime()
                        requireUi("initial next A") { IssUiFixtures.NEXT_A in read(setOf(IssUiFixtures.NEXT_A)) }
                        assertion("initial next A", true)
                        if (name == "hidden_return" || name == "unmount_revisit") {
                            check(SystemClock.elapsedRealtime() - afterTap < 1_200)
                            tap(if (name == "hidden_return") "Hide fixture page" else "Unmount fixture panel")
                            requireUi("panel hidden before expiry") {
                                val label = if (name == "hidden_return") "Fixture hidden" else "Fixture unmounted"
                                label in read(setOf(label)) && read(setOf(IssUiFixtures.NEXT_A)).isEmpty()
                            }
                            until(afterTap + 3_150)
                            tap(if (name == "hidden_return") "Show fixture page" else "Revisit fixture panel")
                        } else if (name == "live_expiry") {
                            // No taps, scrolls, intents, callbacks, or fixture state changes until both ends.
                            until(afterTap + 1_600)
                            assertion("A removed and B promoted without refresh", waitFor(650) {
                                val seen = read(setOf(IssUiFixtures.NEXT_A, IssUiFixtures.NEXT_B, IssUiFixtures.ROW_A))
                                IssUiFixtures.NEXT_B in seen && IssUiFixtures.NEXT_A !in seen && IssUiFixtures.ROW_A !in seen
                            })
                            until(afterTap + 3_150)
                        }
                        if (name != "error_retained") {
                            assertion("truthful ended state", waitFor(650) { IssUiFixtures.ENDED in read(setOf(IssUiFixtures.ENDED)) })
                            assertion("ended is distinct from successful empty", read(setOf(IssUiFixtures.EMPTY,
                                IssUiFixtures.NEXT_A, IssUiFixtures.NEXT_B, IssUiFixtures.ROW_A, IssUiFixtures.ROW_B)).isEmpty())
                        } else {
                            assertion("earlier non-visible retained row initially present", IssUiFixtures.ROW_C in read(setOf(IssUiFixtures.ROW_C)))
                            until(afterTap + 1_600)
                            assertion("non-visible row expires while visible next stays A", waitFor(650) {
                                val seen = read(setOf(IssUiFixtures.ROW_C, IssUiFixtures.NEXT_A, IssUiFixtures.ERROR))
                                IssUiFixtures.ROW_C !in seen && IssUiFixtures.NEXT_A in seen && IssUiFixtures.ERROR in seen
                            })
                            assertion("retained next usable with error", read(setOf(IssUiFixtures.NEXT_A, IssUiFixtures.ERROR)).size == 2)
                        }
                    } else {
                        val expected = when (name) {
                            "empty_success" -> IssUiFixtures.EMPTY
                            "unavailable" -> IssUiFixtures.UNAVAILABLE
                            else -> "Finding passes…"
                        }
                        assertion("public status rendered", waitFor { expected in read(setOf(expected)) })
                        assertion("status distinct from ended", read(setOf(IssUiFixtures.ENDED)).isEmpty())
                        if (name == "unavailable") assertion("unavailable distinct from empty", read(setOf(IssUiFixtures.EMPTY)).isEmpty())
                    }
                    assertion("no automatic refresh callback", "Fixture refreshes: 0" in read(setOf("Fixture refreshes: 0")))
                    if (name == "loading") {
                        val loading = read(setOf("Finding passes…"))["Finding passes…"]
                        assertion("loading refresh disabled", loading != null && !loading.enabled)
                    } else {
                        tap("Refresh passes")
                        assertion("explicit refresh callback exactly once", waitFor { "Fixture refreshes: 1" in read(setOf("Fixture refreshes: 1")) })
                    }
                    results.put(JSONObject().put("case", name).put("darkPalette", dark).put("passed", passed))
                }
            }
            completed = results.length() == 14
        } catch (failure: Throwable) {
            publicFailureType = failure.javaClass.simpleName
            unexpectedCaseExceptions++
            checks.put("bounded fixture execution completed", false)
        } finally {
            if (launched) {
                var closed = false
                try {
                    probe.context.startActivity(launch(finish = true))
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val root = automation?.rootInActiveWindow
                        try { closed = root == null || root.packageName?.toString() != IssUiFixtures.PACKAGE }
                        finally { root?.recycle() }
                        if (closed) break
                        SystemClock.sleep(50)
                    }
                } catch (_: Throwable) { closed = false }
                checks.put("exact nonce owned task cleaned", closed)
                completed = completed && closed
            }
        }
        val report = JSONObject().put("mode", mode).put("completed", completed)
            .put("passed", completed && allPassed).put("cases", results).put("caseCount", results.length())
            .put("plannedCaseCount", 14).put("assertions", checks).put("deadlineMs", 60_000)
            .put("publicFailureStage", publicFailureStage).put("publicFailureType", publicFailureType)
            .put("failCount", failCount).put("unexpectedCaseExceptions", unexpectedCaseExceptions)
            .put("productionPanelUsed", true).put("nativeAccessibilityOnly", true)
            .put("rawTreesPersisted", false).put("screenshotsCaptured", false)
            .put("householdPreferencesReadOrWritten", false).put("networkRequests", 0)
            .put("realClockMutated", false).put("locationOrbitLoadProven", false)
            .put("fullToolsNavigationProven", false).put("realActivityPauseProven", false)
        return Bundle().apply {
            // Baseline completion reports observation booleans; candidate requires the contract.
            putBoolean("issPassUiCompleted", completed && (mode == "baseline" || allPassed))
            putString("issPassUiResult", report.toString())
        }
    }
}
