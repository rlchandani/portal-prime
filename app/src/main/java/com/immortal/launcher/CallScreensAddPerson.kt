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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Add person (mock `addScr`): a sheet over the call listing this home's rooms. Up to 4 people;
 * quiet hours, offline and busy rooms say why instead of ringing; a full call explains the cap.
 * Upright on the Mini the sheet takes the full width.
 */
@Composable
internal fun BoxScope.CallAddPersonSheet(call: ActiveCall, layout: LayoutClass, onClose: () -> Unit) {
  val context = LocalContext.current
  val peers by CallingStore.peers.collectAsState()
  val now = rememberCallClock(SHEET_CLOCK_MS)
  val rows = CallAddPeople.rows(call, peers, Calling.selfId(context), now)
  Box(Modifier.fillMaxSize().background(CallColors.Scrim).callClick("Close", true, onClose))
  val inset = if (layout.compact) 24.dp else 36.dp
  val sheet = if (layout.compactPortrait) Modifier.fillMaxWidth() else Modifier.width(if (layout.compact) 640.dp else 820.dp)
  Column(Modifier.align(Alignment.CenterEnd).padding(inset).then(sheet).fillMaxHeight()
      .callPanel(CallColors.Popover, RoundedCornerShape(40.dp), CallColors.GlassEdge).padding(horizontal = 36.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(22.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      CallRoundButton("back", "Back to the call", onClose, surface = CallDarkSurface, size = 80.dp)
      Column(Modifier.weight(1f)) {
        Text("Add to call", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(CallAddPeople.subtitle(call), color = Color.White.copy(alpha = 0.7f), fontSize = 23.sp)
      }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      if (call.full) FullCard()
      if (rows.isEmpty()) Text("No other Portals in this home yet. They appear here once they’re on the same Wi-Fi.",
          color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp)
      rows.forEach { row -> PersonRow(row, now) { if (Calling.addPerson(context, row.peer.id)) onClose() } }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      CallIcon("lock", Color.White.copy(alpha = 0.8f), 26.dp)
      Text("Everyone added gets the same end-to-end keys for this call only.", color = Color.White.copy(alpha = 0.75f),
          fontSize = 21.sp)
    }
  }
}

@Composable
private fun FullCard() {
  Row(Modifier.fillMaxWidth().background(CallColors.WarnTint, RoundedCornerShape(28.dp)).padding(28.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CallIcon("info", CallColors.WarnIcon, 38.dp)
    Text("Calls hold up to 4 so every face stays big and the Portal stays cool. Someone needs to leave before you add another.",
        color = Color.White, fontSize = 23.sp)
  }
}

@Composable
private fun PersonRow(row: CallAddPeople.Row, now: Long, onAdd: () -> Unit) {
  val presence = CallPolicy.presence(row.peer, now)
  Row(Modifier.fillMaxWidth().background(CallColors.ControlFill, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 18.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CallRoomAvatar(row.peer.name, 88.dp, presence, ring = CallColors.Popover)
    Column(Modifier.weight(1f)) {
      Text(row.peer.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(subtitle(row.option), color = Color.White.copy(alpha = 0.65f), fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (row.option) {
      CallAddPeople.Option.ADD -> CallPill("Add", onAdd, icon = "plus", style = CallButtonStyle.OK, size = CallButtonSize.SM)
      CallAddPeople.Option.IN_CALL -> CallChip("In the call", "check", CallChipTone.OK)
      CallAddPeople.Option.QUIET -> CallChip("Quiet hours", "moon", CallChipTone.QUIET)
      CallAddPeople.Option.FULL -> CallChip("Full")
      CallAddPeople.Option.BUSY -> CallChip("In a call", tone = CallChipTone.WARN)
      CallAddPeople.Option.OFFLINE -> CallChip("Offline")
      CallAddPeople.Option.CALLS_OFF -> CallChip("Calls off")
    }
  }
}

private fun subtitle(option: CallAddPeople.Option): String = when (option) {
  CallAddPeople.Option.ADD -> "Portal in this home · Available"
  CallAddPeople.Option.IN_CALL -> "Portal in this home"
  CallAddPeople.Option.QUIET -> "Quiet hours · won’t ring"
  CallAddPeople.Option.FULL -> "Portal in this home"
  CallAddPeople.Option.BUSY -> "In another call"
  CallAddPeople.Option.OFFLINE -> "Not heard from lately"
  CallAddPeople.Option.CALLS_OFF -> "Doesn’t take calls"
}

private const val SHEET_CLOCK_MS = 5_000L
