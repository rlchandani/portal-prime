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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The plan card's chrome (mock `.planTop` / `.planBot`): floor and lens segmented controls,
 * the per-lens legend pill, the "Live from Home Assistant" freshness pill and the stale bar.
 */
@Composable
internal fun CcPlanTopRow(floors: CcFloorTabs, floorId: String, lens: CcLens, onFloor: (String) -> Unit, onLens: (CcLens) -> Unit,
                          modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
    if (floors.tabs.size > 1) {
      val options = remember(floors) { floors.tabs.map { Triple(it.id, it.label, null as String?) } }
      CcSeg(options, floorId, onFloor)
    }
    CcSeg(LENSES, lens, onLens)
  }
}

@Composable
internal fun CcPlanLegend(legend: CcLegend, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  LegendPill(modifier) {
    when (legend.lens) {
      CcLens.LIVE -> {
        LegendDot(Color(OPAQUE or CcOverviewText.WARM_WASH), "Lights on: ${legend.lightsOn}")
        LegendDot(c.amber, "Open")
        LegendDot(Color(CcPlanDraw.WINDOW_RGB), "Motion")
        LegendDot(c.green, "Locked")
      }
      CcLens.CLIMATE -> {
        LegendText(legend.cold)
        Box(Modifier.size(180.dp, 14.dp).clip(CcStyle.Pill).background(HEAT_BRUSH))
        LegendText(legend.warm)
        legend.climate?.let {
          Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CcIcon("heat", c.sec, 22.dp)
            LegendText(it)
          }
        }
      }
      CcLens.ENERGY -> {
        LegendDot(Color(OPAQUE or CcFlowPx.SOLAR_RGB), "Solar")
        LegendDot(Color(OPAQUE or CcFlowPx.BATTERY_RGB), "Battery")
        LegendDot(Color(OPAQUE or CcFlowPx.GRID_RGB), "Grid")
        LegendDot(Color(OPAQUE or CcFlowPx.ROOM_RGB), "Rooms")
      }
    }
  }
}

/** "Live from Home Assistant" (green dot), or how old the map is when it isn't live. */
@Composable
internal fun CcFreshnessPill(text: String, live: Boolean, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  LegendPill(modifier) {
    if (live) {
      Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.green.copy(alpha = DOT_HALO)))
        Box(Modifier.size(12.dp).clip(CircleShape).background(c.green))
      }
    } else CcIcon("clock", c.text, 24.dp)
    Text(text, color = c.text, fontSize = 18.sp, maxLines = 1)
  }
}

/** Mock `.staleBar`: live updates paused, how old the data is, and Retry. */
@Composable
internal fun CcStaleBar(text: String, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val context = LocalContext.current
  val retry = remember(context) { { ControlCenter.retryNow(context) } }
  Row(modifier.clip(CcStyle.Pill).background(c.amberBg).padding(start = 26.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CcIcon("clock", c.amber, 26.dp)
    Text(text, color = c.amber, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    CcButton("Retry", retry, height = 64)
  }
}

@Composable
private fun LegendPill(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
  val c = LocalCc.current
  Row(modifier.clip(LEGEND).background(c.glass).padding(horizontal = 20.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

@Composable
private fun LegendDot(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.size(16.dp).clip(CircleShape).background(color))
    LegendText(label)
  }
}

@Composable
private fun LegendText(text: String) {
  Text(text, color = LocalCc.current.sec, fontSize = 18.sp, maxLines = 1)
}

private val LENSES = listOf(Triple(CcLens.LIVE, "Live", "bulb"), Triple(CcLens.CLIMATE, "Climate", "therm"), Triple(CcLens.ENERGY, "Energy", "bolt"))
private val LEGEND = RoundedCornerShape(24.dp)
private val HEAT_BRUSH = Brush.horizontalGradient(listOf(Color(0xFF5AA9FF), Color(0xFF61D0A6), Color(0xFFF4D36B), Color(0xFFF08A4B)))
private const val OPAQUE = 0xFF000000.toInt()
private const val DOT_HALO = 0.22f
