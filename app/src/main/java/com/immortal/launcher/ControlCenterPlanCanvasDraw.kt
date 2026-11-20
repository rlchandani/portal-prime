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
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * Everything one plan frame needs, assembled at composition (strokes, dash effects and colours
 * are created here, never per frame). The draw passes live in this file (floor, openings,
 * pulses, walk, focus) and in ControlCenterPlanCanvasMarks.kt (markers, labels).
 * Which pass belongs to which cached layer is documented on [CcPlanCanvas].
 */
@androidx.compose.runtime.Immutable
internal class CcPlanDraw(
    val model: CcPlanModel,
    val px: CcPlanPx,
    val paint: CcPlanPaint,
    val c: CcColors,
    val labels: Map<String, CcRoomText>,
    val chips: CcMapChipText,
    val lens: CcLens,
    val focus: String?,
    val stale: Boolean,
    val fahrenheit: Boolean,
    val dp: Float,
    /** Rooms whose light a running [CcLightFade] draws instead of the cached floor layer. */
    val skipLit: Set<String> = emptySet(),
    /** Jarvis is pointing at [focus]: its edge pulses like the mock's `.hl.on` instead of breathing. */
    val pulse: Boolean = false,
) {
  /** Per-room light wash (colour, alpha) and glow placements, resolved once per state. */
  private class Lit(val wash: Color, val washAlpha: Float, val centers: List<Offset>, val radii: FloatArray, val tints: List<androidx.compose.ui.graphics.ColorFilter>)

  val lightFactor = if (stale) STALE_FACTOR else 1f
  val arcStroke = Stroke(2.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 6f * dp)))
  val windowOpenDash = PathEffect.dashPathEffect(floatArrayOf(10f * dp, 7f * dp))
  val dotStroke = Stroke(3f * dp)
  val personStroke = Stroke(4f * dp)
  val window = Color(WINDOW_RGB)
  private val lit: Map<String, Lit> = model.views.filter { it.glows.isNotEmpty() }.associate { v ->
    val maxB = v.glows.maxOf { it.strength }
    v.room.id to Lit(Color(OPAQUE or CcOverviewText.mixRgb(v.glows.map { it.rgb })), washAlpha(maxB),
        v.glows.map { g -> px.pt(CcPlanGeo.device(v.room.rect, g.fx, g.fy)) },
        FloatArray(v.glows.size) { CcPlanGeo.glowRadius(v.glows[it].strength) * px.fit.scale },
        v.glows.map { paint.tint(it.rgb) })
  }
  private fun washAlpha(strength: Float): Float =
      ((if (c.dark) WASH_DARK else WASH_LIGHT) + strength * (if (c.dark) WASH_B_DARK else WASH_B_LIGHT)) * lightFactor

  private val glowAlpha = when (lens) { CcLens.CLIMATE -> GLOW_CLIMATE; CcLens.LIVE -> if (c.dark) 1f else GLOW_LIGHT } * lightFactor

  /** Layer 2: the focused room's ember tint, lens tints, light washes and glows. */
  fun drawUnder(s: DrawScope) {
    focus?.let(px.rects::get)?.let { s.drawRect(CcStyle.EmberLight, it.topLeft, it.size, alpha = SEL_MIX) }
    for (v in model.views) px.rects[v.room.id]?.let { roomFill(s, v, it) }
  }

  /** Layer 4: openings over the walls, the focus veil, then badges / dots / chips. */
  fun drawOver(s: DrawScope) {
    drawOverFlat(s)
    drawMarks(s, CcAffine.IDENTITY)
  }

  /** The part of layer 4 that lies on the floor (3D draws it through the projection, the markers upright). */
  fun drawOverFlat(s: DrawScope) {
    for (i in model.openings.indices) drawOpening(s, model.openings[i], px.openings[i])
    focusVeil(s)
  }

  /** Layer 6: names + captions, above everything that moves, placed through [view] (upright in 3D). */
  fun drawTop(s: DrawScope, view: CcAffine = CcAffine.IDENTITY) = drawLabels(s, view)

  /**
   * A running light change at [t] (0..1): the rooms in [CcLightFade.rooms] get their wash and
   * glows here, interpolated, while the cached floor layer leaves them out ([skipLit]). At t = 1
   * this draws exactly what [drawUnder] draws for the new state, so ending the fade doesn't pop.
   */
  fun drawFade(s: DrawScope, fade: CcLightFade, t: Float) {
    for (w in fade.washes) {
      val r = px.rects[w.roomId] ?: continue
      val k = fade.washLocal(w, t)
      val alpha = (if (w.from > 0f) washAlpha(w.from) else 0f) * (1f - k) + (if (w.to > 0f) washAlpha(w.to) else 0f) * k
      if (alpha > 0f) s.drawRect(Color(OPAQUE or (if (w.to > 0f) w.toRgb else w.fromRgb)), r.topLeft, r.size, alpha = alpha)
    }
    for ((id, r) in px.rects) {
      if (id !in fade.rooms) continue
      val view = model.view(id)
      s.clipRect(r.left, r.top, r.right, r.bottom) {
        // Lights that didn't change in a fading room are drawn steady.
        view?.glows?.forEach { g ->
          if (fade.lights.none { it.entityId == g.entityId }) {
            ccGlow(paint, px.pt(CcPlanGeo.device(view.room.rect, g.fx, g.fy)), CcPlanGeo.glowRadius(g.strength) * px.fit.scale,
                glowAlpha, paint.tint(g.rgb))
          }
        }
        for (l in fade.lights) {
          if (l.roomId != id) continue
          val k = fade.local(l, t)
          val on = (if (l.from > 0f) 1f - k else 0f) + (if (l.to > 0f) k else 0f)
          if (on <= 0f) continue
          val strength = l.from + (l.to - l.from) * k
          ccGlow(paint, px.pt(l.x, l.y), CcPlanGeo.glowRadius(strength) * px.fit.scale, glowAlpha * on,
              paint.tint(if (l.to > 0f) l.toRgb else l.fromRgb))
        }
      }
    }
  }

  /**
   * Layer 5 at clock [t]: pulses and the focus edge, placed through [view] in screen space. In 3D
   * a pulse is an axis-aligned oval and the edge four lines — never a circle or a stroked rect
   * under a matrix, which the GPU would mask afresh every frame.
   */
  fun drawAnimated(s: DrawScope, t: Float, view: CcAffine = CcAffine.IDENTITY) {
    drawPulses(s, t, view)
    focusEdge(s, t, view)
  }

  private fun roomFill(s: DrawScope, v: CcRoomView, r: Rect) {
    when (lens) {
      CcLens.CLIMATE -> v.temperature?.let { t ->
        s.drawRect(Color(OPAQUE or CcOverviewText.heatRgb(CcRooms.heat(t, fahrenheit))), r.topLeft, r.size, alpha = if (c.dark) CLIMATE_DARK else CLIMATE_LIGHT)
      }
      CcLens.LIVE -> Unit
    }
    if (v.room.id in skipLit) return
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

  private fun drawPulses(s: DrawScope, t: Float, view: CcAffine) {
    val sx = view.rowX
    val sy = view.rowY
    model.pulses.forEachIndexed { i, p ->
      val k = frac(t * PULSE_CYCLES + i * PULSE_STAGGER)
      val color = when (p.tone) {
        CcMapPulseTone.MOTION, CcMapPulseTone.OCCUPIED -> window
        CcMapPulseTone.OPEN -> c.amber
        CcMapPulseTone.LEAK -> c.red
      }
      val r = p.radius * px.fit.scale * (PULSE_FROM + PULSE_GROW * k)
      val at = view.at(px.pt(p.x, p.y))
      val alpha = PULSE_ALPHA * p.strength * (1f - k)
      if (view.isIdentity) s.drawCircle(color, r, at, alpha = alpha)
      else s.drawOval(color, Offset(at.x - r * sx, at.y - r * sy), androidx.compose.ui.geometry.Size(2f * r * sx, 2f * r * sy), alpha = alpha)
    }
  }

  /** [r]'s outline through [view]: a plain rect flat, four round-capped lines once tilted. */
  private fun edge(s: DrawScope, r: Rect, view: CcAffine, alpha: Float, width: Float) {
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

  /** Visited rooms rest dimmer; the soft light sits on (or glides to) the current room. */
  fun drawWalk(s: DrawScope, rooms: List<String>, centres: List<CcMapPt?>, progress: Float, view: CcAffine = CcAffine.IDENTITY) {
    val i = CcPlanGeo.walkIndex(rooms.size, progress)
    if (i < 0) return
    val done = if (progress >= 1f) rooms.size else i
    // Filled rects under any affine are plain GPU quads (no masks), so the veil can ride the matrix.
    val veil: DrawScope.() -> Unit = { for (k in 0 until done) px.rects[rooms[k]]?.let { drawRect(c.planBg, it.topLeft, it.size, alpha = WALK_VEIL) } }
    if (view.isIdentity) s.veil() else s.withTransform({ transform(view.toMatrix()) }, veil)
    if (progress >= 1f) return
    px.rects[rooms[i]]?.let { edge(s, it, view, 1f, FOCUS_DP * dp) }
    CcPlanGeo.walkPoint(centres, progress)?.let { p ->
      val r = WALK_CELLS * px.fit.scale
      s.ccGlowOval(paint, view.at(px.pt(p)), r * view.rowX, r * view.rowY, 1f, paint.tint(WALK_RGB))
    }
  }

  /** While a room's sheet is open the rest of the floor dims… */
  private fun focusVeil(s: DrawScope) {
    val f = focus ?: return
    for ((id, other) in px.rects) if (id != f) s.drawRect(c.planBg, other.topLeft, other.size, alpha = FOCUS_VEIL)
  }

  /** …and the room gets a breathing ember edge. */
  private fun focusEdge(s: DrawScope, t: Float, view: CcAffine) {
    val r = focus?.let(px.rects::get) ?: return
    val k = frac(t * FOCUS_CYCLES)
    val wave = 1f - kotlin.math.abs(k * 2f - 1f)
    val inset = FOCUS_INSET_CELLS * px.fit.scale
    val inner = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
    if (pulse) {
      // The mock's `hlp`: the ember edge swells to twice its width and softens, then settles.
      val swell = 1f - wave
      edge(s, inner, view, 1f - PULSE_SOFTEN * swell, FOCUS_DP * dp * (1f + swell))
      return
    }
    edge(s, inner, view, FOCUS_ALPHA_MIN + (1f - FOCUS_ALPHA_MIN) * wave, FOCUS_DP * dp)
  }

  /** True when [drawAnimated] draws anything at all (no pulses and no focused room: nothing moves). */
  val animates: Boolean get() = model.pulses.isNotEmpty() || (focus != null && focus in px.rects)

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
    private const val WASH_DARK = 0.16f
    private const val WASH_LIGHT = 0.22f
    private const val WASH_B_DARK = 0.22f
    private const val WASH_B_LIGHT = 0.2f
    private const val GLOW_CLIMATE = 0.5f
    private const val GLOW_LIGHT = 0.9f
    private const val PULSE_CYCLES = 3f
    private const val PULSE_STAGGER = 0.37f
    private const val PULSE_FROM = 0.35f
    private const val PULSE_GROW = 1.25f
    private const val PULSE_ALPHA = 0.55f
    private const val FOCUS_CYCLES = 4f
    private const val FOCUS_ALPHA_MIN = 0.55f
    /** The mock's `hlp` keyframe: opacity .6 at the widest. */
    private const val PULSE_SOFTEN = 0.4f
    private const val FOCUS_INSET_CELLS = 0.2f
    private const val FOCUS_VEIL = 0.6f
    private const val WALK_VEIL = 0.45f
    /** The mock's 150-unit sweep light, in cells. */
    private const val WALK_CELLS = 7.5f
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
