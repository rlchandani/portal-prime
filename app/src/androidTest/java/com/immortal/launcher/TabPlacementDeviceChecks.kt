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
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.abs

/** Test-APK-only fixture: real retained tabs and input, no Portal data or network. */
internal object TabPlacementDeviceChecks {
  fun run(instrumentation: Instrumentation): Bundle {
    val assertions = JSONObject()
    var host: ComponentActivity? = null
    var existing: ComponentActivity? = null
    val selected = mutableStateOf(ApprovedLauncherTab.HOME)
    val values = mutableStateMapOf<ApprovedLauncherTab, Int>()
    val creates = mutableMapOf<ApprovedLauncherTab, Int>()
    val disposals = mutableMapOf<ApprovedLauncherTab, Int>()
    val pageBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val actionBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val dockBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
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
    fun tap(rect: Rect, holdMs: Long = 40) {
      val down = SystemClock.uptimeMillis()
      fun event(action: Int) {
        val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            rect.center.x, rect.center.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { check(instrumentation.uiAutomation.injectInputEvent(input, true)) }
        finally { input.recycle() }
      }
      event(MotionEvent.ACTION_DOWN)
      SystemClock.sleep(holdMs)
      event(MotionEvent.ACTION_UP)
    }
    fun settled(tab: ApprovedLauncherTab) = selected.value == tab && pageBounds[tab]?.boundsInWindow()?.let {
      abs(it.left - stage.left) <= 1f && abs(it.top - stage.top) <= 1f &&
          abs(it.right - stage.right) <= 1f && abs(it.bottom - stage.bottom) <= 1f
    } == true
    fun select(tab: ApprovedLauncherTab) {
      tap(bounds(dockBounds, tab))
      await("settled_${tab.id}") { settled(tab) }
    }
    fun launchOwned(): ComponentActivity {
      // NEW_TASK alone may reuse a matching root task, even with standard launch mode.
      val monitor = instrumentation.addMonitor(ImmortalSettingsActivity::class.java.name, null, false)
      try {
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,
            ImmortalSettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        return checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? ComponentActivity)
      } finally { instrumentation.removeMonitor(monitor) }
    }
    try {
      // Two identical base Intents exercise task reuse while both hosts remain test-owned.
      existing = launchOwned()
      val existingHost = checkNotNull(existing)
      val markerId = View.generateViewId()
      var marker: TextView? = null
      instrumentation.runOnMainSync {
        marker = TextView(existingHost).apply { id = markerId; text = "Existing fixture" }
        existingHost.setContentView(marker)
      }
      host = launchOwned()
      val activity = checkNotNull(host)
      demand("fresh_host_despite_matching_settings_task", activity !== existingHost)
      val window = activity.window
      instrumentation.runOnMainSync {
        activity.setContent {
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
                  Text("Fixture ${tab.id}: ${values[tab] ?: 0}")
                  Box(Modifier.size(160.dp, 72.dp)
                      .onGloballyPositioned { actionBounds[tab] = it }
                      .tvFocusable(focusScale = 1f, onLongClick = { values[tab] = (values[tab] ?: 0) + 10 }) {
                        values[tab] = (values[tab] ?: 0) + 1
                      }, contentAlignment = Alignment.Center) { Text("Fixture action ${tab.id}") }
                }
              }
            }
            Row(Modifier.fillMaxWidth().height(72.dp)) {
              for (tab in ApprovedLauncherTab.entries) Box(Modifier.weight(1f).fillMaxHeight()
                  .onGloballyPositioned { dockBounds[tab] = it }
                  .tvFocusable(focusScale = 1f) { selected.value = tab },
                  contentAlignment = Alignment.Center) { Text("Fixture dock ${tab.id}") }
            }
          }
        }
      }
      await("initial_home_placed") { settled(ApprovedLauncherTab.HOME) && dockBounds.size == 3 }
      tap(bounds(actionBounds, ApprovedLauncherTab.HOME))
      await("home_tap_once") { values[ApprovedLauncherTab.HOME] == 1 }
      tap(bounds(actionBounds, ApprovedLauncherTab.HOME), 800)
      await("home_long_press_once_without_click") { values[ApprovedLauncherTab.HOME] == 11 }
      select(ApprovedLauncherTab.APPS)
      tap(bounds(actionBounds, ApprovedLauncherTab.APPS))
      await("apps_input_does_not_hit_hidden_home") {
        values[ApprovedLauncherTab.APPS] == 1 && values[ApprovedLauncherTab.HOME] == 11
      }
      select(ApprovedLauncherTab.TOOLS)
      tap(bounds(actionBounds, ApprovedLauncherTab.TOOLS))
      await("tools_input_does_not_hit_retained_pages") {
        values[ApprovedLauncherTab.TOOLS] == 1 && values[ApprovedLauncherTab.APPS] == 1 && values[ApprovedLauncherTab.HOME] == 11
      }
      select(ApprovedLauncherTab.HOME)
      demand("visited_pages_retained_once", mainBoolean { creates.size == 3 && creates.values.all { it == 1 } && disposals.isEmpty() })
      repeat(4) {
        for (tab in listOf(ApprovedLauncherTab.APPS, ApprovedLauncherTab.TOOLS, ApprovedLauncherTab.HOME)) {
          tap(bounds(dockBounds, tab), 20)
          SystemClock.sleep(20)
        }
      }
      await("rapid_reversal_settles_home") { settled(ApprovedLauncherTab.HOME) }
      tap(bounds(actionBounds, ApprovedLauncherTab.HOME))
      await("input_after_rapid_reversal_only_hits_home") {
        values[ApprovedLauncherTab.HOME] == 12 && values[ApprovedLauncherTab.APPS] == 1 && values[ApprovedLauncherTab.TOOLS] == 1
      }
      demand("same_activity_window", mainBoolean { host === activity && activity.window === window && !activity.isFinishing })
      demand("no_page_disposal_during_switching", mainBoolean { disposals.isEmpty() })
      demand("existing_settings_content_preserved", mainBoolean {
        !existingHost.isFinishing && existingHost.findViewById<View>(markerId) === marker
      })
      demand("completed", true)
    } catch (failure: Throwable) {
      assertions.put("completed", false).put("failureType", failure.javaClass.simpleName)
    } finally {
      instrumentation.runOnMainSync { host?.finish(); existing?.finish() }
    }
    return Bundle().apply {
      putBoolean("tabPlacementCompleted", assertions.optBoolean("completed"))
      putString("tabPlacementAssertions", assertions.toString())
    }
  }
}
