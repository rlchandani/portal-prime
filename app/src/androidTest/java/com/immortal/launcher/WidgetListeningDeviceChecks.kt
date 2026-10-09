/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Test-owned window and recording hosts: no widget registration, providers, or preference access. */
internal object WidgetListeningDeviceChecks {
  private const val STEP_TIMEOUT_MS = 5000L
  private const val RUN_TIMEOUT_MS = 45000L
  private const val QUIET_MS = 200L

  fun run(instrumentation: Instrumentation): Bundle {
    val result = Bundle()
    val assertions = JSONObject()
    val probe = Probe()
    val ownership = Ownership()
    val runDeadline = SystemClock.elapsedRealtime() + RUN_TIMEOUT_MS
    var activity: ComponentActivity? = null
    var owner: FixtureOwner? = null
    var first: RecordingHost? = null
    var replacement: RecordingHost? = null
    var completed = false
    var stage = "launch_owned_settings"
    for (name in listOf("widgetListeningDisabledNoSystemWork",
        "widgetListeningAtMostOneStartPerActiveInterval", "widgetListeningBalancedStops",
        "widgetListeningStartsOnlyWhenEnabledAndStarted", "widgetListeningNoConcurrentOwners",
        "widgetListeningLifecycleGated", "widgetListeningReplacementOwned",
        "widgetListeningDisposalStops", "widgetListeningHomeRetained",
        "widgetListeningSameWindow", "widgetListeningCallbacksOnMain",
        "widgetListeningOwnedWindowFinished")) result.putBoolean(name, false)

    fun demand(label: String, valid: Boolean) {
      stage = label
      result.putBoolean(label, valid)
      assertions.put(label, valid)
      check(valid) { label }
    }
    fun mainBoolean(block: () -> Boolean): Boolean {
      var value = false
      instrumentation.runOnMainSync { value = block() }
      return value
    }
    fun change(block: () -> Unit = {}) {
      instrumentation.runOnMainSync { block(); probe.revision.value++ }
    }
    fun await(label: String, condition: () -> Boolean) {
      stage = label
      val deadline = minOf(SystemClock.elapsedRealtime() + STEP_TIMEOUT_MS, runDeadline)
      var readySince = 0L
      while (SystemClock.elapsedRealtime() < deadline) {
        val ready = mainBoolean(condition)
        val now = SystemClock.elapsedRealtime()
        if (!ready) readySince = 0L
        else {
          if (readySince == 0L) readySince = now
          if (now - readySince >= QUIET_MS) { demand(label, true); return }
        }
        SystemClock.sleep(25)
      }
      demand(label, false)
    }
    fun settle(label: String, tab: ApprovedLauncherTab, homeState: Lifecycle.State) {
      var revision = -1
      instrumentation.runOnMainSync { revision = probe.revision.value }
      // State assignment alone does not mean ApprovedLauncherPageLifecycle's SideEffect ran.
      // Wait for committed page content, two real Compose frames, lifecycle, and final placement.
      await(label) {
        val coordinates = probe.pageBounds[tab]
        val bounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
        probe.framesSettled == revision && probe.pageRevisions[tab] == revision &&
            probe.pageRevisions[ApprovedLauncherTab.HOME] == revision &&
            probe.selected.value == tab && probe.homeOwner?.lifecycle?.currentState == homeState &&
            probe.stage.width > 0f && probe.stage.height > 0f && bounds != null &&
            abs(bounds.left - probe.stage.left) <= 1f && abs(bounds.top - probe.stage.top) <= 1f &&
            abs(bounds.right - probe.stage.right) <= 1f && abs(bounds.bottom - probe.stage.bottom) <= 1f
      }
    }

    try {
      val monitor = instrumentation.addMonitor(ImmortalSettingsActivity::class.java.name, null, false)
      try {
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,
            ImmortalSettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 8000) as? ComponentActivity)
      } finally { instrumentation.removeMonitor(monitor) }
      val hostActivity = checkNotNull(activity)
      val window = hostActivity.window
      instrumentation.runOnMainSync {
        val fixtureOwner = FixtureOwner()
        owner = fixtureOwner
        fun eligible(host: RecordingHost) = probe.enabled.value && probe.listenerPresent.value &&
            probe.currentHost.value === host &&
            probe.homeOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
        val a = RecordingHost(hostActivity, 0x574C01, "first", ownership, ::eligible)
        val b = RecordingHost(hostActivity, 0x574C02, "replacement", ownership, ::eligible)
        first = a
        replacement = b
        probe.currentHost.value = a
        hostActivity.setContent {
          val revision = probe.revision.value
          val selected = probe.selected.value
          val enabled = probe.enabled.value
          val listenerPresent = probe.listenerPresent.value
          val widgetHost = checkNotNull(probe.currentHost.value)
          // Keep the real window resumed so its frame clock progresses even during ON_STOP.
          // AndroidX LifecycleRegistry dispatches these events through the real page lifecycle owners.
          CompositionLocalProvider(LocalLifecycleOwner provides fixtureOwner) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { probe.stage = it.boundsInWindow() }) {
              ApprovedLauncherTabs(selected, Modifier.fillMaxSize()) { tab ->
                val pageOwner = LocalLifecycleOwner.current
                val identity = remember { Any() }
                DisposableEffect(tab) {
                  probe.creates[tab] = (probe.creates[tab] ?: 0) + 1
                  onDispose { probe.disposals[tab] = (probe.disposals[tab] ?: 0) + 1 }
                }
                if (tab == ApprovedLauncherTab.HOME && listenerPresent)
                  ApprovedHomeWidgetListening(widgetHost, enabled)
                SideEffect {
                  probe.pageRevisions[tab] = revision
                  if (tab == ApprovedLauncherTab.HOME) {
                    probe.homeOwner = pageOwner
                    probe.homeIdentities.add(identity)
                  }
                }
                Box(Modifier.fillMaxSize().onGloballyPositioned { probe.pageBounds[tab] = it },
                    contentAlignment = Alignment.Center) {
                  Text("Widget fixture ${tab.id}: saved=$enabled; epoch=$revision")
                }
              }
            }
          }
          LaunchedEffect(revision) {
            repeat(2) { withFrameNanos { } }
            probe.framesSettled = revision
          }
        }
      }
      val a = checkNotNull(first)
      val b = checkNotNull(replacement)
      val lifecycle = checkNotNull(owner)
      settle("settled_initial_disabled_home", ApprovedLauncherTab.HOME, Lifecycle.State.CREATED)
      demand("disabled_created_has_no_calls", mainBoolean { a.calls == Calls(0, 0) && b.calls == Calls(0, 0) })
      demand("owned_activity_is_started", mainBoolean {
        hostActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !hostActivity.isFinishing
      })
      change { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
      settle("settled_disabled_started_home", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      change { probe.selected.value = ApprovedLauncherTab.APPS }
      settle("settled_disabled_apps", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      change { probe.selected.value = ApprovedLauncherTab.HOME }
      settle("settled_disabled_return_home", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("disabled_page_round_trip_has_no_calls", mainBoolean {
        a.calls == Calls(0, 0) && b.calls == Calls(0, 0)
      })

      // Only this in-memory Boolean simulates adding/removing a saved Android widget.
      change { probe.enabled.value = true }
      settle("settled_saved_widget_added", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("saved_widget_starts_once", mainBoolean { a.calls == Calls(1, 0) && a.listening })
      repeat(3) { index ->
        change()
        settle("settled_enabled_recomposition_$index", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      }
      demand("recomposition_does_not_restart_listener", mainBoolean { a.calls == Calls(1, 0) })
      change { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
      settle("settled_resumed_home", ApprovedLauncherTab.HOME, Lifecycle.State.RESUMED)
      change { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
      settle("settled_paused_but_started_home", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("started_to_resumed_to_started_keeps_one_interval", mainBoolean { a.calls == Calls(1, 0) })

      change { probe.selected.value = ApprovedLauncherTab.APPS }
      settle("settled_enabled_apps", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      demand("hidden_home_stops_once", mainBoolean { a.calls == Calls(1, 1) && !a.listening })
      change { probe.enabled.value = false }
      settle("settled_hidden_widget_removed", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      change { probe.enabled.value = true }
      settle("settled_hidden_widget_added", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      demand("hidden_widget_changes_do_no_work", mainBoolean { a.calls == Calls(1, 1) })
      change { probe.selected.value = ApprovedLauncherTab.HOME }
      settle("settled_enabled_return_home", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("returning_home_starts_one_new_interval", mainBoolean { a.calls == Calls(2, 1) && a.listening })

      change { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
      settle("settled_stopped_home", ApprovedLauncherTab.HOME, Lifecycle.State.CREATED)
      demand("stopped_lifecycle_stops_once", mainBoolean { a.calls == Calls(2, 2) && !a.listening })
      change { probe.enabled.value = false }
      settle("settled_stopped_widget_removed", ApprovedLauncherTab.HOME, Lifecycle.State.CREATED)
      change { probe.enabled.value = true }
      settle("settled_stopped_widget_added", ApprovedLauncherTab.HOME, Lifecycle.State.CREATED)
      demand("stopped_widget_changes_do_no_work", mainBoolean { a.calls == Calls(2, 2) })
      change { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
      settle("settled_restarted_home", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("widgetListeningLifecycleGated", mainBoolean { a.calls == Calls(3, 2) && a.listening })

      change { probe.enabled.value = false }
      settle("settled_saved_widget_removed", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("removing_last_saved_widget_stops_once", mainBoolean { a.calls == Calls(3, 3) && !a.listening })
      change()
      settle("settled_disabled_recomposition", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      change { probe.selected.value = ApprovedLauncherTab.APPS }
      settle("settled_removed_widget_apps", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      change { probe.selected.value = ApprovedLauncherTab.HOME }
      settle("settled_removed_widget_return_home", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("disabled_after_removal_has_no_calls", mainBoolean { a.calls == Calls(3, 3) && b.calls == Calls(0, 0) })

      change { probe.enabled.value = true }
      settle("settled_widget_readded", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("readded_widget_starts_once", mainBoolean { a.calls == Calls(4, 3) && a.listening })
      change { probe.currentHost.value = b }
      settle("settled_active_host_replacement", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("widgetListeningReplacementOwned", mainBoolean {
        a.calls == Calls(4, 4) && !a.listening && b.calls == Calls(1, 0) && b.listening && ownership.peak == 1
      })
      change()
      settle("settled_replacement_recomposition", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("replacement_does_not_restart_on_recomposition", mainBoolean {
        a.calls == Calls(4, 4) && b.calls == Calls(1, 0)
      })
      change { probe.listenerPresent.value = false }
      settle("settled_listener_disposal", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("listener_disposal_stops_without_disposing_home", mainBoolean {
        b.calls == Calls(1, 1) && !b.listening && probe.disposals.isEmpty()
      })
      change { probe.listenerPresent.value = true }
      settle("settled_listener_reentry", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("listener_reentry_starts_once", mainBoolean { b.calls == Calls(2, 1) && b.listening })
      change { probe.selected.value = ApprovedLauncherTab.APPS }
      settle("settled_replacement_apps", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      change { probe.currentHost.value = a }
      settle("settled_hidden_host_replacement", ApprovedLauncherTab.APPS, Lifecycle.State.CREATED)
      demand("hidden_host_replacement_does_no_work", mainBoolean {
        a.calls == Calls(4, 4) && b.calls == Calls(2, 2) && ownership.active.isEmpty()
      })
      change { probe.selected.value = ApprovedLauncherTab.HOME }
      settle("settled_original_host_return", ApprovedLauncherTab.HOME, Lifecycle.State.STARTED)
      demand("only_current_host_restarts_on_return", mainBoolean {
        a.calls == Calls(5, 4) && a.listening && b.calls == Calls(2, 2) && !b.listening
      })
      demand("widgetListeningHomeRetained", mainBoolean {
        probe.creates == mapOf(ApprovedLauncherTab.HOME to 1, ApprovedLauncherTab.APPS to 1) &&
            probe.disposals.isEmpty() && probe.homeIdentities.size == 1
      })

      var replacementCommitted = false
      var replacementFramed = false
      instrumentation.runOnMainSync {
        hostActivity.setContent {
          Text("Widget listening fixture disposed")
          SideEffect { replacementCommitted = true }
          LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos { } }
            replacementFramed = true
          }
        }
      }
      await("settled_composition_disposal") {
        replacementCommitted && replacementFramed && probe.disposals[ApprovedLauncherTab.HOME] == 1
      }
      // Destroying the old lifecycle after disposal must not call an obsolete host observer.
      change { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
      await("settled_disposed_owner_destroyed") { lifecycle.lifecycle.currentState == Lifecycle.State.DESTROYED }
      demand("widgetListeningDisposalStops", mainBoolean {
        a.calls == Calls(5, 5) && b.calls == Calls(2, 2) && probe.disposals == probe.creates
      })
      demand("widgetListeningSameWindow", mainBoolean {
        activity === hostActivity && hostActivity.window === window && !hostActivity.isFinishing
      })
      val expected = listOf("first:start", "first:stop", "first:start", "first:stop",
          "first:start", "first:stop", "first:start", "first:stop", "replacement:start",
          "replacement:stop", "replacement:start", "replacement:stop", "first:start", "first:stop")
      demand("widgetListeningAtMostOneStartPerActiveInterval", mainBoolean {
        ownership.events == expected && a.duplicateStarts == 0 && b.duplicateStarts == 0
      })
      demand("widgetListeningBalancedStops", mainBoolean {
        a.starts == a.stops && b.starts == b.stops && a.unmatchedStops == 0 && b.unmatchedStops == 0 &&
            !a.listening && !b.listening && ownership.active.isEmpty()
      })
      demand("widgetListeningNoConcurrentOwners", mainBoolean { ownership.peak == 1 })
      demand("widgetListeningStartsOnlyWhenEnabledAndStarted", mainBoolean {
        a.ineligibleStarts == 0 && b.ineligibleStarts == 0
      })
      demand("widgetListeningCallbacksOnMain", mainBoolean { a.callbacksOnMain && b.callbacksOnMain })
      // Each disabled phase above compared both call counters after composition/lifecycle settlement.
      demand("widgetListeningDisabledNoSystemWork", true)
      completed = true
    } catch (failure: Throwable) {
      assertions.put("failureType", failure.javaClass.simpleName).put("failureStage", stage)
    } finally {
      try {
        instrumentation.runOnMainSync {
          // Explicit disposal also covers an assertion failure before the normal disposal phase.
          val content = activity?.findViewById<ViewGroup>(android.R.id.content)
          for (index in 0 until (content?.childCount ?: 0))
            (content?.getChildAt(index) as? ComposeView)?.disposeComposition()
          owner?.registry?.currentState = Lifecycle.State.DESTROYED
          activity?.finish()
        }
        val finished = mainBoolean { activity?.isFinishing == true }
        result.putBoolean("widgetListeningOwnedWindowFinished", finished)
        assertions.put("widgetListeningOwnedWindowFinished", finished)
        completed = completed && finished && mainBoolean {
          ownership.active.isEmpty() && listOfNotNull(first, replacement).all {
            it.starts == it.stops && it.unmatchedStops == 0 && !it.listening
          }
        }
      } catch (failure: Throwable) {
        completed = false
        assertions.put("cleanupFailureType", failure.javaClass.simpleName)
      }
      try {
        instrumentation.runOnMainSync {
          assertions.put("listenerEvents", JSONArray(ownership.events))
          assertions.put("peakListeningHosts", ownership.peak)
          first?.let { assertions.put("firstStarts", it.starts).put("firstStops", it.stops) }
          replacement?.let { assertions.put("replacementStarts", it.starts).put("replacementStops", it.stops) }
        }
      } catch (failure: Throwable) {
        completed = false
        assertions.put("diagnosticsFailureType", failure.javaClass.simpleName)
      }
    }
    assertions.put("completed", completed)
    result.putBoolean("widgetListeningCompleted", completed)
    result.putString("widgetListeningAssertions", assertions.toString())
    return result
  }

  private class FixtureOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
  }

  /** Only main-thread composition, lifecycle callbacks, and runOnMainSync access this probe. */
  private class Probe {
    val selected = mutableStateOf(ApprovedLauncherTab.HOME)
    val enabled = mutableStateOf(false)
    val listenerPresent = mutableStateOf(true)
    val currentHost = mutableStateOf<RecordingHost?>(null)
    val revision = mutableStateOf(0)
    val creates = mutableMapOf<ApprovedLauncherTab, Int>()
    val disposals = mutableMapOf<ApprovedLauncherTab, Int>()
    val pageBounds = mutableMapOf<ApprovedLauncherTab, LayoutCoordinates>()
    val pageRevisions = mutableMapOf<ApprovedLauncherTab, Int>()
    val homeIdentities = mutableSetOf<Any>()
    var homeOwner: LifecycleOwner? = null
    var framesSettled = -1
    var stage = Rect.Zero
  }

  private data class Calls(val starts: Int, val stops: Int)

  private class Ownership {
    val events = mutableListOf<String>()
    val active = mutableSetOf<RecordingHost>()
    var peak = 0
  }

  /** Never delegate start/stop to AppWidgetHost: the fixture cannot contact a widget provider. */
  private class RecordingHost(
      context: Context,
      hostId: Int,
      private val label: String,
      private val ownership: Ownership,
      private val eligible: (RecordingHost) -> Boolean,
  ) : AppWidgetHost(context, hostId) {
    var starts = 0
      private set
    var stops = 0
      private set
    var listening = false
      private set
    var duplicateStarts = 0
      private set
    var unmatchedStops = 0
      private set
    var ineligibleStarts = 0
      private set
    var callbacksOnMain = true
      private set
    val calls: Calls get() = Calls(starts, stops)

    override fun startListening() {
      callbacksOnMain = callbacksOnMain && Looper.myLooper() === Looper.getMainLooper()
      starts++
      if (listening) duplicateStarts++
      if (!eligible(this)) ineligibleStarts++
      listening = true
      ownership.events.add("$label:start")
      ownership.active.add(this)
      ownership.peak = maxOf(ownership.peak, ownership.active.size)
    }

    override fun stopListening() {
      callbacksOnMain = callbacksOnMain && Looper.myLooper() === Looper.getMainLooper()
      stops++
      if (!listening) unmatchedStops++
      listening = false
      ownership.events.add("$label:stop")
      ownership.active.remove(this)
    }
  }
}
