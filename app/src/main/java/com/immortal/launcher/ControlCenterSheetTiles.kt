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
import androidx.compose.runtime.key
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

/** `.tiles`: two columns of device tiles, keyed by entity so each tile skips on its own. */
@Composable
internal fun CcTileGrid(ui: CcUi, slice: CcRoomSlice) {
  val rows = slice.contents.tiles.chunked(2)
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    rows.forEach { row ->
      key(row.first().id) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          row.forEach { e ->
            key(e.id) {
              val kind = CcKind.of(e)
              CcDeviceTile(ui, e, pending = e.id in slice.pending, enabled = slice.canControl && !e.unavailable, done = e.id in slice.done,
                  stale = slice.stale, guarded = guarded(kind, slice), now = if (kind == CcKind.GARAGE) slice.minuteNow else 0L,
                  modifier = Modifier.weight(1f))
            }
          }
          if (row.size == 1) Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

private fun guarded(kind: CcKind, slice: CcRoomSlice): Boolean = when (kind) {
  CcKind.GARAGE -> slice.askGarage; CcKind.LOCK -> slice.askUnlock; CcKind.VALVE -> slice.askValve; else -> false
}

/**
 * `.tile`: tinted icon square, power button for lights/switches/media, name and status line.
 * Takes only its entity and primitives ([now] is 0 unless the status line shows a duration).
 */
@Composable
internal fun CcDeviceTile(ui: CcUi, e: CcEntity, pending: Boolean, enabled: Boolean, done: Boolean, stale: Boolean, guarded: Boolean,
                          now: Long, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val tone = CcSheetLogic.tone(e, done)
  val (iconBg, iconFg) = tileColors(e, tone)
  val status = CcSheetLogic.status(e, now, done)
  Column(modifier.heightIn(min = 150.dp).ccCard(CcStyle.Tile).clickable(role = Role.Button) { ui.openDevice(e.id) }
      .semantics { contentDescription = "${e.name}, $status" }
      .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(iconBg), contentAlignment = Alignment.Center) {
        CcIcon(tileIcon(e), iconFg, 32.dp)
      }
      Spacer(Modifier.weight(1f))
      CcCtlBusy(pending, 30)
      TileTrailing(ui, e, enabled, guarded)
    }
    // The tile itself says "name, status"; its texts are not nodes of their own.
    Column(Modifier.ccSilent()) {
      Text(e.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
      Text(status, color = if (stale) c.faint else if (tone == CcTileTone.WARN) c.amber else c.sec, fontSize = 19.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun TileTrailing(ui: CcUi, e: CcEntity, enabled: Boolean, guarded: Boolean) {
  val c = LocalCc.current
  if (CcSheetLogic.hasPower(e)) {
    val on = e.isOn
    Box(Modifier.padding(start = 10.dp).size(64.dp).clip(CircleShape).background(if (on) c.text else c.surface2)
        .clickable(enabled = enabled, role = Role.Switch) { ui.act(e, powerCall(e, !on)) }
        .semantics { contentDescription = "${e.name} power, ${if (on) "on" else "off"}" }, contentAlignment = Alignment.Center) {
      CcIcon("power", if (on) c.bg else c.text.copy(alpha = if (enabled) 1f else CC_DISABLED_ALPHA), 28.dp)
    }
    return
  }
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
internal fun CcSensorRows(ui: CcUi, contents: CcRoomContents, now: Long) {
  val sensors = contents.sensors
  Column(Modifier.fillMaxWidth().ccCard(CcStyle.Tile).padding(horizontal = 22.dp, vertical = 4.dp)) {
    sensors.forEachIndexed { i, e ->
      key(e.id) { SensorRow(ui, e, now, divider = i > 0) }
    }
  }
}

@Composable
private fun SensorRow(ui: CcUi, e: CcEntity, now: Long, divider: Boolean) {
  val c = LocalCc.current
  if (divider) HorizontalDivider(color = c.div, thickness = 1.dp)
  val tone = CcSheetLogic.tone(e)
  val status = CcSheetLogic.status(e, now)
  Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { ui.openDevice(e.id) }.ccSpeak("${e.name}, $status"),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    CcIcon(tileIcon(e), if (tone == CcTileTone.WARN) c.amber else c.sec, 30.dp)
    Text(e.name, color = c.text, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Text(status, color = if (tone == CcTileTone.WARN) c.amber else c.sec, fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** `.tile.on .ti` default (`--lc:#ffd18a`) and its ink. */
private val LIT = Color(0xFFFFD18A)
private val LIT_INK = Color(0xFF2A1A05)
