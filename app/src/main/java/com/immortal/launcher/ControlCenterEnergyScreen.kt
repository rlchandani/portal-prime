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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Reload today/week totals this often while the screen is shown. */
private const val ENERGY_RELOAD_MS = 5 * 60 * 1000L

/** Energy (`energy`, `energy-week`): live flow left; KPIs, the day/week chart and top consumers right. */
@Composable
internal fun CcEnergyScreen(s: CcUiState, ui: CcUi) {
  val context = ui.context
  LaunchedEffect(Unit) { while (true) { ControlCenter.loadEnergy(context); delay(ENERGY_RELOAD_MS) } }
  LaunchedEffect(s.energyPrefs, s.connection.canControl) { if (s.energyPrefs != null && s.connection.canControl) ControlCenter.loadEnergy(context) }
  val now = remember(s.entitiesVersion) { CcEnergy.now(s.entities) }
  val ev = remember(s.entitiesVersion, s.connection.canControl) { CcEnergyKpis.ev(s.entities, now.chargerW, s.connection.canControl) }
  val consumers = remember(now.consumers, s.plan) { CcEnergyKpis.consumerItems(now.consumers) { id -> s.plan?.roomOf(id)?.let { it.id to it.name } } }
  var week by rememberSaveable { mutableStateOf(false) }
  val onWeek = remember { { w: Boolean -> week = w } }
  val series = if (week) s.energyWeek else s.energyToday
  val kpis = remember(series, s.energyPrefs, week) { series?.let { CcKpis(CcEnergyKpis.kpis(it, s.energyPrefs, week)) } }
  val dim = if (s.connection.phase == CcPhase.STALE) CC_DIMMED_ALPHA else 1f
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    CcFlowCard(now, s.ready, ev, ui, Modifier.weight(FLOW_WEIGHT).fillMaxHeight().alpha(dim))
    Column(Modifier.weight(1f).fillMaxHeight().alpha(dim), verticalArrangement = Arrangement.spacedBy(22.dp)) {
      kpis?.let { Kpis(it) }
      ChartCard(week, onWeek, s.energyPrefs, series, s.minute, Modifier.weight(1f).fillMaxWidth())
      Consumers(consumers, ui)
    }
  }
}

private const val FLOW_WEIGHT = 1.05f

@Composable
private fun CcFlowCard(now: CcEnergyNow, ready: Boolean, ev: CcEvSlice?, ui: CcUi, modifier: Modifier) {
  val status = remember(now) { CcEnergyFlow.status(now) }
  val empty = remember(now) { CcEnergyFlow.nodes(now).isEmpty() }
  Column(modifier.ccCard().padding(28.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      CcSectionLabel("Right now", Modifier.weight(1f))
      status?.let { IconTag(it) }
    }
    if (empty) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      EnergyNote("bolt", if (ready) "No power sensors yet" else "Loading power…",
          "The live flow needs power sensors in watts: solar production, grid power (positive while importing, negative while exporting) " +
              "and battery power (positive while discharging). Give them device_class power in Home Assistant.")
    } else CcEnergyFlowDiagram(now, Modifier.weight(1f).fillMaxWidth())
    ev?.let { EvRow(it, ui) }
  }
}

@Composable
private fun IconTag(st: CcEnergyStatus) {
  val c = LocalCc.current
  val (bg, fg) = when (st.tone) { CcEnergyTone.GREEN -> c.greenBg to c.green; CcEnergyTone.AMBER -> c.amberBg to c.amber; CcEnergyTone.NEUTRAL -> c.surface2 to c.sec }
  Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(bg).padding(horizontal = 13.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    CcIcon(st.icon, fg, 20.dp)
    Text(st.text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

/** `.ev`: the car charger, its state, the car's charge and "Charge now" when HA has a charge switch. */
@Composable
private fun EvRow(ev: CcEvSlice, ui: CcUi) {
  val c = LocalCc.current
  Box(Modifier.fillMaxWidth().height(1.dp).background(c.div))
  Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    CcIcon("car", c.sec, 34.dp)
    Column(Modifier.weight(1f)) {
      Text(ev.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      ev.soc?.let { pct ->
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surface2)) {
          Box(Modifier.fillMaxWidth(pct / PCT).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(c.green))
        }
      }
    }
    ev.switch?.let { sw ->
      val toggle = remember(sw, ui) { { ui.act(sw, CcCalls.power(sw, !sw.isOn)) } }
      CcButton(if (sw.isOn) "Stop" else "Charge now", toggle, height = 64, enabled = ev.canControl)
    }
  }
}

private const val PCT = 100f

@Composable
private fun Kpis(kpis: CcKpis) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    kpis.items.forEach { k ->
      Column(Modifier.weight(1f).ccCard(RoundedCornerShape(26.dp)).padding(horizontal = 22.dp, vertical = 20.dp)) {
        Text(k.value, color = c.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, lineHeight = 38.sp)
        Text(k.label, color = c.sec, fontSize = 18.sp, maxLines = 1)
      }
    }
  }
}

@Composable
private fun ChartCard(week: Boolean, onWeek: (Boolean) -> Unit, prefs: CcEnergyPrefs?, series: CcEnergySeries?, minute: Long, modifier: Modifier) {
  Column(modifier.ccCard().padding(horizontal = 26.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      CcSectionLabel(if (week) "This week · kWh used, solar share" else "Today · use vs solar (kW)", Modifier.weight(1f))
      CcSeg(RANGES, week, onWeek)
    }
    val model = remember(series, week, minute) {
      series?.let { if (week) CcChart.week(it) else CcChart.today(it, minute * MS_PER_MINUTE) }
    }
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      when {
        prefs == null -> EnergyNote("clock", "Loading energy…", "Asking Home Assistant for today's totals.")
        !prefs.configured -> EnergyNote("bolt", "Set up Energy in Home Assistant",
            "Totals and this chart come from Home Assistant's Energy dashboard (Settings › Dashboards › Energy). " +
                "Add your grid and solar there; the live flow keeps working from power sensors.")
        model == null -> EnergyNote("clock", if (week) "Loading this week…" else "Loading today…", "Reading the recorder's hourly statistics.")
        else -> CcEnergyChart(model, Modifier.fillMaxSize())
      }
    }
  }
}

private val RANGES = listOf(Triple(false, "Today", null), Triple(true, "Week", null))
private const val MS_PER_MINUTE = 60_000L

/** TOP CONSUMERS NOW: the biggest other power sensors, with their room and an ember bar. */
@Composable
private fun Consumers(consumers: CcConsumerItems, ui: CcUi) {
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    CcSectionLabel("Top consumers now")
    if (consumers.items.isEmpty()) Text("No other power sensors are drawing power right now. Smart plugs with power monitoring show up here.",
        color = c.sec, fontSize = 19.sp, lineHeight = 26.sp, modifier = Modifier.padding(vertical = 8.dp))
    consumers.items.forEach { item -> key(item.consumer.entityId) { CcConsumerRow(item, ui) } }
  }
}

@Composable
internal fun EnergyNote(icon: String, title: String, body: String) {
  val c = LocalCc.current
  Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.clip(CcStyle.CardSmall).background(c.surface2).padding(18.dp)) { CcIcon(icon, c.sec, 40.dp) }
    Text(title, color = c.text, fontSize = 30.sp, fontFamily = CcStyle.Serif)
    Text(body, color = c.sec, fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center)
  }
}
