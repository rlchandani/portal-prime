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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Picks the control for the device's kind (one composable per [CcKind]). */
@Composable
internal fun ColumnScope.CcDeviceControl(s: CcUiState, ui: CcUi, e: CcEntity) {
  val gate = ccGate(s, e)
  when (CcKind.of(e)) {
    CcKind.LIGHT -> CcSheetBody(scroll = false) { CcCtlReason(gate); CcLightControl(s, ui, e, gate) }
    CcKind.CLIMATE -> CcSheetBody { CcCtlReason(gate); CcClimateControl(s, ui, e, gate) }
    CcKind.LOCK -> CcSheetBody { CcCtlReason(gate); CcLockControl(s, ui, e, gate) }
    CcKind.GARAGE -> CcSheetBody { CcCtlReason(gate); CcGarageControl(s, ui, e, gate) }
    CcKind.COVER -> CcSheetBody { CcCtlReason(gate); CcCoverControl(s, ui, e, gate) }
    CcKind.FAN -> CcSheetBody { CcCtlReason(gate); CcFanControl(s, ui, e, gate) }
    CcKind.MEDIA -> CcSheetBody { CcCtlReason(gate); CcMediaControl(s, ui, e, gate) }
    CcKind.VALVE -> CcSheetBody { CcCtlReason(gate); CcValveControl(ui, e, gate) }
    CcKind.CAMERA -> CcSheetBody {
      CcCameraTile(s, e.id, live = false, modifier = Modifier.fillMaxWidth().height(446.dp), label = e.name,
          onClick = { ui.camera(e.id) })
    }
    CcKind.SWITCH -> CcSheetBody { CcCtlReason(gate); CcSwitchControl(ui, e, gate) }
    CcKind.APPLIANCE -> CcSheetBody {
      if (e.domain == "switch" || e.domain == "input_boolean") { CcCtlReason(gate); CcSwitchControl(ui, e, gate) }
      else CcSensorControl(s, e)
    }
    else -> CcSheetBody { CcSensorControl(s, e) }
  }
}

/** Switches: one 220 dp power button (`.hbtn` in the mock) and the state under it. */
@Composable
internal fun CcSwitchControl(ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  val on = e.isOn
  Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(220.dp).alpha(if (gate.enabled) 1f else CC_DISABLED_ALPHA)
        .then(if (on) Modifier.clip(CircleShape).background(c.text) else Modifier.ccCard(CircleShape)).clip(CircleShape)
        .clickable(enabled = gate.enabled, role = Role.Switch) { ui.act(e, powerCall(e, !on)) }
        .semantics { contentDescription = "${e.name} ${if (on) "on" else "off"}" }, contentAlignment = Alignment.Center) {
      CcIcon("power", if (on) c.bg else c.text, 96.dp)
    }
    Text(if (on) "On" else "Off", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp))
    if (e.lastChanged > 0) CcCtlNote("Since ${ccTime(e.lastChanged)}", Modifier.padding(top = 8.dp))
  }
}

/** Main water valve: big state circle; shutting off asks first (via [CcUi.act]). */
@Composable
internal fun CcValveControl(ui: CcUi, e: CcEntity, gate: CcGate) {
  val c = LocalCc.current
  val open = e.state == "open" || e.state == "opening"
  Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    BigState("valve", if (open) c.greenBg else c.redBg, if (open) c.green else c.red)
    Text(if (open) "Water is on" else "Water shut off", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    if (e.lastChanged > 0) CcCtlNote("Since ${ccTime(e.lastChanged)}", Modifier.padding(top = 6.dp))
    Box(Modifier.padding(top = 24.dp)) {
      if (open) CcButton("Shut off water…", { ui.act(e, CcCalls.valveClose(e)) }, style = CcButtonStyle.DANGER, enabled = gate.enabled)
      else CcButton("Turn water back on", { ui.act(e, CcCalls.valveOpen(e)) }, enabled = gate.enabled)
    }
  }
}

/** Sensors and anything read-only: the value, when it changed, battery if known. */
@Composable
internal fun CcSensorControl(s: CcUiState, e: CcEntity) {
  val c = LocalCc.current
  val tone = CcSheetLogic.tone(e, e.id in s.appliancesDone)
  val warn = tone == CcTileTone.WARN
  Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    BigState(tileIcon(e), if (warn) c.amberBg else c.surface2, if (warn) c.amber else c.text)
    Text(CcSheetLogic.status(e, s.now, e.id in s.appliancesDone), color = if (warn) c.amber else c.text, fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val facts = listOfNotNull(e.battery?.let { "Battery $it%" }, e.lastChanged.takeIf { it > 0 }?.let { "Changed ${ccTime(it)}" })
    if (facts.isNotEmpty()) CcCtlNote(facts.joinToString(" · "), Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 28.dp).ccCard(CcStyle.Tile).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
      CcIcon("eye", c.sec, 28.dp)
      Text("Read only — this sensor reports to Home Assistant; there's nothing to switch.", color = c.sec, fontSize = 20.sp)
    }
  }
}

/** `.bigLock`: the 260 dp state circle used by locks, valves and sensors. */
@Composable
internal fun BigState(icon: String, bg: Color, fg: Color) {
  Box(Modifier.padding(vertical = 10.dp).size(260.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
    CcIcon(icon, fg, 120.dp)
  }
}
