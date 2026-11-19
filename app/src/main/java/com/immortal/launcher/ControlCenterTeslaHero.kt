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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** A −/+ burst on the charge limit is sent once, this long after the last tap (one car command, not five). */
private const val LIMIT_SETTLE_MS = 1200L
private const val PCT = 100f

/** The hero card (`tesla-premium-final`): name and state, the car, the big battery figure, the charge bar and details. */
@Composable
internal fun CcTeslaHero(s: CcUiState, ui: CcUi, ids: CcTeslaIds, car: CcCarView, charger: CcChargerView?, canAct: Boolean, modifier: Modifier) {
  val motion = LocalCcMotion.current && LocalCcScreenActive.current
  val cable = when {
    car.charging -> CcCableLook.CHARGING
    car.pluggedIn == true -> CcCableLook.PLUGGED
    else -> CcCableLook.NONE
  }
  Column(modifier.ccCard().padding(horizontal = 34.dp, vertical = 28.dp)) {
    TitleRow(s, ui, ids, car, canAct)
    CcCarArt(car.paint, cable, charger != null, animate = motion && car.life == CcCarLife.AWAKE, modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
    BatteryRow(car, s.now)
    ChargeBar(car.batteryPct, car.limitPct, car.charging, Modifier.padding(top = 18.dp))
    Details(s, ui, ids, car, charger, canAct, Modifier.padding(top = 22.dp))
  }
}

@Composable
private fun TitleRow(s: CcUiState, ui: CcUi, ids: CcTeslaIds, car: CcCarView, canAct: Boolean) {
  val c = LocalCc.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f)) {
      Text(ids.name ?: CcTeslaIds.DEFAULT_LABEL, color = c.text, fontSize = 44.sp, fontFamily = CcStyle.Serif, maxLines = 1)
      Text(listOfNotNull(ids.model, "updated ${CcTeslaText.ago(car.updatedAt, s.now)}").joinToString(" · "), color = c.sec, fontSize = 20.sp, maxLines = 1)
    }
    LifeTag(car)
    val wake = ids.car[CcCarRole.WAKE]?.let(s::entity)?.takeIf { it.state != "unavailable" }
    if (car.life == CcCarLife.ASLEEP && wake != null && !s.settings.kids) {
      val press = remember(wake, ui) { { ui.act(wake, CcTeslaCalls.press(wake, "Waking the car")) } }
      CcButton("Wake", press, icon = "power", height = 64, enabled = canAct)
    }
  }
}

@Composable
private fun LifeTag(car: CcCarView) {
  val (text, tone) = when (car.life) {
    CcCarLife.STALE -> "Last known" to CcTone.AMBER
    CcCarLife.OFFLINE -> "Offline" to CcTone.RED
    CcCarLife.ASLEEP -> "Asleep" to CcTone.NEUTRAL
    CcCarLife.AWAKE -> (if (car.charging) "Charging" else car.place ?: "Online") to (if (car.charging) CcTone.GREEN else CcTone.NEUTRAL)
  }
  CcTag(text, tone)
}

@Composable
private fun BatteryRow(car: CcCarView, now: Long) {
  val c = LocalCc.current
  Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(verticalAlignment = Alignment.Top) {
      Text(car.batteryPct?.toString() ?: "—", color = c.text, fontSize = 128.sp, fontWeight = FontWeight.Light, lineHeight = 128.sp, maxLines = 1)
      Text("%", color = c.sec, fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 18.dp))
    }
    Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      car.range?.let { Text(it, color = c.text, fontSize = 32.sp, maxLines = 1) }
      Text(CcTeslaText.headline(car, now), color = if (car.charging && car.life == CcCarLife.AWAKE) c.green else c.sec, fontSize = 22.sp, maxLines = 1)
    }
  }
}

/** Battery fill with the charge-limit tick (the mock's green bar and white marker). */
@Composable
private fun ChargeBar(pct: Int?, limit: Int?, charging: Boolean, modifier: Modifier) {
  val c = LocalCc.current
  BoxWithConstraints(modifier.fillMaxWidth().height(18.dp).clip(CcStyle.Pill).background(c.surface2)) {
    val w = maxWidth
    Box(Modifier.fillMaxHeight().width(w * ((pct ?: 0) / PCT)).clip(CcStyle.Pill).background(if (charging) c.green else c.green.copy(alpha = BAR_IDLE)))
    limit?.let { Box(Modifier.offset(x = w * (it / PCT) - 2.dp).width(4.dp).fillMaxHeight().background(c.text)) }
  }
}

private const val BAR_IDLE = 0.8f

/**
 * Charging: power, energy added, time to full and the limit. Otherwise three tiles: what the
 * charge port is doing with an estimate to the limit, the last session's energy and the limit.
 */
@Composable
private fun Details(s: CcUiState, ui: CcUi, ids: CcTeslaIds, car: CcCarView, charger: CcChargerView?, canAct: Boolean, modifier: Modifier) {
  val added = car.energyAddedKwh?.let { "${CcTeslaText.kwh(it)} kWh" } ?: "—"
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    if (car.charging) {
      Tile(car.powerKw?.let { "${CcTeslaText.kw(it)} kW" } ?: "—", "charging power")
      Tile(added, "added this session")
      Tile(CcTeslaText.toFull(car.fullAt, s.now) ?: "—", "to full")
    } else {
      val idle = remember(car, charger, ids.model) { CcTeslaIdle.of(car, charger, ids.model) }
      IdleTile(idle)
      Tile(added, "last charge")
    }
    LimitTile(s, ui, ids, car, canAct)
  }
}

@Composable
private fun RowScope.IdleTile(idle: CcIdleCharge) {
  val c = LocalCc.current
  Row(Modifier.weight(IDLE_WEIGHT).clip(TILE).background(c.surface2).padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    CcIcon("charger", c.sec, 34.dp)
    Column {
      Text(idle.title, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      Text(idle.detail ?: "Not charging", color = c.sec, fontSize = 18.sp, maxLines = 1)
    }
  }
}

@Composable
private fun RowScope.Tile(value: String, label: String) {
  val c = LocalCc.current
  Column(Modifier.weight(1f).clip(TILE).background(c.surface2).padding(horizontal = 20.dp, vertical = 16.dp)) {
    Text(value, color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text(label, color = c.sec, fontSize = 18.sp, maxLines = 1)
  }
}

@Composable
private fun RowScope.LimitTile(s: CcUiState, ui: CcUi, ids: CcTeslaIds, car: CcCarView, canAct: Boolean) {
  val c = LocalCc.current
  val number = ids.car[CcCarRole.CHARGE_LIMIT]?.let(s::entity)?.takeIf { !it.unavailable }
  var pending by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(pending) {
    val want = pending ?: return@LaunchedEffect
    delay(LIMIT_SETTLE_MS)
    number?.let { ui.act(it, CcTeslaCalls.limit(it, want)) }
    pending = null
  }
  val shown = pending ?: car.limitPct
  Row(Modifier.weight(LIMIT_WEIGHT).clip(TILE).background(c.surface2).padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(shown?.let { "$it%" } ?: "—", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      Text("charge limit", color = c.sec, fontSize = 18.sp, maxLines = 1)
    }
    if (number != null && shown != null && !s.settings.kids) {
      val step = { up: Boolean -> pending = CcTeslaCalls.stepLimit(shown, up, car.limitMin, car.limitMax) }
      CcRoundButton("minus", { step(false) }, "Lower charge limit", size = 64, enabled = canAct && shown > car.limitMin)
      CcRoundButton("plus", { step(true) }, "Raise charge limit", size = 64, enabled = canAct && shown < car.limitMax, modifier = Modifier.padding(start = 8.dp))
    }
  }
}

private val TILE = RoundedCornerShape(22.dp)
/** The limit tile carries two 64 dp buttons, so it is wider than the read-only tiles. */
private const val LIMIT_WEIGHT = 1.6f
/** The idle tile stands in for two read-only tiles. */
private const val IDLE_WEIGHT = 2.2f
