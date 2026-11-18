/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure plan geometry for the overview map (unit tested): grid cells → pixels, the wall segment
 * of an opening, hit-testing, glow sizes and the scene walk. Everything is in grid cells
 * ([CcPlan.GRID_W] × [CcPlan.GRID_H]) until [CcPlanFit] maps it to the canvas.
 */
internal data class CcMapPt(val x: Float, val y: Float)

internal data class CcMapLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
  val mid: CcMapPt get() = CcMapPt((x1 + x2) / 2f, (y1 + y2) / 2f)
  val length: Float get() = kotlin.math.hypot(x2 - x1, y2 - y1)
}

internal data class CcMapBox(val l: Float, val t: Float, val r: Float, val b: Float) {
  val w: Float get() = r - l
  val h: Float get() = b - t
  val center: CcMapPt get() = CcMapPt((l + r) / 2f, (t + b) / 2f)
}

/** Cells → px: `px = d + cell × scale`, the plan centred in the canvas. */
internal data class CcPlanFit(val scale: Float, val dx: Float, val dy: Float) {
  fun x(cell: Float): Float = dx + cell * scale
  fun y(cell: Float): Float = dy + cell * scale
  fun cellX(px: Float): Float = (px - dx) / scale
  fun cellY(py: Float): Float = (py - dy) / scale
}

internal object CcPlanGeo {
  /** Breathing room around the rooms (the mock's 26-unit viewBox margin ≈ 1.3 cells). */
  const val MARGIN_CELLS = 1.3f
  /** A light's glow radius at full brightness: the mock's 230 units on its 20-unit grid. */
  const val GLOW_CELLS = 11.5f
  private const val GLOW_MIN = 0.35f
  private const val GLOW_RANGE = 0.75f
  /** How far an open door swings (rad), as drawn in the mock. */
  const val DOOR_SWING_RAD = 1.3f
  /** Chips sit this far inside the wall they belong to. */
  const val CHIP_INSET_CELLS = 1.5f
  /** The walk light glides to the next room during the last part of each room's slot. */
  const val WALK_MOVE_FROM = 0.7f

  /** Union of the floor's rooms; the whole grid when the floor has none. */
  fun bounds(floor: CcFloor?): CcMapBox {
    val rooms = floor?.rooms.orEmpty()
    if (rooms.isEmpty()) return CcMapBox(0f, 0f, CcPlan.GRID_W.toFloat(), CcPlan.GRID_H.toFloat())
    return CcMapBox(rooms.minOf { it.rect.x }.toFloat(), rooms.minOf { it.rect.y }.toFloat(),
        rooms.maxOf { it.rect.right }.toFloat(), rooms.maxOf { it.rect.bottom }.toFloat())
  }

  /** Fits [box] (+ [margin] cells each side) into [w]×[h] px keeping aspect, centred, at most [maxScale] px/cell. */
  fun fit(box: CcMapBox, w: Float, h: Float, margin: Float = MARGIN_CELLS, maxScale: Float = Float.MAX_VALUE): CcPlanFit {
    val bw = box.w + margin * 2f
    val bh = box.h + margin * 2f
    if (w <= 0f || h <= 0f || bw <= 0f || bh <= 0f) return CcPlanFit(1f, 0f, 0f)
    val scale = min(min(w / bw, h / bh), maxScale)
    val c = box.center
    return CcPlanFit(scale, w / 2f - c.x * scale, h / 2f - c.y * scale)
  }

  /** The stretch of [rect]'s [side] wall an opening covers ([at] = centre, [span] = share of the wall). */
  fun opening(rect: CcRect, side: CcSide, at: Float, span: Float): CcMapLine {
    val horizontal = side == CcSide.N || side == CcSide.S
    val wall = (if (horizontal) rect.w else rect.h).toFloat()
    val half = (span.coerceIn(0f, 1f) * wall) / 2f
    val centre = at.coerceIn(0f, 1f) * wall
    val a = (centre - half).coerceAtLeast(0f)
    val b = (centre + half).coerceAtMost(wall)
    return when (side) {
      CcSide.N -> CcMapLine(rect.x + a, rect.y.toFloat(), rect.x + b, rect.y.toFloat())
      CcSide.S -> CcMapLine(rect.x + a, rect.bottom.toFloat(), rect.x + b, rect.bottom.toFloat())
      CcSide.W -> CcMapLine(rect.x.toFloat(), rect.y + a, rect.x.toFloat(), rect.y + b)
      CcSide.E -> CcMapLine(rect.right.toFloat(), rect.y + a, rect.right.toFloat(), rect.y + b)
    }
  }

  /** Unit vector pointing from the wall into the room. */
  fun inward(side: CcSide): CcMapPt = when (side) {
    CcSide.N -> CcMapPt(0f, 1f)
    CcSide.S -> CcMapPt(0f, -1f)
    CcSide.W -> CcMapPt(1f, 0f)
    CcSide.E -> CcMapPt(-1f, 0f)
  }

  /** Where an open door's leaf ends: hinged at the line's start, swung [DOOR_SWING_RAD] into the room. */
  fun doorSwing(line: CcMapLine, side: CcSide): CcMapPt {
    val len = line.length
    val ux = (line.x2 - line.x1) / max(len, EPS)
    val uy = (line.y2 - line.y1) / max(len, EPS)
    val n = inward(side)
    val c = cos(DOOR_SWING_RAD)
    val s = sin(DOOR_SWING_RAD)
    return CcMapPt(line.x1 + len * (ux * c + n.x * s), line.y1 + len * (uy * c + n.y * s))
  }

  /** Where a status chip ("OPEN 17m") for an opening sits: at the opening's start, just inside the room. */
  fun chipAnchor(line: CcMapLine, side: CcSide): CcMapPt {
    val n = inward(side)
    return CcMapPt(line.x1 + n.x * CHIP_INSET_CELLS, line.y1 + n.y * CHIP_INSET_CELLS)
  }

  fun device(rect: CcRect, fx: Float, fy: Float): CcMapPt = CcMapPt(rect.x + fx * rect.w, rect.y + fy * rect.h)

  fun center(rect: CcRect): CcMapPt = CcMapPt(rect.x + rect.w / 2f, rect.y + rect.h / 2f)

  /** The room under a point (cells): indoor rooms win over outdoor slabs, then the smallest box. */
  fun roomAt(floor: CcFloor, cx: Float, cy: Float): CcRoom? =
      floor.rooms.filter { it.rect.contains(cx, cy) }
          .minWithOrNull(compareBy<CcRoom> { it.outdoor }.thenBy { it.rect.w * it.rect.h })

  /** Glow radius in cells for a light at [strength] (0..1), the mock's `scale(.35 + b·.75)`. */
  fun glowRadius(strength: Float): Float = GLOW_CELLS * (GLOW_MIN + strength.coerceIn(0f, 1f) * GLOW_RANGE)

  /** Which room of [count] the walk is in at [progress] (0..1); −1 when there are none. */
  fun walkIndex(count: Int, progress: Float): Int =
      if (count <= 0) -1 else (progress.coerceIn(0f, 1f) * count).toInt().coerceAtMost(count - 1)

  /**
   * Where the walk light is: resting on room i's centre, gliding to room i+1 in the last
   * part of its slot. Null while the current room isn't on this floor ([centres] entry null).
   */
  fun walkPoint(centres: List<CcMapPt?>, progress: Float): CcMapPt? {
    val i = walkIndex(centres.size, progress)
    if (i < 0) return null
    val here = centres[i] ?: return null
    val local = progress.coerceIn(0f, 1f) * centres.size - i
    val next = centres.getOrNull(i + 1)
    if (next == null || local <= WALK_MOVE_FROM) return here
    val k = smooth((local - WALK_MOVE_FROM) / (1f - WALK_MOVE_FROM))
    return CcMapPt(here.x + (next.x - here.x) * k, here.y + (next.y - here.y) * k)
  }

  /** Rooms the walk has already passed at [progress]. */
  fun walkVisited(rooms: List<String>, progress: Float): Set<String> {
    val i = walkIndex(rooms.size, progress)
    if (i < 0) return emptySet()
    val done = if (progress >= 1f) rooms.size else i
    return rooms.take(done).toSet()
  }

  private fun smooth(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

  private const val EPS = 1e-4f
}

/** The id of the point nearest ([x], [y]) within [radius] (all in the same units), or null. */
internal fun ccNearest(points: List<Pair<String, CcMapPt>>, x: Float, y: Float, radius: Float): String? =
    points.map { (id, p) -> id to kotlin.math.hypot(p.x - x, p.y - y) }.filter { it.second <= radius }.minByOrNull { it.second }?.first
