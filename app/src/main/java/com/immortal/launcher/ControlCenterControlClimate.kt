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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Thermostat page: dial, modes, and the plan rooms this thermostat sits in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CcClimateControl(ui: CcUi, e: CcEntity, d: CcDeviceSlice) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    CcThermostatDial(ui, e, d.gate, d.humidity, d.fahrenheit)
    if (!d.readOnly) CcThermostatModes(ui, e, d.gate)
  }
  if (d.rooms.isNotEmpty()) {
    CcCtlSection("Rooms on this thermostat")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      d.rooms.forEach { r -> key(r.roomId) { CcCtlChip(r.label, { ui.openRoom(r.roomId) }) } }
    }
  }
}

/** Fan page: the fan glyph spins with the speed; segments (or a slider for many speeds). */
@Composable
internal fun CcFanControl(ui: CcUi, e: CcEntity, gate: CcGate) {
  val speeds = CcControlMath.fanSpeeds(e)
  FanGlyph(e.isOn, e.percentage ?: FULL)
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

/** The fan spins in graphicsLayer only (no recomposition per frame); still when motion is off. */
@Composable
private fun FanGlyph(on: Boolean, pct: Int) {
  val c = LocalCc.current
  val spinning = on && LocalCcMotion.current
  val angle = if (spinning) {
    rememberInfiniteTransition(label = "fan")
        .animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(fanPeriodMs(pct), easing = LinearEasing), RepeatMode.Restart), label = "fanSpin")
  } else null
  Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
    CcIcon("fan", c.text, 220.dp, Modifier.size(220.dp).graphicsLayer { rotationZ = angle?.value ?: 0f })
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
