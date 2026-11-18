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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `.tiles`: two columns of device tiles. */
@Composable
internal fun CcTileGrid(s: CcUiState, ui: CcUi, tiles: List<CcEntity>) {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    tiles.chunked(2).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        row.forEach { CcDeviceTile(s, ui, it, Modifier.weight(1f)) }
        if (row.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

/** `.tile`: tinted icon square, power button for lights/switches/media, name and status line. */
@Composable
internal fun CcDeviceTile(s: CcUiState, ui: CcUi, e: CcEntity, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val done = e.id in s.appliancesDone
  val tone = CcSheetLogic.tone(e, done)
  val (iconBg, iconFg) = tileColors(e, tone)
  val status = CcSheetLogic.status(e, s.now, done)
  val stale = s.connection.phase == CcPhase.STALE
  Column(modifier.heightIn(min = 150.dp).ccCard(CcStyle.Tile).clickable(role = Role.Button) { ui.openDevice(e.id) }
      .semantics { contentDescription = "${e.name}, $status" }
      .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(iconBg), contentAlignment = Alignment.Center) {
        CcIcon(tileIcon(e), iconFg, 32.dp)
      }
      Spacer(Modifier.weight(1f))
      CcCtlBusy(e.id in s.pending, 30)
      TileTrailing(s, ui, e)
    }
    Column {
      Text(e.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
      Text(status, color = if (stale) c.faint else if (tone == CcTileTone.WARN) c.amber else c.sec, fontSize = 19.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun TileTrailing(s: CcUiState, ui: CcUi, e: CcEntity) {
  val c = LocalCc.current
  if (CcSheetLogic.hasPower(e)) {
    val on = e.isOn
    val enabled = ccGate(s, e).enabled
    Box(Modifier.padding(start = 10.dp).size(64.dp).clip(CircleShape).background(if (on) c.text else c.surface2)
        .clickable(enabled = enabled, role = Role.Switch) { ui.act(e, powerCall(e, !on)) }
        .semantics { contentDescription = "${e.name} power, ${if (on) "on" else "off"}" }, contentAlignment = Alignment.Center) {
      CcIcon("power", if (on) c.bg else c.text.copy(alpha = if (enabled) 1f else CC_DISABLED_ALPHA), 28.dp)
    }
    return
  }
  val guarded = when (CcKind.of(e)) { CcKind.GARAGE -> s.settings.askGarage; CcKind.LOCK -> s.settings.askUnlock; CcKind.VALVE -> s.settings.askValve; else -> false }
  if (guarded) CcIcon("shield", c.faint, 26.dp)
}

/** On/off for whatever the tile is. */
internal fun powerCall(e: CcEntity, on: Boolean): CcCall =
    if (e.domain == "media_player") CcCalls.mediaPower(e, on) else CcCalls.power(e, on)

@Composable
private fun tileColors(e: CcEntity, tone: CcTileTone): Pair<Color, Color> {
  val c = LocalCc.current
  return when (tone) {
    CcTileTone.ON -> (if (e.domain == "light") Color(0xFF000000.toInt() or CcRooms.glowColor(e)) else LIT) to LIT_INK
    CcTileTone.WARN -> c.amberBg to c.amber
    CcTileTone.LOCKED -> c.greenBg to c.green
    CcTileTone.NONE -> c.surface2 to c.text
  }
}

internal fun tileIcon(e: CcEntity): String = when (CcKind.of(e)) {
  CcKind.LOCK -> if (e.state == "locked") "lock" else "unlock"
  CcKind.MEDIA -> if (e.deviceClass == "speaker" || "speaker" in e.id) "speaker" else "tv"
  CcKind.APPLIANCE -> when {
    "dish" in e.id -> "dish"; "dryer" in e.id -> "dryer"; "oven" in e.id -> "oven"; else -> "washer"
  }
  else -> CcIcons.forKind(CcKind.of(e))
}

/** Sensors as read-only rows (value + when it last changed). */
@Composable
internal fun CcSensorRows(s: CcUiState, ui: CcUi, sensors: List<CcEntity>) {
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().ccCard(CcStyle.Tile).padding(horizontal = 22.dp, vertical = 4.dp)) {
    sensors.forEachIndexed { i, e ->
      if (i > 0) HorizontalDivider(color = c.div, thickness = 1.dp)
      val tone = CcSheetLogic.tone(e)
      Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { ui.openDevice(e.id) },
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CcIcon(tileIcon(e), if (tone == CcTileTone.WARN) c.amber else c.sec, 30.dp)
        Text(e.name, color = c.text, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(CcSheetLogic.status(e, s.now), color = if (tone == CcTileTone.WARN) c.amber else c.sec, fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
    }
  }
}

/** `.tile.on .ti` default (`--lc:#ffd18a`) and its ink. */
private val LIT = Color(0xFFFFD18A)
private val LIT_INK = Color(0xFF2A1A05)
