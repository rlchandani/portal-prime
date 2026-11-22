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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
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
  val layoutClass = currentLayoutClass()
  val compactPortrait = layoutClass.compactPortrait
  val dims = GuestCardLayout.of(layoutClass)
  var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(touchedAt) { delay(IDLE_CLOSE_MS); onClose() }
  Box(Modifier.fillMaxSize().background(ApprovedHomePalette.Background).pointerInput(Unit) {
    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touchedAt = System.currentTimeMillis() } }
  }.padding(horizontal = dims.sidePadding.dp, vertical = dims.verticalPadding.dp)) {
    when {
      content == null -> Unit
      !content.ready -> GuestCardEmpty(onEdit)
      compactPortrait -> GuestCardStacked(content, mode, dims.qr.dp, onStart, onEnd)
      else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp),
          verticalAlignment = Alignment.CenterVertically) {
        GuestQr(content.network, size = dims.qr.dp)
        // The Mini turned: the welcome line keeps clear of the close button and the details use the
        // compact type so the whole card fits the 740 dp height instead of scrolling the last choice away.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dims.detailsGap.dp)) {
          GuestCardDetails(content, compact = dims.compactDetails, headingEnd = dims.detailsEndPadding.dp)
          GuestModeControls(mode, onStart, onEnd)
        }
      }
    }
    Box(Modifier.align(Alignment.TopEnd)) { RoundIconButton("x", "Close guest card", onClose) }
  }
}

/**
 * Compact portrait: the QR (as large as the width allows, up to the Portal+ size) centred on top,
 * then the details and Guest mode controls below, all in one scroll.
 */
@Composable
private fun GuestCardStacked(content: GuestCardContent, mode: GuestModeState, maxQr: Dp,
                             onStart: (GuestSchedule) -> Unit, onEnd: () -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    // Leave room for the close button beside the centred code.
    val qr = minOf(maxWidth - STACKED_QR_INSET * 2, maxQr)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        GuestQr(content.network, size = qr)
      }
      GuestCardDetails(content)
      GuestModeControls(mode, onStart, onEnd)
    }
  }
}

private val STACKED_QR_INSET = 96.dp

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

/** The primary choice spans the width; the timed choices share the row below (no orphaned half button). */
@Composable
private fun GuestStartButtons(onStart: (GuestSchedule) -> Unit) {
  val choices = GuestStartChoices.choices
  Column(verticalArrangement = Arrangement.spacedBy(GuestCardLayout.CHOICE_GAP_DP.dp)) {
    GuestCardLayout.choiceRows(choices.size).forEach { row ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GuestCardLayout.CHOICE_GAP_DP.dp)) {
        row.forEach { index ->
          val (label, schedule) = choices[index]
          ToolsButton(label, primary = schedule == GuestSchedule.UntilEnded, modifier = Modifier.weight(1f)
              .semantics { contentDescription = "Start Guest mode, ${label.lowercase()}" }) { onStart(schedule) }
        }
      }
    }
  }
}

/** The guest card's sizes per window class and the start-choice rows (pure, JVM-tested). */
internal object GuestCardLayout {
  data class Dims(val sidePadding: Int, val verticalPadding: Int, val qr: Int, val detailsGap: Int,
                  val detailsEndPadding: Int, val compactDetails: Boolean)

  const val CHOICE_GAP_DP = 16
  /** The close button (76 dp) plus a gap, kept clear beside the details on the Mini turned. */
  const val CLOSE_CLEARANCE_DP = 76 + 16

  val LARGE = Dims(sidePadding = 48, verticalPadding = 32, qr = 400, detailsGap = 20, detailsEndPadding = 0, compactDetails = false)
  /** Upright: the code a little smaller so the start choices end inside the 1220 dp height. */
  val PORTRAIT = LARGE.copy(sidePadding = 32, qr = 360)
  val LANDSCAPE = Dims(sidePadding = 48, verticalPadding = 24, qr = 400, detailsGap = 14,
      detailsEndPadding = CLOSE_CLEARANCE_DP, compactDetails = true)

  fun of(cls: LayoutClass): Dims = when (cls) {
    LayoutClass.LARGE -> LARGE
    LayoutClass.COMPACT_PORTRAIT -> PORTRAIT
    LayoutClass.COMPACT_LANDSCAPE -> LANDSCAPE
  }

  /** The first (primary) choice alone, the rest side by side: three choices → [[0], [1, 2]]. */
  fun choiceRows(count: Int): List<List<Int>> =
      if (count <= 0) emptyList() else listOf(listOf(0)) + listOfNotNull((1 until count).toList().takeIf { it.isNotEmpty() })
}
