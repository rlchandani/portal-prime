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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MIN_BAR = 0.02f
/** The tallest bar leaves room above it for its kWh label. */
private const val BAR_ROOM = 0.85f

/** The home charger now, plus this week's charging per day from HA's recorder. */
@Composable
internal fun CcChargerCard(charger: CcChargerView?, week: CcChargeWeek?, hasCharger: Boolean, modifier: Modifier) {
  val c = LocalCc.current
  Column(modifier.ccCard().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      CcSectionLabel(charger?.source ?: "Charging", Modifier.weight(1f))
      charger?.status?.let { CcTag(it, if (charger.delivering) CcTone.GREEN else CcTone.NEUTRAL) }
    }
    if (charger != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Metric(charger.powerKw?.let { "${CcTeslaText.kw(it)} kW" } ?: "—", "power now")
      Metric(charger.sessionKwh?.let { "${CcTeslaText.kwh(it)} kWh" } ?: "—", "this session")
      Metric(week?.let { "${CcTeslaText.kwh(it.today)} kWh" } ?: "—", week?.todayCost?.let { "today · ${CcTeslaText.money(it)}" } ?: "today")
    } else if (!hasCharger) Text("No home charger in Home Assistant. Add the Tesla Wall Connector integration to see its power here; " +
        "the chart below shows what the car added.", color = c.sec, fontSize = 19.sp, lineHeight = 26.sp)
    WeekHeader(week)
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      if (week == null) Text("Reading this week from Home Assistant…", color = c.sec, fontSize = 19.sp)
      else WeekBars(week)
    }
  }
}

@Composable
private fun RowScope.Metric(value: String, label: String) {
  val c = LocalCc.current
  Column(Modifier.weight(1f).clip(CELL).background(c.surface2).padding(horizontal = 18.dp, vertical = 14.dp)) {
    Text(value, color = c.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text(label, color = c.sec, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun WeekHeader(week: CcChargeWeek?) {
  val c = LocalCc.current
  Row(verticalAlignment = Alignment.Bottom) {
    Text("This week", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    week?.let { w ->
      Text("${CcTeslaText.kwh(w.total)} kWh" + (w.weekCost?.let { " · ${CcTeslaText.money(it)}" } ?: ""), color = c.sec, fontSize = 21.sp)
    }
  }
}

/** Seven day bars (today in green), kWh above, day below. Static: no canvas, no animation. */
@Composable
private fun WeekBars(week: CcChargeWeek) {
  val c = LocalCc.current
  val zone = remember { java.util.TimeZone.getDefault() }
  val max = (week.kwh.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
  Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    week.kwh.forEachIndexed { i, kwh ->
      val today = i == week.kwh.lastIndex
      Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (kwh > 0) CcTeslaText.kwh(kwh) else "", color = c.sec, fontSize = 17.sp, maxLines = 1)
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().fillMaxHeight((kwh / max).toFloat().coerceIn(MIN_BAR, 1f) * BAR_ROOM)
                .clip(BAR).background(if (today) c.green else c.surface2))
          }
        }
        Text(CcTeslaText.day(week.starts[i], zone), color = if (today) c.text else c.sec, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
      }
    }
  }
}

/** Locked, cabin, sentry, tyres, openings, where it is, software and odometer — read-only for everyone. */
@Composable
internal fun CcCarStatusCard(car: CcCarView, modifier: Modifier) {
  val rows = remember(car) { CcTeslaStatus.rows(car) }
  Column(modifier.ccCard().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    CcSectionLabel("Car status")
    rows.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        pair.forEach { StatusCell(it) }
        if (pair.size == 1) Box(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun RowScope.StatusCell(r: CcStatusRow) {
  val c = LocalCc.current
  val tint = when (r.tone) { CcTone.GREEN -> c.green; CcTone.AMBER -> c.amber; CcTone.RED -> c.red; CcTone.NEUTRAL -> c.sec }
  Row(Modifier.weight(1f).height(84.dp).clip(CELL).background(c.surface2).padding(horizontal = 18.dp).ccSpeak("${r.label}: ${r.value}"),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    CcIcon(r.icon, tint, 30.dp)
    Column(Modifier.weight(1f)) {
      Text(r.value, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(r.label, color = c.sec, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

private val CELL = RoundedCornerShape(20.dp)
private val BAR = RoundedCornerShape(12.dp)
