/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The room highlight (an open room sheet, or Jarvis pointing): the ember tint under the room, the
 * veil over the other rooms, the breathing (or pulsing) edge and the faded labels of the others.
 *
 * Kept apart from [CcPlanDraw] so a highlight change rebuilds only this small object: the plan's
 * own passes (floors, washes, glows, walls, openings, markers) keep their draw object and their
 * recorded display lists, and only the highlight's own layers (and the labels) are drawn again.
 * Each draw here is exactly what the single draw object drew before, in the same place in the
 * layer order (see [CcPlanCanvas]).
 */
@androidx.compose.runtime.Immutable
internal class CcPlanFocus(
    private val rects: Map<String, Rect>,
    private val scale: Float,
    private val c: CcColors,
    val focus: String?,
    /** Jarvis is pointing at [focus]: its edge pulses like the mock's `.hl.on` instead of breathing. */
    val pulse: Boolean,
    private val dp: Float,
) {
  constructor(px: CcPlanPx, c: CcColors, focus: String?, pulse: Boolean, dp: Float) :
      this(px.rects, px.fit.scale, c, focus, pulse, dp)

  /** True when the focused room is on this floor (so the edge animates). */
  val shows: Boolean get() = focus != null && focus in rects

  /** Rooms the veil dims while one is focused: every other room on the floor. */
  fun veiled(roomId: String): Boolean = focus != null && roomId != focus

  /** Under the lens tints: the focused room's ember tint. */
  fun drawTint(s: DrawScope) {
    focus?.let(rects::get)?.let { s.drawRect(CcStyle.EmberLight, it.topLeft, it.size, alpha = SEL_MIX) }
  }

  /** Over the openings, under the markers: the rest of the floor dims… */
  fun drawVeil(s: DrawScope) {
    if (focus == null) return
    for ((id, other) in rects) if (veiled(id)) s.drawRect(c.planBg, other.topLeft, other.size, alpha = FOCUS_VEIL)
  }

  /** …and the room gets a breathing ember edge at clock [t], placed through [view]. */
  fun drawEdge(s: DrawScope, t: Float, view: CcAffine = CcAffine.IDENTITY) {
    val r = focus?.let(rects::get) ?: return
    val e = edgeAt(t)
    ccPlanEdge(s, inner(r), view, e.alpha, FOCUS_DP * dp * e.widthScale)
  }

  /** The edge sits inset inside the room's rect. */
  fun inner(r: Rect): Rect {
    val inset = FOCUS_INSET_CELLS * scale
    return Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
  }

  /** The edge's alpha and width (× [FOCUS_DP]) at clock [t]: breathing, or the mock's `hlp` swell when [pulse]. */
  fun edgeAt(t: Float): CcEdgeLook {
    val k = frac(t * FOCUS_CYCLES)
    val wave = 1f - kotlin.math.abs(k * 2f - 1f)
    if (pulse) {
      // The mock's `hlp`: the ember edge swells to twice its width and softens, then settles.
      val swell = 1f - wave
      return CcEdgeLook(1f - PULSE_SOFTEN * swell, 1f + swell)
    }
    return CcEdgeLook(FOCUS_ALPHA_MIN + (1f - FOCUS_ALPHA_MIN) * wave, 1f)
  }

  private fun frac(v: Float): Float = v - kotlin.math.floor(v)

  companion object {
    /** The mock's highlight stroke (dp); the scene walk's current room uses it too. */
    const val FOCUS_DP = 6f
    private const val SEL_MIX = 0.2f
    private const val FOCUS_VEIL = 0.6f
    private const val FOCUS_CYCLES = 4f
    private const val FOCUS_ALPHA_MIN = 0.55f
    /** The mock's `hlp` keyframe: opacity .6 at the widest. */
    private const val PULSE_SOFTEN = 0.4f
    private const val FOCUS_INSET_CELLS = 0.2f
  }
}

/** One frame of the highlight edge: its alpha and its width as a multiple of [CcPlanFocus.FOCUS_DP]. */
internal data class CcEdgeLook(val alpha: Float, val widthScale: Float)

/** [r]'s outline through [view]: a plain rect flat, four round-capped lines once tilted. */
internal fun ccPlanEdge(s: DrawScope, r: Rect, view: CcAffine, alpha: Float, width: Float) {
  if (view.isIdentity) {
    s.drawRect(CcStyle.EmberLight, r.topLeft, r.size, alpha = alpha, style = Stroke(width, join = StrokeJoin.Round))
    return
  }
  val a = view.at(r.topLeft)
  val b = view.at(r.topRight)
  val c2 = view.at(r.bottomRight)
  val d = view.at(r.bottomLeft)
  s.drawLine(CcStyle.EmberLight, a, b, width, StrokeCap.Round, alpha = alpha)
  s.drawLine(CcStyle.EmberLight, b, c2, width, StrokeCap.Round, alpha = alpha)
  s.drawLine(CcStyle.EmberLight, c2, d, width, StrokeCap.Round, alpha = alpha)
  s.drawLine(CcStyle.EmberLight, d, a, width, StrokeCap.Round, alpha = alpha)
}
