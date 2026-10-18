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
import android.view.InputDevice
import android.view.MotionEvent
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
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Public fixtures only. No clock, preferences, network, or household source lookup. */
private object TodayCalendarFixtures {
  const val PACKAGE = "com.immortal.launcher.debug.test"
  const val ACTIVITY = "com.immortal.launcher.TodayCalendarFixtureActivity"
  const val SOURCE = "https://example.invalid/public-calendar-a.ics"
  const val OTHER_SOURCE = "https://example.invalid/public-calendar-b.ics"
  val names = listOf("today", "tomorrow", "future_year", "overnight", "all_day", "empty",
      "unavailable", "loading", "saved_event", "changed_source", "setup_add", "retry_transition")
  private val now = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
  private fun event(start: String, end: String, title: String, allDay: Boolean = false) =
      CalendarFeed.Event(Instant.parse(start).toEpochMilli(), Instant.parse(end).toEpochMilli(), title, allDay)
  fun state(name: String): TodayCalendarState {
    val events = when (name) {
      "today" -> listOf(event("2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "Public today event"))
      "tomorrow" -> listOf(event("2026-10-19T14:00:00Z", "2026-10-19T15:00:00Z", "Public tomorrow event"))
      "future_year" -> listOf(event("2027-01-03T09:00:00Z", "2027-01-03T10:00:00Z", "Public next-year event"))
      "overnight" -> listOf(event("2026-10-17T23:00:00Z", "2026-10-18T13:00:00Z", "Public overnight event"))
      "all_day" -> listOf(event("2026-10-17T00:00:00Z", "2026-10-20T00:00:00Z", "Public all-day event", true))
      "saved_event", "changed_source" -> listOf(event("2026-10-18T16:00:00Z", "2026-10-18T17:00:00Z", "Public saved event"))
      else -> emptyList()
    }
    val state = prepareTodayCalendarState(if (name == "setup_add") null else SOURCE,
        CalendarFeed.Result(events, name in setOf("saved_event", "changed_source", "unavailable", "retry_transition")),
        now, true, TimeZone.getTimeZone("UTC"), Locale.US)
    return when (name) {
      "loading" -> state.copy(refreshing = true)
      "changed_source" -> state.forSource(OTHER_SOURCE)
      else -> state
    }
  }
}

/** Separate test task/process; invokes the actual production composable with public data. */
class TodayCalendarFixtureActivity : ComponentActivity() {
  private var nonce = ""
  private var scenario by mutableStateOf("today")
  private var dark by mutableStateOf(false)
  private var state by mutableStateOf(TodayCalendarState())
  private var retries by mutableStateOf(0)
  private var adds by mutableStateOf(0)
  private var gate = TodayCalendarRefreshGate()
  private var coalesced by mutableStateOf(false)
  private var consumedOnce by mutableStateOf(false)
  private var oldSourceCleared by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    nonce = intent.getStringExtra("today_fixture_nonce").orEmpty()
    if (!Regex("[a-f0-9]{32}").matches(nonce)) { finish(); return }
    apply(intent)
    setContent {
      val palette = if (dark) ApprovedDarkPalette else ApprovedLightPalette
      CompositionLocalProvider(LocalApprovedPalette provides palette) {
        key(scenario, dark) {
          Box(Modifier.fillMaxSize().background(palette.background)) {
            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(16.dp)) {
              Text(marker(), color = palette.secondary)
              Spacer(Modifier.height(12.dp))
              ApprovedHomeSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                  TodayCalendarSection(state, onRetry = {
                    if (gate.request(state, state.url)) {
                      retries++
                      val duplicateRejected = !gate.request(state, state.url)
                      state = state.copy(refreshing = true)
                      coalesced = duplicateRejected && !gate.request(state, state.url) && gate.revision == 1
                    }
                  }, onAdd = {
                    adds++
                    state = TodayCalendarFixtures.state("empty")
                  })
                }
              }
              Spacer(Modifier.height(16.dp))
              Text("Fixture retries: $retries", color = palette.secondary)
              Text("Fixture adds: $adds", color = palette.secondary)
              if (scenario == "retry_transition") {
                Text("Gate coalesced: $coalesced", color = palette.secondary)
                Text("Gate consumed once: $consumedOnce", color = palette.secondary)
                ApprovedHomeTextButton("Complete fixture refresh") {
                  val first = gate.consume(state.url)
                  consumedOnce = first && !gate.consume(state.url) && !gate.pending(state.url)
                  state = TodayCalendarFixtures.state("today")
                }
              }
              if (scenario == "changed_source") Text("Gate old source cleared: $oldSourceCleared", color = palette.secondary)
              ApprovedHomeTextButton("Close calendar fixture") { finishAndRemoveTask() }
            }
          }
        }
      }
    }
  }
  private fun marker() = "Calendar fixture $nonce $scenario ${if (dark) "dark" else "light"}"
  private fun apply(next: Intent) {
    val wanted = next.getStringExtra("today_fixture_case").orEmpty()
    require(wanted in TodayCalendarFixtures.names)
    scenario = wanted; dark = next.getBooleanExtra("today_fixture_dark", false)
    retries = 0; adds = 0; gate = TodayCalendarRefreshGate()
    coalesced = false; consumedOnce = false; oldSourceCleared = false
    if (wanted == "changed_source") {
      val requested = gate.request(TodayCalendarFixtures.state("saved_event"), TodayCalendarFixtures.SOURCE)
      oldSourceCleared = requested && !gate.consume(TodayCalendarFixtures.OTHER_SOURCE) &&
          !gate.pending(TodayCalendarFixtures.SOURCE)
    }
    state = TodayCalendarFixtures.state(wanted)
  }
  override fun onNewIntent(next: Intent) {
    super.onNewIntent(next)
    if (next.getStringExtra("today_fixture_nonce") != nonce) return
    intent = next
    if (next.getBooleanExtra("today_fixture_finish", false)) finishAndRemoveTask() else apply(next)
  }
  override fun onDestroy() {
    state = TodayCalendarState(); nonce = ""
    super.onDestroy()
  }
}

/** Candidate fixture proof, deliberately separate from actual Home/baseline acceptance. */
internal object TodayCalendarUiDeviceChecks {
  private data class Control(val bounds: Rect, val enabled: Boolean, val actionable: Boolean)
  fun run(probe: Instrumentation): Bundle {
    val cases = JSONArray()
    val checks = JSONObject()
    var automation: UiAutomation? = null
    var started = false
    var completed = false
    val nonce = UUID.randomUUID().toString().replace("-", "")
    val deadline = SystemClock.elapsedRealtime() + 55_000
    var current = "today"
    var dark = false
    var maxVisibleTexts = 0
    fun intent(finish: Boolean = false) = Intent().setComponent(ComponentName(
        TodayCalendarFixtures.PACKAGE, TodayCalendarFixtures.ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("today_fixture_nonce", nonce).putExtra("today_fixture_case", current)
        .putExtra("today_fixture_dark", dark).putExtra("today_fixture_finish", finish)
    fun marker() = "Calendar fixture $nonce $current ${if (dark) "dark" else "light"}"
    fun demand(label: String, value: Boolean) {
      checks.put(label, value)
      check(value) { label }
    }
    fun read(labels: Set<String>): Map<String, Control> {
      val root = automation?.rootInActiveWindow ?: return emptyMap()
      try {
        if (root.packageName?.toString() != TodayCalendarFixtures.PACKAGE) return emptyMap()
        val found = mutableMapOf<String, Control>()
        fun visit(node: AccessibilityNodeInfo, parent: Control? = null, parentEnabled: Boolean = true) {
          val rect = Rect().also(node::getBoundsInScreen)
          val enabled = parentEnabled && node.isEnabled
          val action = if (node.isClickable) Control(rect, enabled, true) else parent?.copy(enabled = parent.enabled && enabled)
          for (raw in listOf(node.text, node.contentDescription)) {
            val lines = raw?.toString()?.lines().orEmpty()
            for (label in labels) if (label in lines && node.isVisibleToUser && !rect.isEmpty) {
              found[label] = action ?: Control(rect, enabled, false)
            }
          }
          for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
            try { visit(child, action, enabled) } finally { child.recycle() }
          }
        }
        visit(root)
        maxVisibleTexts = maxOf(maxVisibleTexts, found.size)
        return found
      } finally { root.recycle() }
    }
    fun await(label: String, condition: () -> Boolean) {
      val end = minOf(deadline, SystemClock.elapsedRealtime() + 5_000)
      while (SystemClock.elapsedRealtime() < end) {
        if (condition()) { demand(label, true); return }
        SystemClock.sleep(65)
      }
      demand(label, false)
    }
    fun scroll(back: Boolean = false) {
      val root = automation?.rootInActiveWindow ?: return
      try {
        if (root.packageName?.toString() != TodayCalendarFixtures.PACKAGE) return
        fun visit(node: AccessibilityNodeInfo): Boolean {
          if (node.isScrollable && node.performAction(if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
          for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
            try { if (visit(child)) return true } finally { child.recycle() }
          }
          return false
        }
        visit(root)
      } finally { root.recycle() }
    }
    fun tap(label: String, disabled: Boolean = false) {
      var target: Control? = null
      await("$current control bounds available") {
        target = read(setOf(label))[label]
        if (target == null) scroll()
        target != null
      }
      val control = checkNotNull(target)
      demand("$current control enabled state", control.enabled != disabled)
      if (!disabled) demand("$current control actionable", control.actionable)
      val down = SystemClock.uptimeMillis()
      for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        if (action == MotionEvent.ACTION_UP) SystemClock.sleep(35)
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            control.bounds.exactCenterX(), control.bounds.exactCenterY(), 0).apply {
          source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { demand("$current public fixture tap injected", checkNotNull(automation).injectInputEvent(event, true)) }
        finally { event.recycle() }
      }
    }
    try {
      automation = probe.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
      for (palette in listOf(false, true)) {
        dark = palette
        for (name in TodayCalendarFixtures.names) {
          current = name
          val state = TodayCalendarFixtures.state(name)
          val expected = buildSet {
            add(marker())
            state.event?.let { add(it.title); add(state.kicker); add(state.detail) }
            state.message?.let(::add)
            if (state.unavailable) add("Try again")
          }
          val launch = intent()
          if (!started) launch.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
          probe.context.startActivity(launch); started = true
          await("$name public production section visible") { read(expected).keys.containsAll(expected) }
          demand("$name semantic date and status contract", when (name) {
            "today" -> state.kicker == "Today · 14:00"
            "tomorrow" -> state.kicker == "Tomorrow · 14:00"
            "future_year" -> "2027" in state.kicker
            "overnight" -> state.kicker == "Today · In progress"
            "all_day" -> state.kicker == "Today" && state.detail == "All-day"
            "empty" -> state.message == "No upcoming events"
            "unavailable", "retry_transition" -> state.message == "Calendar unavailable"
            "loading" -> state.message == "Loading calendar…"
            "saved_event" -> state.event != null && state.unavailable && state.detail.startsWith("Saved event · ")
            "changed_source" -> state.url == TodayCalendarFixtures.OTHER_SOURCE && state.event == null && state.refreshing
            "setup_add" -> state.message == "Add your calendar"
            else -> false
          })
          if (name == "changed_source") {
            demand("changed source hides old event", read(setOf("Public saved event")).isEmpty())
            await("production gate clears old source ownership") {
              val seen = read(setOf("Gate old source cleared: true"))
              if (seen.isEmpty()) scroll()
              seen.size == 1
            }
          }
          if (name == "setup_add") {
            tap("Add calendar")
            scroll(back = true)
            await("setup add invokes only fixture callback") {
              read(setOf("Fixture adds: 1", "No upcoming events")).size == 2
            }
          }
          if (name == "retry_transition") {
            tap("Try again")
            await("retry becomes disabled while refreshing") {
              val values = read(setOf("Refreshing…", "Fixture retries: 1"))
              values.size == 2 && values["Refreshing…"]?.enabled == false
            }
            await("production gate coalesces actual retry requests") {
              val seen = read(setOf("Gate coalesced: true"))
              if (seen.isEmpty()) scroll()
              seen.size == 1
            }
            scroll(back = true)
            tap("Refreshing…", disabled = true)
            await("disabled retry cannot invoke callback twice") { read(setOf("Fixture retries: 1")).size == 1 }
            tap("Complete fixture refresh")
            scroll(back = true)
            await("refresh result renders actual production event") {
              read(setOf("Public today event", "Today · 14:00")).size == 2
            }
            demand("successful refresh removes retry status", read(setOf("Try again", "Refreshing…")).isEmpty())
            await("production force request consumed exactly once") {
              val seen = read(setOf("Gate consumed once: true"))
              if (seen.isEmpty()) scroll()
              seen.size == 1
            }
          }
          cases.put(JSONObject().put("case", name).put("darkPalette", dark).put("passed", true)
            .put("actualProductionComposable", true).put("nativeAccessibilityVerified", true))
        }
      }
      completed = true
    } catch (failure: Throwable) {
      cases.put(JSONObject().put("case", current).put("darkPalette", dark).put("passed", false)
        .put("failureType", failure.javaClass.simpleName))
    } finally {
      if (started) {
        try {
          probe.context.startActivity(intent(finish = true))
          val end = SystemClock.elapsedRealtime() + 5_000
          var closed = false
          while (!closed && SystemClock.elapsedRealtime() < end) {
            val root = automation?.rootInActiveWindow
            try { closed = root == null || root.packageName?.toString() != TodayCalendarFixtures.PACKAGE }
            finally { root?.recycle() }
            if (!closed) SystemClock.sleep(65)
          }
          checks.put("owned fixture window closed", closed)
          completed = completed && closed
        } catch (failure: Throwable) {
          checks.put("owned fixture window closed", false)
          checks.put("cleanupFailureType", failure.javaClass.simpleName)
          completed = false
        }
      }
    }
    val report = JSONObject().put("passed", completed).put("cases", cases).put("caseCount", cases.length())
      .put("plannedCaseCount", TodayCalendarFixtures.names.size * 2).put("assertions", checks)
      .put("portalDensityDpi", probe.context.resources.displayMetrics.densityDpi)
      .put("compactWidthDp", 400).put("maxAllowlistedVisibleTexts", maxVisibleTexts)
      .put("rawTreesPersisted", false).put("screenshotsCaptured", false).put("preferencesReadOrWritten", false)
      .put("householdUiStarted", false).put("networkRequests", 0).put("productionStateAndSectionUsed", true)
      .put("baselineHomeAcceptanceClaimed", false).put("actualProductionRefreshGateUsed", true)
    return Bundle().apply {
      putBoolean("todayCalendarUiCompleted", completed)
      putString("todayCalendarUiResult", report.toString())
    }
  }
}
