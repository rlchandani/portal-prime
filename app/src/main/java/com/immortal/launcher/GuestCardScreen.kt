/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val IDLE_CLOSE_MS = 10 * 60 * 1000L

@Composable
internal fun GuestCardScreen(
    content: GuestCardContent?,
    mode: GuestModeState,
    onClose: () -> Unit,
    onStart: (GuestSchedule) -> Unit,
    onEnd: () -> Unit,
    onEdit: () -> Unit,
) {
  var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(touchedAt) { delay(IDLE_CLOSE_MS); onClose() }
  Box(Modifier.fillMaxSize().background(ApprovedHomePalette.Background).pointerInput(Unit) {
    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touchedAt = System.currentTimeMillis() } }
  }.padding(horizontal = 48.dp, vertical = 32.dp)) {
    when {
      content == null -> Unit
      !content.ready -> GuestCardEmpty(onEdit)
      else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp),
          verticalAlignment = Alignment.CenterVertically) {
        GuestQr(content.network)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
          GuestCardDetails(content)
          GuestModeControls(mode, onStart, onEnd)
        }
      }
    }
    Box(Modifier.align(Alignment.TopEnd)) { RoundIconButton("x", "Close guest card", onClose) }
  }
}

@Composable
private fun GuestCardEmpty(onEdit: () -> Unit) {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally) {
    Text("The guest card isn't set up yet", color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 50.sp,
        modifier = Modifier.semantics { heading() })
    Text("Add your Wi-Fi network and password, and a few house basics.", color = ApprovedHomePalette.Secondary,
        fontSize = 24.sp, lineHeight = 34.sp)
    ToolsButton("Set up guest card", primary = true, onClick = onEdit)
  }
}

@Composable
private fun GuestModeControls(mode: GuestModeState, onStart: (GuestSchedule) -> Unit, onEnd: () -> Unit) {
  Spacer(Modifier.size(8.dp))
  if (mode.isOn(System.currentTimeMillis())) {
    GuestLine("Guest mode is on", "Photos, calendar, notes and music library are hidden. ${guestUntilLabel(mode)}.")
    GuestHoldToEnd(onEnd)
  } else {
    GuestLabel("Guest mode hides photos, calendar, notes and your music library")
    GuestStartButtons(onStart)
  }
}

@Composable
private fun GuestStartButtons(onStart: (GuestSchedule) -> Unit) {
  GuestStartChoices.choices.chunked(2).forEach { row ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      row.forEach { (label, schedule) ->
        ToolsButton(label, primary = schedule == GuestSchedule.UntilEnded, modifier = Modifier.weight(1f)
            .semantics { contentDescription = "Start Guest mode, ${label.lowercase()}" }) { onStart(schedule) }
      }
      if (row.size == 1) Spacer(Modifier.weight(1f))
    }
  }
}
