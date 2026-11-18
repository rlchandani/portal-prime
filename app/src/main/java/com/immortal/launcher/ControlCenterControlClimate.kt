/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/** Thermostat page: dial, modes, and the plan rooms this thermostat sits in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CcClimateControl(s: CcUiState, ui: CcUi, e: CcEntity, gate: CcGate) {
  val room = s.plan?.roomOf(e.id)
  val humidity = room?.let { CcRooms.view(it, s.entities, listOf(e)).humidity } ?: e.humidity
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    CcThermostatDial(ui, e, gate, humidity)
    if (!CcPolicy.readOnly(e, s.settings)) CcThermostatModes(ui, e, gate)
  }
  val rooms = CcSheetLogic.thermostatRooms(s.plan, e.id)
  if (rooms.isNotEmpty()) {
    CcCtlSection("Rooms on this thermostat")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      rooms.forEach { r ->
        val t = CcRooms.view(r, s.entities, listOf(e)).temperature
        CcCtlChip(listOfNotNull(r.name, t?.let { CcSheetLogic.temp(it) }).joinToString(" · "), { ui.openRoom(r.id) })
      }
    }
  }
}

/** Fan page: the fan glyph spins with the speed; segments (or a slider for many speeds). */
@Composable
internal fun CcFanControl(s: CcUiState, ui: CcUi, e: CcEntity, gate: CcGate) {
  val speeds = CcControlMath.fanSpeeds(e)
  FanGlyph(e)
  if (speeds.size > 1) {
    val labels = CcControlMath.fanLabels(speeds.size)
    val idx = CcControlMath.fanSpeedIndex(e, speeds)
    Box(Modifier.fillMaxWidth().alpha(if (gate.enabled) 1f else CC_DISABLED_ALPHA), contentAlignment = Alignment.Center) {
      CcSeg(speeds.indices.map { Triple(it, labels[it], null) }, idx, { i -> if (gate.enabled) ui.act(e, CcCalls.fanSpeed(e, speeds[i])) })
    }
  } else if (e.percentage != null) {
    CcCtlHSlider(if (e.isOn) e.percentage ?: 0 else 0, { "Speed $it%" }, { p -> ui.act(e, CcCalls.fanSpeed(e, p)) },
        enabled = gate.enabled, description = "${e.name} speed")
  }
  Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
    CcButton(if (e.isOn) "Turn off" else "Turn on", { ui.act(e, CcCalls.power(e, !e.isOn)) }, icon = "power", enabled = gate.enabled,
        style = if (e.isOn) CcButtonStyle.PRIMARY else CcButtonStyle.TONAL)
  }
}

@Composable
private fun FanGlyph(e: CcEntity) {
  val c = LocalCc.current
  val spinning = e.isOn && LocalCcMotion.current
  val period = fanPeriodMs(e.percentage ?: FULL)
  val angle = if (spinning) {
    val t = rememberInfiniteTransition(label = "fan")
    t.animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "fanSpin").value
  } else 0f
  Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
    CcIcon("fan", c.text, 220.dp, Modifier.size(220.dp).rotate(angle))
  }
}

/** The mock's spin periods: 1.6 s low … 0.45 s high. */
internal fun fanPeriodMs(pct: Int): Int {
  val p = pct.coerceIn(1, FULL)
  return (SLOW_MS - (SLOW_MS - FAST_MS) * p / FULL)
}

private const val FULL = 100
private const val FULL_TURN = 360f
private const val SLOW_MS = 1_600
private const val FAST_MS = 450
