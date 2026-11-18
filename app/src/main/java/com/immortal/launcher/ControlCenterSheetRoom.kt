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

/** The room page (`roomView` in the mock): name + chips, alerts, camera & climate, scenes, devices. */
@Composable
internal fun ColumnScope.CcRoomSheet(s: CcUiState, ui: CcUi, roomId: String) {
  val room = s.plan?.room(roomId)
  if (room == null) {
    RoomHead(s, ui, null, "Room")
    Column(Modifier.padding(34.dp)) {
      CcCtlEmpty("plan", "This room is no longer on the plan", "It may have been removed in Edit plan.")
    }
    return
  }
  val contents = remember(s.entities, s.settings, room) { CcSheetLogic.contents(s, room) }
  RoomHead(s, ui, room, room.name)
  CcSheetBody {
    CcSheetLogic.alerts(s, room).forEach { RoomAlert(s, ui, it) }
    CameraAndClimate(s, ui, room, contents)
    RoomScenes(s, ui, room)
    CcCtlSection("Devices · ${contents.tiles.size}")
    if (contents.tiles.isEmpty() && contents.sensors.isEmpty()) {
      CcCtlEmpty("plus", "No devices placed here yet",
          "Place lights, locks and sensors in this room from Edit plan, and they'll show up here.",
          action = if (s.settings.kids) null else "Edit plan" to { ui.go(CcScreen.EDIT) })
    } else CcTileGrid(s, ui, contents.tiles)
    if (contents.sensors.isNotEmpty()) {
      CcCtlSection("Sensors")
      CcSensorRows(s, ui, contents.sensors)
    }
    if (contents.hiddenCount > 0) KidsHidden(contents.hiddenCount)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomHead(s: CcUiState, ui: CcUi, room: CcRoom?, title: String) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 30.dp, bottom = 14.dp), verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f)) {
      Text(title, color = c.text, fontSize = 50.sp, fontFamily = CcStyle.Serif, lineHeight = 54.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (room != null) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)) { RoomChips(s, room) }
    }
    CcSheetClose(ui)
  }
}

@Composable
private fun RoomChips(s: CcUiState, room: CcRoom) {
  val view = remember(s.entities, room) { CcRooms.view(room, s.entities, s.entities.values.filter { it.domain == "climate" }) }
  view.temperature?.let { CcCtlTag(CcSheetLogic.temp(it), "therm") }
  view.humidity?.let { CcCtlTag("$it%", "drop") }
  CcSheetLogic.presence(s, room)?.let { (text, active) -> CcCtlTag(text, "person", if (active) CcTone.GREEN else CcTone.NEUTRAL) }
  val on = CcSheetLogic.lightsOn(s, room).size
  if (on > 0) CcCtlTag("$on on", tone = CcTone.AMBER)
}

@Composable
private fun RoomAlert(s: CcUiState, ui: CcUi, a: CcAlert) {
  val c = LocalCc.current
  val (bg, fg) = when (a.severity) { CcSeverity.CRITICAL -> c.redBg to c.red; CcSeverity.WARNING -> c.amberBg to c.amber; CcSeverity.INFO -> c.sel to c.text }
  val e = s.entity(a.entityId)
  Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).ccCard(CcStyle.Tile).clickable(role = Role.Button) { ui.openDevice(a.entityId) }
      .padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(58.dp).clip(CcStyle.CardSmall).background(bg), contentAlignment = Alignment.Center) { CcIcon(CcIcons.forKind(a.kind), fg, 32.dp) }
    Column(Modifier.weight(1f)) {
      Text(a.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(a.body, color = c.sec, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (a.action == CcAlertAction.CLOSE && e != null && CcModalLogic.closable(e))
      CcButton("Close…", { ui.act(e, CcCalls.coverClose(e)) }, icon = "garage", height = 64, enabled = s.connection.canControl)
    else CcIcon("chev", c.faint, 28.dp)
  }
}

@Composable
private fun CameraAndClimate(s: CcUiState, ui: CcUi, room: CcRoom, contents: CcRoomContents) {
  val cam = contents.cameraId
  val cl = contents.climate
  if (cam == null && cl == null) return
  Row(Modifier.fillMaxWidth().height(250.dp).padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    if (cam != null) CcCameraTile(s, cam, live = false, modifier = Modifier.weight(1.1f).fillMaxHeight(), label = room.name, onClick = { ui.camera(cam) })
    if (cl != null) ClimateCard(s, ui, cl, room, Modifier.weight(1f).fillMaxHeight())
  }
}

@Composable
private fun ClimateCard(s: CcUiState, ui: CcUi, e: CcEntity, room: CcRoom, modifier: Modifier) {
  val c = LocalCc.current
  val gate = ccGate(s, e)
  val readOnly = CcPolicy.readOnly(e, s.settings)
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
        CcRoundButton("minus", { ui.act(e, CcCalls.temperature(e, target - step)) }, "Lower ${e.name}", size = 64, enabled = gate.enabled)
      Text(target?.let { CcSheetLogic.temp(it) } ?: CcSheetLogic.climateText(e), color = c.text, fontSize = 56.sp, fontWeight = FontWeight.Light,
          textAlign = TextAlign.Center, maxLines = 1,
          modifier = Modifier.weight(1f).clickable(role = Role.Button) { ui.openDevice(e.id) })
      if (!readOnly && target != null)
        CcRoundButton("plus", { ui.act(e, CcCalls.temperature(e, target + step)) }, "Raise ${e.name}", size = 64, enabled = gate.enabled)
    }
    val now = CcRooms.view(room, s.entities).temperature ?: e.currentTemp
    Text(listOfNotNull(now?.let { "Room is ${CcSheetLogic.temp(it)}" }, "tap for schedule").joinToString(" · "), color = c.sec, fontSize = 18.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { ui.openDevice(e.id) })
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomScenes(s: CcUiState, ui: CcUi, room: CcRoom) {
  val scenes = CcSheetLogic.scenes(s, room)
  val lightsOn = CcSheetLogic.lightsOn(s, room)
  val hasLights = room.devices.any { it.entityId.startsWith("light.") }
  if (scenes.isEmpty() && !hasLights) return
  val can = s.connection.canControl
  CcCtlSection("Scenes")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    scenes.forEach { sc -> CcCtlChip(sc.name, { ui.runScene(sc) }, icon = sceneIcon(sc.name), enabled = can) }
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
