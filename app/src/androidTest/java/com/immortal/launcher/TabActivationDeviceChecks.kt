/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.abs

/** Test-owned, generated fixture: activation fan-out and nested touch/D-pad isolation. */
internal object TabActivationDeviceChecks {
  fun run(instrumentation: Instrumentation): Bundle {
    val assertions = JSONObject()
    var host: ComponentActivity? = null
    val selected = mutableStateOf(ApprovedLauncherTab.HOME)
    val actions = mutableStateMapOf<ApprovedLauncherTab, Int>()
    val nestedActions = mutableStateMapOf<ApprovedLauncherTab, Int>()
    val rowActions = mutableMapOf<ApprovedLauncherTab, Int>()
    val parentActions = mutableMapOf<ApprovedLauncherTab, Int>()
    val counts = mutableMapOf<ApprovedLauncherTab, Int>()
    val creates = mutableMapOf<ApprovedLauncherTab, Int>()
    val disposals = mutableMapOf<ApprovedLauncherTab, Int>()
    val pageBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val actionBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val nestedBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val rowBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val dockBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val requesters = ApprovedLauncherTab.entries.associateWith { FocusRequester() }
    var longPressHaptics = 0
    val haptics = object : HapticFeedback {
      override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (hapticFeedbackType == HapticFeedbackType.LongPress) longPressHaptics++
      }
    }
    var stage = Rect.Zero
    fun demand(label: String, valid: Boolean) {
      assertions.put(label, valid)
      check(valid) { label }
    }
    fun mainBoolean(block: () -> Boolean): Boolean {
      var result = false
      instrumentation.runOnMainSync { result = block() }
      return result
    }
    fun await(label: String, condition: () -> Boolean) {
      val deadline = SystemClock.elapsedRealtime() + 5000
      while (SystemClock.elapsedRealtime() < deadline) {
        if (mainBoolean(condition)) { demand(label, true); return }
        SystemClock.sleep(25)
      }
      demand(label, false)
    }
    fun bounds(map: Map<ApprovedLauncherTab, LayoutCoordinates>, tab: ApprovedLauncherTab): Rect {
      var result = Rect.Zero
      instrumentation.runOnMainSync { result = map.getValue(tab).boundsInWindow() }
      check(result.width > 0 && result.height > 0)
      return result
    }
    fun pointer(rect: Rect, down: Long, action: Int) {
      val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
          rect.center.x, rect.center.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
      try { check(instrumentation.uiAutomation.injectInputEvent(input, true)) }
      finally { input.recycle() }
    }
    fun tap(rect: Rect, holdMs: Long = 40) {
      val down = SystemClock.uptimeMillis()
      pointer(rect, down, MotionEvent.ACTION_DOWN)
      SystemClock.sleep(holdMs)
      pointer(rect, down, MotionEvent.ACTION_UP)
    }
    fun settled(tab: ApprovedLauncherTab) = selected.value == tab && pageBounds[tab]?.boundsInWindow()?.let {
      abs(it.left - stage.left) <= 1f && abs(it.top - stage.top) <= 1f &&
          abs(it.right - stage.right) <= 1f && abs(it.bottom - stage.bottom) <= 1f
    } == true
    fun select(tab: ApprovedLauncherTab) {
      tap(bounds(dockBounds, tab))
      await("settled_${tab.id}") { settled(tab) }
      // Counts are observational and never Compose state. Allow pending effects to settle.
      SystemClock.sleep(150)
    }
    try {
      val monitor = instrumentation.addMonitor(ImmortalSettingsActivity::class.java.name, null, false)
      try {
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,
            ImmortalSettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        host = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? ComponentActivity)
      } finally { instrumentation.removeMonitor(monitor) }
      val activity = checkNotNull(host)
      val window = activity.window
      instrumentation.runOnMainSync {
        activity.setContent {
          CompositionLocalProvider(LocalHapticFeedback provides haptics) {
            Column(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 48.dp)) {
              Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { stage = it.boundsInWindow() }) {
                ApprovedLauncherTabs(selected.value, Modifier.fillMaxSize()) { tab ->
                  DisposableEffect(tab) {
                    creates[tab] = (creates[tab] ?: 0) + 1
                    onDispose { disposals[tab] = (disposals[tab] ?: 0) + 1 }
                  }
                  Column(Modifier.fillMaxSize().onGloballyPositioned { pageBounds[tab] = it },
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.Center) {
                    Text("Actions: ${actions[tab] ?: 0}; nested: ${nestedActions[tab] ?: 0}")
                    // A focusable widget parent must not defeat a child's inactive focus guard.
                    Column(Modifier.tvFocusable(focusScale = 1f) {
                      parentActions[tab] = (parentActions[tab] ?: 0) + 1
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                      Box(Modifier.size(160.dp, 72.dp)
                          .onGloballyPositioned { actionBounds[tab] = it }
                          .tvFocusable(focusScale = 1f,
                              onLongClick = { actions[tab] = (actions[tab] ?: 0) + 10 }) {
                            actions[tab] = (actions[tab] ?: 0) + 1
                          }, contentAlignment = Alignment.Center) { Text("Action ${tab.id}") }
                      UntouchedAction(tab, counts, nestedBounds, rowBounds,
                          requesters.getValue(tab), nestedActions, rowActions)
                    }
                  }
                }
              }
              Row(Modifier.fillMaxWidth().height(72.dp)) {
                for (tab in ApprovedLauncherTab.entries) Box(Modifier.weight(1f).fillMaxHeight()
                    .onGloballyPositioned { dockBounds[tab] = it }
                    .tvFocusable(focusScale = 1f) { selected.value = tab },
                    contentAlignment = Alignment.Center) { Text("Dock ${tab.id}") }
              }
            }
          }
        }
      }
      await("initial_home") { settled(ApprovedLauncherTab.HOME) && dockBounds.size == 3 }
      tap(bounds(actionBounds, ApprovedLauncherTab.HOME))
      await("home_tap_once") { actions[ApprovedLauncherTab.HOME] == 1 }
      tap(bounds(actionBounds, ApprovedLauncherTab.HOME), 800)
      await("home_long_press_without_click") { actions[ApprovedLauncherTab.HOME] == 11 }
      select(ApprovedLauncherTab.APPS)
      tap(bounds(actionBounds, ApprovedLauncherTab.APPS))
      await("apps_tap_isolated") {
        actions[ApprovedLauncherTab.APPS] == 1 && actions[ApprovedLauncherTab.HOME] == 11
      }
      // An in-flight touch remains owned by Apps while its page becomes inactive.
      val heldRect = bounds(actionBounds, ApprovedLauncherTab.APPS)
      val heldDown = SystemClock.uptimeMillis()
      pointer(heldRect, heldDown, MotionEvent.ACTION_DOWN)
      instrumentation.runOnMainSync { selected.value = ApprovedLauncherTab.TOOLS }
      SystemClock.sleep(850)
      pointer(heldRect, heldDown, MotionEvent.ACTION_UP)
      await("held_gesture_does_not_activate_hidden_apps") {
        settled(ApprovedLauncherTab.TOOLS) && actions[ApprovedLauncherTab.APPS] == 1 &&
            actions[ApprovedLauncherTab.TOOLS] == null && actions[ApprovedLauncherTab.HOME] == 11
      }
      tap(bounds(actionBounds, ApprovedLauncherTab.TOOLS))
      await("tools_tap_isolated") { actions[ApprovedLauncherTab.TOOLS] == 1 }
      tap(bounds(rowBounds, ApprovedLauncherTab.TOOLS))
      demand("nested_row_tap_isolated", mainBoolean {
        rowActions[ApprovedLauncherTab.TOOLS] == 1 && rowActions.size == 1 && parentActions.isEmpty()
      })
      val returningRect = bounds(actionBounds, ApprovedLauncherTab.TOOLS)
      val returningDown = SystemClock.uptimeMillis()
      pointer(returningRect, returningDown, MotionEvent.ACTION_DOWN)
      instrumentation.runOnMainSync { selected.value = ApprovedLauncherTab.HOME }
      SystemClock.sleep(250)
      instrumentation.runOnMainSync { selected.value = ApprovedLauncherTab.TOOLS }
      SystemClock.sleep(650)
      pointer(returningRect, returningDown, MotionEvent.ACTION_UP)
      await("held_gesture_stays_cancelled_after_page_returns") {
        settled(ApprovedLauncherTab.TOOLS) && actions[ApprovedLauncherTab.TOOLS] == 1 &&
            actions[ApprovedLauncherTab.HOME] == 11
      }
      val shortDownRect = bounds(actionBounds, ApprovedLauncherTab.TOOLS)
      val shortDown = SystemClock.uptimeMillis()
      pointer(shortDownRect, shortDown, MotionEvent.ACTION_DOWN)
      instrumentation.runOnMainSync { selected.value = ApprovedLauncherTab.HOME }
      SystemClock.sleep(100)
      instrumentation.runOnMainSync { selected.value = ApprovedLauncherTab.TOOLS }
      await("returned_tools_before_short_release") { settled(ApprovedLauncherTab.TOOLS) }
      val shortReleaseRect = bounds(actionBounds, ApprovedLauncherTab.TOOLS)
      demand("short_release_inside_returned_control", shortReleaseRect.contains(shortDownRect.center))
      pointer(shortReleaseRect, shortDown, MotionEvent.ACTION_UP)
      demand("short_gesture_released_before_long_press_timeout",
          SystemClock.uptimeMillis() - shortDown < android.view.ViewConfiguration.getLongPressTimeout())
      await("short_gesture_stays_cancelled_after_page_returns") {
        settled(ApprovedLauncherTab.TOOLS) && actions[ApprovedLauncherTab.TOOLS] == 1 &&
            actions[ApprovedLauncherTab.HOME] == 11
      }
      tap(bounds(actionBounds, ApprovedLauncherTab.TOOLS))
      await("new_touch_after_cancel_clicks_once") { actions[ApprovedLauncherTab.TOOLS] == 2 }
      instrumentation.runOnMainSync { assertions.put("longPressHapticRequests", longPressHaptics) }
      demand("rejected_long_presses_emit_no_haptic", mainBoolean { longPressHaptics == 1 })
      select(ApprovedLauncherTab.HOME)
      val before = mutableMapOf<ApprovedLauncherTab, Int>()
      instrumentation.runOnMainSync { before.putAll(counts) }
      for (tab in listOf(ApprovedLauncherTab.APPS, ApprovedLauncherTab.TOOLS, ApprovedLauncherTab.HOME)) select(tab)
      val after = mutableMapOf<ApprovedLauncherTab, Int>()
      instrumentation.runOnMainSync { after.putAll(counts) }
      assertions.put("untouchedCompositionsBefore", JSONObject(before.mapKeys { it.key.id }))
      assertions.put("untouchedCompositionsAfter", JSONObject(after.mapKeys { it.key.id }))
      // Run input regressions even on the RED build; demand the performance invariant last.
      select(ApprovedLauncherTab.APPS)
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
      demand("active_nested_control_can_focus", mainBoolean { requesters.getValue(ApprovedLauncherTab.APPS).requestFocus() })
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
      await("nested_dpad_activates_only_apps") {
        nestedActions[ApprovedLauncherTab.APPS] == 1 && nestedActions[ApprovedLauncherTab.HOME] == null &&
            nestedActions[ApprovedLauncherTab.TOOLS] == null && parentActions.isEmpty()
      }
      demand("hidden_nested_control_cannot_focus", !mainBoolean {
        requesters.getValue(ApprovedLauncherTab.HOME).requestFocus()
      })
      demand("visited_pages_retained_once", mainBoolean {
        creates.size == 3 && creates.values.all { it == 1 } && disposals.isEmpty()
      })
      demand("same_window", mainBoolean { activity.window === window && !activity.isFinishing })
      // Shared Settings callers outside retained tabs still use the legacy visibility local.
      val legacyActive = mutableStateOf(false)
      var legacyBounds: LayoutCoordinates? = null
      var legacyClicks = 0
      instrumentation.runOnMainSync {
        activity.setContent {
          CompositionLocalProvider(LocalApprovedLauncherPageActive provides legacyActive.value) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Box(Modifier.size(160.dp, 72.dp).onGloballyPositioned { legacyBounds = it }
                  .tvFocusableRow { legacyClicks++ }, contentAlignment = Alignment.Center) { Text("Legacy row") }
            }
          }
        }
      }
      await("legacy_row_placed") { legacyBounds?.isAttached == true }
      var legacyRect = Rect.Zero
      instrumentation.runOnMainSync { legacyRect = checkNotNull(legacyBounds).boundsInWindow() }
      tap(legacyRect)
      demand("legacy_inactive_row_stays_disabled", mainBoolean { legacyClicks == 0 })
      instrumentation.runOnMainSync { legacyActive.value = true }
      SystemClock.sleep(200)
      tap(legacyRect)
      await("legacy_active_row_clicks_once") { legacyClicks == 1 }
      demand("untouched_controls_skip_activation_recomposition",
          before.size == 3 && before == after)
      demand("completed", true)
    } catch (failure: Throwable) {
      assertions.put("completed", false).put("failureType", failure.javaClass.simpleName)
    } finally {
      instrumentation.runOnMainSync { host?.finish() }
    }
    return Bundle().apply {
      putBoolean("tabActivationCompleted", assertions.optBoolean("completed"))
      putString("tabActivationAssertions", assertions.toString())
    }
  }

  @Composable
  private fun UntouchedAction(
      tab: ApprovedLauncherTab,
      counts: MutableMap<ApprovedLauncherTab, Int>,
      coordinates: MutableMap<ApprovedLauncherTab, LayoutCoordinates>,
      rowCoordinates: MutableMap<ApprovedLauncherTab, LayoutCoordinates>,
      requester: FocusRequester,
      actions: MutableMap<ApprovedLauncherTab, Int>,
      rowActions: MutableMap<ApprovedLauncherTab, Int>,
  ) {
    SideEffect { counts[tab] = (counts[tab] ?: 0) + 1 }
    val click = remember(tab, actions) { { actions[tab] = (actions[tab] ?: 0) + 1 } }
    val rowClick = remember(tab, rowActions) { { rowActions[tab] = (rowActions[tab] ?: 0) + 1 } }
    Column {
      Box(Modifier.size(160.dp, 72.dp).onGloballyPositioned { coordinates[tab] = it }
          .focusRequester(requester).tvFocusable(focusScale = 1f, onClick = click),
          contentAlignment = Alignment.Center) { Text("Untouched ${tab.id}") }
      Box(Modifier.size(160.dp, 72.dp).onGloballyPositioned { rowCoordinates[tab] = it }
          .tvFocusableRow(onClick = rowClick),
          contentAlignment = Alignment.Center) { Text("Untouched row ${tab.id}") }
    }
  }
}
