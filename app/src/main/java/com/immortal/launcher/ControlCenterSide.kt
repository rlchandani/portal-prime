/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The overview's right column (mock `.side`, 540 dp, scrolls): Needs attention, Scenes,
 * the car and Cameras. Cards the house can't fill are left out rather than faked.
 */
@Composable
internal fun CcSideColumn(s: CcUiState, ui: CcUi, modifier: Modifier = Modifier) {
  // Each card gets only its slice (derived off the main thread, see [CcUiState.side]); equal slices skip the card.
  CcSideCards(s.side, s.connection.canControl, ui, CcCameraFeedState.of(s), modifier)
}

@Composable
private fun CcSideCards(side: CcSideSlices, canControl: Boolean, ui: CcUi, feed: CcCameraFeedState, modifier: Modifier) {
  Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    CcAttentionCard(side.attention, canControl, ui)
    CcScenesCard(side.scenes, canControl, ui)
    side.car?.let { CcCarCard(it, ui) }
    if (side.cameras.cams.isNotEmpty()) CcCamerasCard(feed, side.cameras, ui)
  }
}

@Composable
private fun CcAttentionCard(slice: CcAttentionSlice, canControl: Boolean, ui: CcUi) {
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    CcSectionLabel(if (slice.rows.isEmpty()) "All good" else "Needs attention", Modifier.padding(bottom = 2.dp))
    if (slice.rows.isEmpty()) AllClearRow(slice.allClear)
    slice.rows.forEach { key(it.alert.id) { AttentionRow(it, canControl, ui) } }
  }
}

@Composable
private fun AttentionRow(row: CcAttentionRow, canControl: Boolean, ui: CcUi) {
  val c = LocalCc.current
  val a = row.alert
  val e = row.entity
  val tap = remember(row, ui) { if (row.close && row.roomId != null) ({ ui.openRoom(row.roomId) }) else ({ ui.go(CcScreen.ALERTS) }) }
  val closes = row.close && e != null
  val speech = CcSpeech.row(a.title, a.body)
  // One node per row; a row with its own Close button keeps that button as a second node.
  Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(ROW).clickable(role = Role.Button, onClick = tap)
      .then(if (closes) Modifier else Modifier.ccSpeak(speech))
      .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    val tone = CcSideLogic.tone(a)
    val (bg, fg) = when (tone) { CcTone.RED -> c.redBg to c.red; CcTone.AMBER -> c.amberBg to c.amber; CcTone.GREEN -> c.greenBg to c.green; CcTone.NEUTRAL -> c.sel to c.text }
    Box(Modifier.size(60.dp).clip(ICON_TILE).background(bg), contentAlignment = Alignment.Center) { CcIcon(CcSideLogic.icon(a), fg, 32.dp) }
    Column(Modifier.weight(1f).then(if (closes) Modifier.ccSpeak(speech) else Modifier)) {
      Text(a.title, color = c.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(a.body, color = c.sec, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
    }
    if (closes && e != null) CcButton("Close", { ui.act(e, CcCalls.coverClose(e)) }, height = 64, enabled = canControl)
    else CcIcon("chev", c.sec, 30.dp)
  }
}

/** Designed empty state: nothing needs attention, said honestly from what the house has. */
@Composable
private fun AllClearRow(line: String) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).ccSpeak(CcSpeech.row("Everything's fine", line)).padding(start = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(60.dp).clip(ICON_TILE).background(c.greenBg), contentAlignment = Alignment.Center) { CcIcon("check", c.green, 32.dp) }
    Column {
      Text("Everything's fine", color = c.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
      Text(line, color = c.sec, fontSize = 19.sp)
    }
  }
}

private val ROW = RoundedCornerShape(20.dp)
private val ICON_TILE = RoundedCornerShape(18.dp)
