/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Plan text on the platform canvas: a handful of cached [Paint]s (typeface, size, the soft
 * 1 dp shadow) and plain strings with their measured widths. No TextLayoutResult / StaticLayout,
 * so a lens or floor change costs a few `measureText` calls instead of ~1 ms per label.
 */
internal class CcPlanTextPaints(c: CcColors, d: Density) {
  private val shadow = Color.Black.copy(alpha = if (c.dark) SHADOW_DARK else SHADOW_LIGHT).toArgb()
  private val lift = with(d) { SHADOW_Y_DP.dp.toPx() } to with(d) { SHADOW_BLUR_DP.dp.toPx() }
  val name = paint(d, NAME_SP, c.text, W_SEMI, shadow = true)
  val outdoorName = paint(d, OUTDOOR_NAME_SP, c.text, W_SEMI, shadow = true)
  val caption = paint(d, CAPTION_SP, c.sec, W_MEDIUM, shadow = true)
  val bigCaption = paint(d, NAME_SP, c.text, W_SEMI, shadow = true)
  val chip = paint(d, CHIP_SP, CcStyle.EmberInk, W_EXTRA)
  val badge = paint(d, CHIP_SP, c.text, W_BOLD)
  val node = paint(d, NODE_SP, c.text, W_BOLD)
  val hub = paint(d, HUB_SP, c.bg, W_BOLD)
  val hubUnit = paint(d, HUB_UNIT_SP, c.bg, W_SEMI)

  private fun paint(d: Density, sp: Float, color: Color, weight: Int, shadow: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = with(d) { sp.sp.toPx() }
    this.color = color.toArgb()
    typeface = weight(weight)
    if (shadow) setShadowLayer(lift.second, 0f, lift.first, this@CcPlanTextPaints.shadow)
  }

  private fun weight(w: Int): Typeface =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, w, false)
      else if (w >= W_BOLD) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)

  private companion object {
    const val W_MEDIUM = 500
    const val W_SEMI = 600
    const val W_BOLD = 700
    const val W_EXTRA = 800
    const val NAME_SP = 22f
    const val OUTDOOR_NAME_SP = 18f
    const val CAPTION_SP = 17f
    const val CHIP_SP = 19f
    const val HUB_SP = 22f
    const val NODE_SP = 18f
    const val HUB_UNIT_SP = 15f
    const val SHADOW_DARK = 0.55f
    const val SHADOW_LIGHT = 0.18f
    const val SHADOW_Y_DP = 1f
    const val SHADOW_BLUR_DP = 2f
  }
}

/** A string drawn with [paint]; width and line metrics measured once. */
internal class CcPlanLabel(val text: String, val paint: Paint) {
  val width: Float = paint.measureText(text)
  private val ascent: Float = paint.ascent()
  val height: Float = paint.descent() - ascent

  /** Draws with the label's top-left at ([left], [top]). Mutates only the paint's alpha, then restores it. */
  fun draw(s: DrawScope, left: Float, top: Float, alpha: Float = 1f) {
    s.drawIntoCanvas { canvas ->
      val a = paint.alpha
      if (alpha < 1f) paint.alpha = (a * alpha).toInt()
      canvas.nativeCanvas.drawText(text, left, top - ascent, paint)
      paint.alpha = a
    }
  }
}

internal class CcRoomText(val name: CcPlanLabel, val caption: CcPlanLabel?)

internal fun roomTexts(model: CcPlanModel, lens: CcLens, p: CcPlanTextPaints): Map<String, CcRoomText> =
    model.views.associate { v ->
      val caption = CcOverviewText.caption(v, lens).takeIf { it.isNotBlank() }
          ?.let { CcPlanLabel(it, if (lens == CcLens.CLIMATE) p.bigCaption else p.caption) }
      v.room.id to CcRoomText(CcPlanLabel(v.room.name, if (v.room.outdoor) p.outdoorName else p.name), caption)
    }

/** Chip and node texts (looked up by id while drawing). */
internal class CcMapChipText(
    val badge: Map<String, CcPlanLabel>,
    val chip: Map<String, CcPlanLabel>,
    val hub: CcPlanLabel?,
    val hubUnit: CcPlanLabel?,
    val solar: CcPlanLabel?,
    val grid: CcPlanLabel?,
    val battery: CcPlanLabel?,
)

internal fun chipTexts(model: CcPlanModel, sources: CcMapSources, lens: CcLens, p: CcPlanTextPaints): CcMapChipText {
  val badge = model.badges.filter { it.kind == CcMapBadgeKind.CLIMATE }.mapNotNull { b -> b.label?.let { b.entityId to CcPlanLabel(it, p.badge) } }.toMap()
  val chip = model.chips.associate { ch -> ch.entityId to CcPlanLabel(ch.text, p.chip) }
  if (lens != CcLens.ENERGY) return CcMapChipText(badge, chip, null, null, null, null, null)
  return CcMapChipText(badge, chip,
      hub = sources.homeW?.let { CcPlanLabel(CcEnergy.kw(it), p.hub) },
      hubUnit = CcPlanLabel("kW home", p.hubUnit),
      solar = sources.solarW?.let { CcPlanLabel("Solar ${CcEnergy.kw(it)} kW", p.node) },
      grid = sources.gridW?.let { CcPlanLabel("Grid ${CcEnergy.kw(kotlin.math.abs(it))} kW ${if (it >= 0) "in" else "out"}", p.node) },
      battery = sources.batteryW?.let { w -> CcPlanLabel("Battery${sources.batteryPct?.let { " $it%" } ?: ""} · ${CcEnergy.kw(kotlin.math.abs(w))} kW", p.node) })
}
