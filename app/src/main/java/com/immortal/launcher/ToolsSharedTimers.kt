/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val START_ERROR = "Couldn’t start the timer. Try again."
private const val CHANGE_ERROR = "That timer already changed in another room."

/**
 * Tools › Timers: the household timers. Starting one here shows it on every Portal and the phone,
 * with this room's name; every row can be paused, given a minute more or cancelled from here.
 */
@Composable
internal fun ToolsTimersContent() {
  val context = LocalContext.current.applicationContext
  val view = rememberSharedTimers(context)
  val operations = rememberEverydayOperations()
  val scope = rememberCoroutineScope()
  var custom by rememberSaveable { mutableStateOf(false) }
  var name by rememberSaveable { mutableStateOf("") }
  var hours by rememberSaveable { mutableStateOf("0") }
  var minutes by rememberSaveable { mutableStateOf("5") }
  var seconds by rememberSaveable { mutableStateOf("0") }
  var cancelId by rememberSaveable { mutableStateOf<String?>(null) }
  val duration = ApprovedToolsEverydayValidation.timerDurationMillis(hours, minutes, seconds)
  fun startTimer(label: String, millis: Long, closeEditor: Boolean = false) {
    operations.run(START_ERROR, task = { requireNotNull(SharedTimers.start(context, label, millis)) }) {
      if (closeEditor) { custom = false; name = "" }
    }
  }
  fun act(action: SharedTimerAction, id: String) {
    operations.error = null
    scope.launch {
      if (sharedTimerAct(context, action, id) !is SharedTimers.ActResult.Changed) operations.error = CHANGE_ERROR
    }
  }

  EverydayCollectionPanes(left = {
    EverydayPanel("Start a timer") {
      ToolsText("Tap a duration to start. It shows in every room.", secondary = true)
      Spacer(Modifier.height(24.dp))
      EverydayTimerPresets.chunked(3).forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { preset ->
            ToolsButton("$preset min", enabled = !operations.busy, modifier = Modifier.weight(1f)) {
              startTimer("$preset min", preset * 60_000L)
            }
          }
        }
      }
      EverydayActions {
        ToolsButton("Custom timer", primary = true, enabled = !operations.busy) {
          operations.error = null
          custom = true
        }
      }
      EverydayError(operations.error.takeUnless { custom || cancelId != null })
    }
  }, right = {
    EverydayPanel("Running timers") {
      if (view.timers.isEmpty()) EverydayEmpty("timer", "A moment to yourself", "Timers from every room appear here.")
      else EverydayCollection {
        items(count = view.timers.size, key = { view.timers[it].id }) { index ->
          val timer = view.timers[index]
          Column {
            if (index > 0) EverydayDivider()
            SharedTimerToolsRow(timer, view.clock, onAct = { act(it, timer.id) }, onCancel = { cancelId = timer.id })
          }
        }
      }
    }
  })

  if (custom) {
    EverydaySheet("Custom timer", onDismiss = { if (!operations.busy) custom = false }, actions = {
      ToolsButton("Cancel", enabled = !operations.busy) { custom = false }
      ToolsButton("Start timer", primary = true, enabled = duration != null && !operations.busy) {
        duration?.let { startTimer(name.ifBlank { SharedTimer.DEFAULT_NAME }, it, closeEditor = true) }
      }
    }) {
      ToolsField("Name (optional)", name, { name = it.take(DinnerWire.MAX_NAME) })
      Spacer(Modifier.height(20.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ToolsField("Hours", hours, { hours = it }, numeric = true, modifier = Modifier.weight(1f))
        ToolsField("Minutes", minutes, { minutes = it }, numeric = true, modifier = Modifier.weight(1f))
        ToolsField("Seconds", seconds, { seconds = it }, numeric = true, modifier = Modifier.weight(1f))
      }
      Spacer(Modifier.height(20.dp))
      ToolsText("Hours 0–23 · Minutes and seconds 0–59", secondary = true)
      EverydayError(if (duration == null) "Enter a duration greater than zero, with each field in range." else operations.error)
    }
  }
  cancelId?.let { id ->
    val selected = view.timers.firstOrNull { it.id == id }
    ToolsConfirm(
        "Cancel this timer?",
        selected?.let { "Cancel “${it.name}” in every room? Your other timers keep running." } ?: "This timer has finished.",
        confirmLabel = "Cancel timer",
        onDismiss = { cancelId = null },
        onConfirm = { cancelId = null; if (selected != null) act(SharedTimerAction.CANCEL, id) },
    )
  }
}

@Composable
private fun SharedTimerToolsRow(
    timer: SharedTimer, clock: State<Long>, onAct: (SharedTimerAction) -> Unit, onCancel: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(Modifier.weight(1f)) {
        EverydayTitle(timer.name)
        ToolsText(sharedTimerSubtitle(timer), secondary = true)
      }
      SharedTimerToolsClock(timer, clock)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      when (timer.state) {
        SharedTimerState.RINGING -> ToolsButton("Stop", primary = true) { onAct(SharedTimerAction.STOP) }
        SharedTimerState.PAUSED -> ToolsButton("Resume") { onAct(SharedTimerAction.RESUME) }
        else -> ToolsButton("Pause") { onAct(SharedTimerAction.PAUSE) }
      }
      ToolsButton("+1 min") { onAct(SharedTimerAction.ADD) }
      if (timer.state != SharedTimerState.RINGING) ToolsButton("Cancel", destructive = true, onClick = onCancel)
    }
  }
}

/** Only this text reads the ticking clock. */
@Composable
private fun SharedTimerToolsClock(timer: SharedTimer, clock: State<Long>) {
  val now by clock
  EverydayValue(ApprovedToolsEverydayValidation.timerClock(timer.remaining(now)), size = 56)
}
