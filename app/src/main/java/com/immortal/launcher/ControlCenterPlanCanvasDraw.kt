/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * Everything one plan frame needs, assembled at composition (strokes, dash effects and colours
 * are created here, never per frame). The draw passes live in this file (floor, openings,
 * pulses, walk, flows, focus) and in ControlCenterPlanCanvasMarks.kt (markers, labels, nodes).
 * Which pass belongs to which cached layer is documented on [CcPlanCanvas].
 */
@androidx.compose.runtime.Immutable
internal class CcPlanDraw(
    val model: CcPlanModel,
    val px: CcPlanPx,
    val flows: CcFlowPx?,
    val paint: CcPlanPaint,
    val c: CcColors,
    val labels: Map<String, CcRoomText>,
    val chips: CcMapChipText,
    val lens: CcLens,
    val focus: String?,
    val stale: Boolean,
    val dashes: CcMapDashes,
    val sources: CcMapSources,
    val fahrenheit: Boolean,
    val dp: Float,
) {
  /** Per-room light wash (colour, alpha) and glow placements, resolved once per state. */
  private class Lit(val wash: Color, val washAlpha: Float, val centers: List<Offset>, val radii: FloatArray, val tints: List<androidx.compose.ui.graphics.ColorFilter>)

  val lightFactor = if (stale) STALE_FACTOR else 1f
  val arcStroke = Stroke(2.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 6f * dp)))
  val windowOpenDash = PathEffect.dashPathEffect(floatArrayOf(10f * dp, 7f * dp))
  val dotStroke = Stroke(3f * dp)
  val focusStroke = Stroke(FOCUS_DP * dp, join = StrokeJoin.Round)
  val nodeStroke = Stroke(3f * dp)
  val window = Color(WINDOW_RGB)
  val flowBase = flows?.lines?.map { Stroke(it.widthDp * dp, cap = StrokeCap.Round) }.orEmpty()
  val flowMoving = flows?.lines?.map { l -> dashes.effects.map { Stroke(l.widthDp * dp, cap = StrokeCap.Round, pathEffect = it) } }.orEmpty()
  private val lit: Map<String, Lit> = model.views.filter { it.glows.isNotEmpty() }.associate { v ->
    val maxB = v.glows.maxOf { it.strength }
    val wash = (if (lens == CcLens.ENERGY) WASH_ENERGY else if (c.dark) WASH_DARK else WASH_LIGHT) + maxB * (if (c.dark) WASH_B_DARK else WASH_B_LIGHT)
    v.room.id to Lit(Color(OPAQUE or CcOverviewText.mixRgb(v.glows.map { it.rgb })), wash * lightFactor,
        v.glows.map { g -> px.pt(CcPlanGeo.device(v.room.rect, g.fx, g.fy)) },
        FloatArray(v.glows.size) { CcPlanGeo.glowRadius(v.glows[it].strength) * px.fit.scale },
        v.glows.map { paint.tint(it.rgb) })
  }
  private val glowAlpha = when (lens) { CcLens.ENERGY -> GLOW_ENERGY; CcLens.CLIMATE -> GLOW_CLIMATE; CcLens.LIVE -> if (c.dark) 1f else GLOW_LIGHT } * lightFactor

  /** Layer 2: the focused room's ember tint, lens tints, light washes and glows. */
  fun drawUnder(s: DrawScope) {
    focus?.let(px.rects::get)?.let { s.drawRect(CcStyle.EmberLight, it.topLeft, it.size, alpha = SEL_MIX) }
    for (v in model.views) px.rects[v.room.id]?.let { roomFill(s, v, it) }
  }

  /** Layer 4: openings over the walls, the focus veil, then badges / dots / chips. */
  fun drawOver(s: DrawScope) {
    for (i in model.openings.indices) drawOpening(s, model.openings[i], px.openings[i])
    focusVeil(s)
    drawMarks(s)
  }

  /** Layer 6: names + captions and the energy nodes, above everything that moves. */
  fun drawTop(s: DrawScope) {
    drawLabels(s)
    drawEnergyNodes(s)
  }

  /** Layer 5 at clock [t]: pulses, energy dashes and the focus edge. */
  fun drawAnimated(s: DrawScope, t: Float) {
    drawPulses(s, t)
    drawFlows(s, t)
    focusEdge(s, t)
  }

  private fun roomFill(s: DrawScope, v: CcRoomView, r: Rect) {
    when (lens) {
      CcLens.CLIMATE -> v.temperature?.let { t ->
        s.drawRect(Color(OPAQUE or CcOverviewText.heatRgb(CcRooms.heat(t, fahrenheit))), r.topLeft, r.size, alpha = if (c.dark) CLIMATE_DARK else CLIMATE_LIGHT)
      }
      CcLens.ENERGY -> v.powerW?.takeIf { it > 0 && model.maxPowerW > 0 }?.let { w ->
        s.drawRect(Color(OPAQUE or CcFlowPx.ROOM_RGB), r.topLeft, r.size, alpha = ENERGY_BASE + ENERGY_RANGE * (w / model.maxPowerW).toFloat())
      }
      CcLens.LIVE -> Unit
    }
    val l = lit[v.room.id] ?: return
    s.drawRect(l.wash, r.topLeft, r.size, alpha = l.washAlpha)
    s.clipRect(r.left, r.top, r.right, r.bottom) {
      for (k in l.centers.indices) ccGlow(paint, l.centers[k], l.radii[k], glowAlpha, l.tints[k])
    }
  }

  private fun drawOpening(s: DrawScope, o: CcOpeningView, g: CcPlanPx.OpeningPx) {
    s.drawLine(c.floor, g.a, g.b, GAP_DP * dp)
    when (o.kind) {
      CcMapOpeningKind.WINDOW -> if (o.open) s.drawLine(c.amber, g.a, g.b, WINDOW_DP * dp, StrokeCap.Round, windowOpenDash)
      else s.drawLine(window, g.a, g.b, WINDOW_DP * dp, StrokeCap.Round)
      CcMapOpeningKind.DOOR -> if (o.open) {
        s.drawPath(g.arc, c.amber, alpha = ARC_ALPHA, style = arcStroke)
        s.drawLine(c.amber, g.a, g.swing, DOOR_DP * dp, StrokeCap.Round)
      } else s.drawLine(c.wall, g.a, g.b, DOOR_DP * dp, StrokeCap.Round)
      CcMapOpeningKind.GARAGE -> {
        s.drawLine(c.wall, g.a, g.b, GARAGE_DP * dp, StrokeCap.Round)
        if (o.progress > 0f) s.drawLine(c.amber, g.a, Offset(g.a.x + (g.b.x - g.a.x) * o.progress, g.a.y + (g.b.y - g.a.y) * o.progress),
            GARAGE_DP * dp, StrokeCap.Round)
      }
    }
  }

  private fun drawPulses(s: DrawScope, t: Float) {
    model.pulses.forEachIndexed { i, p ->
      val k = frac(t * PULSE_CYCLES + i * PULSE_STAGGER)
      val color = when (p.tone) { CcMapPulseTone.MOTION -> window; CcMapPulseTone.OPEN -> c.amber; CcMapPulseTone.LEAK -> c.red }
      s.drawCircle(color, p.radius * px.fit.scale * (PULSE_FROM + PULSE_GROW * k), px.pt(p.x, p.y), alpha = PULSE_ALPHA * (1f - k))
    }
  }

  /** Visited rooms rest dimmer; the soft light sits on (or glides to) the current room. */
  fun drawWalk(s: DrawScope, rooms: List<String>, centres: List<CcMapPt?>, progress: Float) {
    val i = CcPlanGeo.walkIndex(rooms.size, progress)
    if (i < 0) return
    val done = if (progress >= 1f) rooms.size else i
    for (k in 0 until done) px.rects[rooms[k]]?.let { s.drawRect(c.planBg, it.topLeft, it.size, alpha = WALK_VEIL) }
    if (progress >= 1f) return
    px.rects[rooms[i]]?.let { s.drawRect(CcStyle.EmberLight, it.topLeft, it.size, style = focusStroke) }
    CcPlanGeo.walkPoint(centres, progress)?.let { p ->
      s.ccGlow(paint, px.pt(p), WALK_CELLS * px.fit.scale, 1f, paint.tint(WALK_RGB))
    }
  }

  private fun drawFlows(s: DrawScope, t: Float) {
    val f = flows ?: return
    val phase = frac(t * DASH_CYCLES)
    f.lines.forEachIndexed { i, l ->
      val col = Color(OPAQUE or l.rgb)
      s.drawPath(l.path, col, alpha = FLOW_BASE_ALPHA, style = flowBase[i])
      val p = if (l.reverse) 1f - phase else phase
      s.drawPath(l.path, col, style = flowMoving[i][((p * CcMapDashes.STEPS).toInt()).coerceIn(0, CcMapDashes.STEPS - 1)])
    }
  }

  /** While a room's sheet is open the rest of the floor dims… */
  private fun focusVeil(s: DrawScope) {
    val f = focus ?: return
    for ((id, other) in px.rects) if (id != f) s.drawRect(c.planBg, other.topLeft, other.size, alpha = FOCUS_VEIL)
  }

  /** …and the room gets a breathing ember edge. */
  private fun focusEdge(s: DrawScope, t: Float) {
    val r = focus?.let(px.rects::get) ?: return
    val k = frac(t * FOCUS_CYCLES)
    val breathe = FOCUS_ALPHA_MIN + (1f - FOCUS_ALPHA_MIN) * (1f - kotlin.math.abs(k * 2f - 1f))
    val inset = FOCUS_INSET_CELLS * px.fit.scale
    s.drawRect(CcStyle.EmberLight, Offset(r.left + inset, r.top + inset), androidx.compose.ui.geometry.Size(r.width - inset * 2, r.height - inset * 2),
        alpha = breathe, style = focusStroke)
  }

  private fun frac(v: Float): Float = v - kotlin.math.floor(v)

  companion object {
    private const val OPAQUE = 0xFF000000.toInt()
    const val WINDOW_RGB = 0xFF7FB2E5
    private const val WALK_RGB = 0xFFF1D6
    /** The mock's 5-unit `.wl` stroke. */
    const val WALL_DP = 5f
    private const val GAP_DP = 9f
    private const val WINDOW_DP = 6f
    private const val DOOR_DP = 7f
    private const val GARAGE_DP = 12f
    private const val FOCUS_DP = 6f
    private const val ARC_ALPHA = 0.8f
    private const val STALE_FACTOR = 0.45f
    private const val SEL_MIX = 0.2f
    private const val CLIMATE_DARK = 0.5f
    private const val CLIMATE_LIGHT = 0.55f
    private const val ENERGY_BASE = 0.06f
    private const val ENERGY_RANGE = 0.3f
    private const val WASH_ENERGY = 0.06f
    private const val WASH_DARK = 0.16f
    private const val WASH_LIGHT = 0.22f
    private const val WASH_B_DARK = 0.22f
    private const val WASH_B_LIGHT = 0.2f
    private const val GLOW_ENERGY = 0.25f
    private const val GLOW_CLIMATE = 0.5f
    private const val GLOW_LIGHT = 0.9f
    private const val PULSE_CYCLES = 3f
    private const val PULSE_STAGGER = 0.37f
    private const val PULSE_FROM = 0.35f
    private const val PULSE_GROW = 1.25f
    private const val PULSE_ALPHA = 0.55f
    private const val DASH_CYCLES = 6f
    private const val FOCUS_CYCLES = 4f
    private const val FOCUS_ALPHA_MIN = 0.55f
    private const val FOCUS_INSET_CELLS = 0.2f
    private const val FOCUS_VEIL = 0.6f
    private const val WALK_VEIL = 0.45f
    /** The mock's 150-unit sweep light, in cells. */
    private const val WALK_CELLS = 7.5f
    private const val FLOW_BASE_ALPHA = 0.18f
  }
}

/** The static layers (1 floors + slabs, 3 walls): depend only on floor geometry, size and theme. */
internal class CcPlanBase(private val px: CcPlanPx, private val c: CcColors, dp: Float) {
  private val wallStroke = Stroke(CcPlanDraw.WALL_DP * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
  private val outdoorStroke = Stroke(OUTDOOR_DP * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(OUTDOOR_DASH * dp, OUTDOOR_GAP * dp)))

  fun drawFloors(s: DrawScope) {
    for (i in px.outdoorPaths.indices) {
      s.drawPath(px.outdoorPaths[i], c.floor2)
      s.drawPath(px.outdoorPaths[i], c.div, style = outdoorStroke)
    }
    for ((_, r) in px.indoor) s.drawRect(c.floor, r.topLeft, r.size)
  }

  fun drawWalls(s: DrawScope) = s.drawPath(px.walls, c.wall, style = wallStroke)

  private companion object {
    const val OUTDOOR_DP = 2f
    const val OUTDOOR_DASH = 6f
    const val OUTDOOR_GAP = 8f
  }
}
