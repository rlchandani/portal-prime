/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Text the plan draws, measured once per state change (never per frame): room names and
 * captions, thermostat / garage chips and the energy-lens node labels.
 */
/** A room's name + caption, measured once per state change. */
internal class CcRoomText(val name: TextLayoutResult, val caption: TextLayoutResult?)

internal fun measureRooms(model: CcPlanModel, lens: CcLens, c: CcColors, m: TextMeasurer, density: Float): Map<String, CcRoomText> {
  // Crisp labels with a soft 1–2 dp lift (the mock's label), not a stroked halo.
  val lift = Shadow(Color.Black.copy(alpha = if (c.dark) SHADOW_DARK else SHADOW_LIGHT), Offset(0f, SHADOW_Y_DP * density), SHADOW_BLUR_DP * density)
  return model.views.associate { v ->
      val nameSize = if (v.room.outdoor) OUTDOOR_NAME_SP else NAME_SP
      val name = m.measure(v.room.name, TextStyle(color = c.text, fontSize = nameSize.sp, fontWeight = FontWeight.SemiBold, shadow = lift))
      val text = CcOverviewText.caption(v, lens)
      val big = lens == CcLens.CLIMATE
      val caption = text.takeIf { it.isNotBlank() }?.let {
        m.measure(it, TextStyle(color = if (big) c.text else c.sec, fontSize = (if (big) NAME_SP else CAPTION_SP).sp,
            fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium, shadow = lift))
      }
      v.room.id to CcRoomText(name, caption)
    }
}

/** Chip and node texts, measured once per state change (looked up by id while drawing). */
internal class CcMapChipText(
    val badge: Map<String, TextLayoutResult>,
    val chip: Map<String, TextLayoutResult>,
    val hub: TextLayoutResult?,
    val hubUnit: TextLayoutResult?,
    val solar: TextLayoutResult?,
    val grid: TextLayoutResult?,
    val battery: TextLayoutResult?,
)

internal fun measureChips(model: CcPlanModel, sources: CcMapSources, lens: CcLens, c: CcColors, m: TextMeasurer): CcMapChipText {
  val bold = TextStyle(fontSize = CHIP_SP.sp, fontWeight = FontWeight.Bold)
  val badge = model.badges.filter { it.kind == CcMapBadgeKind.CLIMATE }.mapNotNull { b -> b.label?.let { b.entityId to m.measure(it, bold.copy(color = c.text)) } }.toMap()
  val chip = model.chips.associate { ch -> ch.entityId to m.measure(ch.text, bold.copy(color = CcStyle.EmberInk, fontWeight = FontWeight.ExtraBold)) }
  if (lens != CcLens.ENERGY) return CcMapChipText(badge, chip, null, null, null, null, null)
  val node = TextStyle(color = c.text, fontSize = NODE_SP.sp, fontWeight = FontWeight.Bold)
  return CcMapChipText(badge, chip,
      hub = sources.homeW?.let { m.measure(CcEnergy.kw(it), TextStyle(color = c.bg, fontSize = HUB_SP.sp, fontWeight = FontWeight.Bold)) },
      hubUnit = m.measure("kW home", TextStyle(color = c.bg, fontSize = HUB_UNIT_SP.sp, fontWeight = FontWeight.SemiBold)),
      solar = sources.solarW?.let { m.measure("Solar ${CcEnergy.kw(it)} kW", node) },
      grid = sources.gridW?.let { m.measure("Grid ${CcEnergy.kw(kotlin.math.abs(it))} kW ${if (it >= 0) "in" else "out"}", node) },
      battery = sources.batteryW?.let { w -> m.measure("Battery${sources.batteryPct?.let { " $it%" } ?: ""} · ${CcEnergy.kw(kotlin.math.abs(w))} kW", node) })
}

private const val NAME_SP = 22f
private const val OUTDOOR_NAME_SP = 18f
private const val CAPTION_SP = 17f
private const val CHIP_SP = 19f
private const val HUB_SP = 22f
private const val NODE_SP = 18f
private const val HUB_UNIT_SP = 15f
private const val SHADOW_DARK = 0.55f
private const val SHADOW_LIGHT = 0.18f
private const val SHADOW_Y_DP = 1f
private const val SHADOW_BLUR_DP = 2f
