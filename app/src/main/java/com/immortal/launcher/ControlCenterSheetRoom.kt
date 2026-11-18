/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The room page (`roomView` in the mock): name + chips, alerts, camera & climate, scenes, devices.
 * One [CcRoomSlice] is derived per snapshot; every section takes only its part of it, and each
 * tile only its entity and primitives, so one light changing recomposes one tile.
 */
@Composable
internal fun ColumnScope.CcRoomSheet(s: CcUiState, ui: CcUi, roomId: String) {
  val slice = remember(s, roomId) { CcSlices.room(s, roomId) }
  if (slice == null) {
    RoomHead(ui, "Room", null)
    Column(Modifier.padding(34.dp)) {
      CcCtlEmpty("plan", "This room is no longer on the plan", "It may have been removed in Edit plan.")
    }
    return
  }
  val contents = slice.contents
  RoomHead(ui, slice.room.name, slice.head)
  CcSheetBody {
    slice.alerts.forEach { a -> key(a.alert.id) { RoomAlert(ui, a, slice.canControl) } }
    if (contents.cameraId != null || contents.climate != null) {
      Row(Modifier.fillMaxWidth().height(250.dp).padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        contents.cameraId?.let { cam ->
          CcCameraTile(s, cam, live = false, modifier = Modifier.weight(1.1f).fillMaxHeight(), label = slice.room.name, onClick = { ui.camera(cam) })
        }
        contents.climate?.let { cl ->
          ClimateCard(ui, cl, slice.climateRoomTemp, slice.climateGate?.enabled == true, slice.climateReadOnly, Modifier.weight(1f).fillMaxHeight())
        }
      }
    }
    RoomScenes(ui, slice.scenes)
    CcCtlSection("Devices · ${contents.tiles.size}")
    if (contents.tiles.isEmpty() && contents.sensors.isEmpty()) {
      CcCtlEmpty("plus", "No devices placed here yet",
          "Place lights, locks and sensors in this room from Edit plan, and they'll show up here.",
          action = if (slice.kids) null else "Edit plan" to { ui.go(CcScreen.EDIT) })
    } else CcTileGrid(ui, slice)
    if (contents.sensors.isNotEmpty()) {
      CcCtlSection("Sensors")
      CcSensorRows(ui, contents, slice.minuteNow)
    }
    if (contents.hiddenCount > 0) KidsHidden(contents.hiddenCount)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomHead(ui: CcUi, title: String, head: CcRoomHead?) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 30.dp, bottom = 14.dp), verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f)) {
      Text(title, color = c.text, fontSize = 50.sp, fontFamily = CcStyle.Serif, lineHeight = 54.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (head != null) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)) {
        head.temp?.let { CcCtlTag(it, "therm") }
        head.humidity?.let { CcCtlTag(it, "drop") }
        head.presence?.let { (text, active) -> CcCtlTag(text, "person", if (active) CcTone.GREEN else CcTone.NEUTRAL) }
        if (head.lightsOn > 0) CcCtlTag("${head.lightsOn} on", tone = CcTone.AMBER)
      }
    }
    CcSheetClose(ui)
  }
}

@Composable
private fun RoomAlert(ui: CcUi, ra: CcRoomAlert, canControl: Boolean) {
  val c = LocalCc.current
  val a = ra.alert
  val (bg, fg) = when (a.severity) { CcSeverity.CRITICAL -> c.redBg to c.red; CcSeverity.WARNING -> c.amberBg to c.amber; CcSeverity.INFO -> c.sel to c.text }
  Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).ccCard(CcStyle.Tile).clickable(role = Role.Button) { ui.openDevice(a.entityId) }
      .padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(58.dp).clip(CcStyle.CardSmall).background(bg), contentAlignment = Alignment.Center) { CcIcon(CcIcons.forKind(a.kind), fg, 32.dp) }
    Column(Modifier.weight(1f)) {
      Text(a.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(a.body, color = c.sec, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val e = ra.closable
    if (e != null) CcButton("Close…", { ui.act(e, CcCalls.coverClose(e)) }, icon = "garage", height = 64, enabled = canControl)
    else CcIcon("chev", c.faint, 28.dp)
  }
}

@Composable
private fun ClimateCard(ui: CcUi, e: CcEntity, roomTemp: Double?, enabled: Boolean, readOnly: Boolean, modifier: Modifier) {
  val c = LocalCc.current
  val target = e.targetTemp
  val action = e.hvacAction ?: e.state
  Column(modifier.ccCard(CcStyle.Tile).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.SpaceBetween) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(e.name, color = c.sec, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      CcCtlTag(CcSheetLogic.pretty(action).lowercase(), tone = if (action == "heating" || action == "cooling") CcTone.AMBER else CcTone.NEUTRAL)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      val step = e.tempStep.takeIf { it > 0 } ?: 1.0
      if (!readOnly && target != null)
        CcRoundButton("minus", { ui.act(e, CcCalls.temperature(e, target - step)) }, "Lower ${e.name}", size = 64, enabled = enabled)
      Text(target?.let { CcSheetLogic.temp(it) } ?: CcSheetLogic.climateText(e), color = c.text, fontSize = 56.sp, fontWeight = FontWeight.Light,
          textAlign = TextAlign.Center, maxLines = 1,
          modifier = Modifier.weight(1f).clickable(role = Role.Button) { ui.openDevice(e.id) })
      if (!readOnly && target != null)
        CcRoundButton("plus", { ui.act(e, CcCalls.temperature(e, target + step)) }, "Raise ${e.name}", size = 64, enabled = enabled)
    }
    Text(listOfNotNull(roomTemp?.let { "Room is ${CcSheetLogic.temp(it)}" }, "tap for schedule").joinToString(" · "), color = c.sec, fontSize = 18.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { ui.openDevice(e.id) })
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomScenes(ui: CcUi, part: CcRoomScenes) {
  val (scenes, lightsOn, hasLights, can) = part
  if (scenes.isEmpty() && !hasLights) return
  CcCtlSection("Scenes")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    scenes.forEach { sc -> key(sc.id) { CcCtlChip(sc.name, { ui.runScene(sc) }, icon = sceneIcon(sc.name), enabled = can) } }
    if (hasLights) CcCtlChip("All off", { lightsOn.forEach { ui.act(it, CcCalls.lightPower(it, false)) } }, icon = "power",
        enabled = can && lightsOn.isNotEmpty())
  }
}

/** A glyph that suits the scene's name, like the mock's Bright/Relax/Night light/Movie chips. */
internal fun sceneIcon(name: String): String {
  val n = name.lowercase()
  return when {
    "night" in n || "sleep" in n || "dim" in n -> "moon"
    "movie" in n || "tv" in n || "film" in n -> "film"
    "bright" in n || "morning" in n || "day" in n -> "sun"
    "leav" in n || "away" in n -> "leave"
    "relax" in n || "read" in n || "warm" in n -> "bulb"
    else -> "sparkle"
  }
}

@Composable
private fun KidsHidden(count: Int) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().padding(top = 16.dp).ccCard(CcStyle.Tile).padding(horizontal = 24.dp, vertical = 20.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    CcIcon("hand", c.text, 30.dp)
    Text(if (count == 1) "One more thing here — ask a grown-up" else "$count more things here — ask a grown-up", color = c.text, fontSize = 21.sp)
  }
}
