/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Reload the week's charging statistics this often while the screen is shown (HA's recorder, never the car). */
private const val WEEK_RELOAD_MS = 15 * 60 * 1000L
private const val LEFT_WEIGHT = 1.25f

/**
 * Tesla (replaces the old Energy screen): the car hero with battery, range and charging on the
 * left, then its controls; the home charger with this week's charging and the car's status on the
 * right. Kids mode shows everything read-only. Not set up / offline / asleep / stale each say so.
 */
@Composable
internal fun CcTeslaScreen(s: CcUiState, ui: CcUi) {
  val ids = s.registry?.tesla ?: CcTeslaIds.NONE
  val car = remember(s.entitiesVersion, ids, s.connection.phase) { CcTeslaCar.view(ids, s.entities, s.connection.phase) }
  val charger = remember(s.entitiesVersion, ids) { CcTeslaCharger.view(ids, s.entities) }
  WeekLoader(ids.weekStat, s.connection.canControl, ui)
  Box(Modifier.fillMaxSize()) {
    when {
      car == null && s.registry == null -> CcCenteredMessage("car", "Looking for your car…",
          "Reading Home Assistant's devices. This needs an administrator token to see the car's integration.")
      car == null -> CcCenteredMessage("car", "Add your Tesla to Home Assistant", NOT_SET_UP)
      else -> TeslaBody(s, ui, ids, car, charger)
    }
  }
}

private const val NOT_SET_UP = "This screen reads the Tesla Fleet, Teslemetry or Tessie integration " +
    "(Settings › Devices & services › Add integration). Add the Tesla Wall Connector integration as well for home charging power " +
    "and a week of charging history."

@Composable
private fun WeekLoader(stat: String?, live: Boolean, ui: CcUi) {
  val active = LocalCcScreenActive.current
  val context = ui.context
  LaunchedEffect(stat, live, active) {
    if (stat == null || !live || !active) return@LaunchedEffect
    while (true) { ControlCenter.loadChargeWeek(context); delay(WEEK_RELOAD_MS) }
  }
}

@Composable
private fun TeslaBody(s: CcUiState, ui: CcUi, ids: CcTeslaIds, car: CcCarView, charger: CcChargerView?) {
  val kids = s.settings.kids
  val actions = remember(s.entitiesVersion, ids, car) { CcTeslaActions.list(ids, s.entities, car) }
  val canAct = s.connection.canControl && car.life != CcCarLife.OFFLINE && !kids
  val dim = if (car.life == CcCarLife.STALE) CC_DIMMED_ALPHA else 1f
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    Column(Modifier.weight(LEFT_WEIGHT).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
      CcTeslaHero(s, ui, ids, car, charger, canAct, Modifier.weight(1f).fillMaxWidth().alpha(dim))
      CcTeslaControls(actions, canAct, kids, s.entities, ui, Modifier.fillMaxWidth())
    }
    Column(Modifier.weight(1f).fillMaxHeight().alpha(dim), verticalArrangement = Arrangement.spacedBy(22.dp)) {
      CcChargerCard(charger, s.chargeWeek, ids.charger.isNotEmpty(), Modifier.weight(1f).fillMaxWidth())
      CcCarStatusCard(car, Modifier.fillMaxWidth())
    }
  }
}
