/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val WidgetPresetMinutes = listOf(1, 5, 10)
private val DialogPresetMinutes = listOf(1, 5, 10, 15, 30)
private const val MINUTE_MS = 60_000L
private val ChipMinHeight = 64.dp

/**
 * Home "Timers" widget: the household timers (started here, in another room or on the phone).
 * Shows the next one to end with Pause/Resume and +1 min (Stop while it rings), how many more are
 * running, and quick 1/5/10 minute starts when none is. The header opens [ApprovedHomeTimerEditor].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharedTimersWidget(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val view = rememberSharedTimers(context)
  val scope = rememberCoroutineScope()
  var showCustom by remember { mutableStateOf(false) }
  var showAll by remember { mutableStateOf(false) }
  val first = view.timers.firstOrNull()
  ImmortalWidgetShell(title = "Timers", openPage = "timers", modifier = modifier, onOpen = { showAll = true }) {
    if (first == null) {
      Text("Ready", color = ApprovedHomePalette.Text, fontSize = 48.sp, lineHeight = 60.sp)
      Text("Set a timer for every room", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
          modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WidgetPresetMinutes.forEach { m ->
          SharedTimerChip("${m}m") { scope.launch { sharedTimerStart(context, "$m min", m * MINUTE_MS) } }
        }
        SharedTimerChip("Custom") { showCustom = true }
      }
    } else {
      SharedTimerClockText(first, view.clock, 48)
      Text("${first.name} · ${sharedTimerSubtitle(first)}", color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
          lineHeight = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SharedTimerControls(first) { action -> scope.launch { sharedTimerAct(context, action, first.id) } }
      }
      val more = view.timers.size - 1
      if (more > 0) Text(if (more == 1) "1 more timer" else "$more more timers", color = ApprovedHomePalette.Secondary,
          fontSize = 20.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 12.dp))
    }
  }
  if (showCustom) CustomTimerDialog(onStart = { ms ->
    showCustom = false
    if (ms > 0L) scope.launch { sharedTimerStart(context, SharedTimer.DEFAULT_NAME, ms) }
  }, onDismiss = { showCustom = false })
  if (showAll) ApprovedHomeTimersPage { showAll = false }
}

/** Every household timer with its controls, plus starts (Home widget header, widget Settings). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovedHomeTimerEditor(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val view = rememberSharedTimers(context)
  val scope = rememberCoroutineScope()
  var showCustom by remember { mutableStateOf(false) }
  if (showCustom) {
    CustomTimerDialog(onStart = { ms ->
      showCustom = false
      if (ms > 0L) scope.launch { sharedTimerStart(context, SharedTimer.DEFAULT_NAME, ms) }
    }, onDismiss = { showCustom = false })
    return
  }
  ApprovedHomeDialog("Timers", onDismiss) {
    Text("Timers show in every room and on your phone.", color = ApprovedHomePalette.Secondary,
        fontSize = 22.sp, lineHeight = 30.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      DialogPresetMinutes.forEach { m ->
        SharedTimerChip("$m min") { scope.launch { sharedTimerStart(context, "$m min", m * MINUTE_MS) } }
      }
      SharedTimerChip("Custom") { showCustom = true }
    }
    if (view.timers.isEmpty()) {
      Text("No timers running.", color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 32.sp)
    }
    view.timers.forEach { timer ->
      Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(timer.name, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sharedTimerSubtitle(timer), color = ApprovedHomePalette.Secondary, fontSize = 20.sp, lineHeight = 28.sp)
          }
          SharedTimerClockText(timer, view.clock, 40)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SharedTimerControls(timer, withCancel = true) { action -> scope.launch { sharedTimerAct(context, action, timer.id) } }
        }
      }
    }
  }
}

@Composable
private fun SharedTimerControls(timer: SharedTimer, withCancel: Boolean = false, onAct: (SharedTimerAction) -> Unit) {
  when (timer.state) {
    SharedTimerState.RINGING -> SharedTimerChip("Stop") { onAct(SharedTimerAction.STOP) }
    SharedTimerState.PAUSED -> SharedTimerChip("Resume") { onAct(SharedTimerAction.RESUME) }
    else -> SharedTimerChip("Pause") { onAct(SharedTimerAction.PAUSE) }
  }
  SharedTimerChip("+1 min") { onAct(SharedTimerAction.ADD) }
  if (withCancel && timer.state != SharedTimerState.RINGING) SharedTimerChip("Cancel") { onAct(SharedTimerAction.CANCEL) }
}

/** Only this text reads the ticking clock. */
@Composable
private fun SharedTimerClockText(timer: SharedTimer, clock: State<Long>, sizeSp: Int) {
  val now by clock
  val text = if (timer.state == SharedTimerState.RINGING) "Time’s up"
      else ApprovedToolsEverydayValidation.timerClock(timer.remaining(now))
  Text(text, color = ApprovedHomePalette.Text, fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.25f).sp)
}

@Composable
private fun SharedTimerChip(label: String, onClick: () -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Surface(color = ApprovedHomePalette.Chrome, shape = shape,
      modifier = Modifier.heightIn(min = ChipMinHeight).tvFocusable(shape) { onClick() }) {
    Text(label, color = ApprovedHomePalette.Accent, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp))
  }
}
