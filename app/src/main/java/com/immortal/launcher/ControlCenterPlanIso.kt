/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine map `(x, y) → (a·x + c·y + tx, b·x + d·y + ty)` (pure, unit tested). The plan's flat
 * layers are recorded once in 2D pixels; the 3D view replays them through one of these, so a
 * 2D ↔ 3D switch re-records nothing. Also how a tap finds its room ([inverse]).
 */
internal data class CcAffine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
  fun x(px: Float, py: Float): Float = a * px + c * py + tx
  fun y(px: Float, py: Float): Float = b * px + d * py + ty
  val isIdentity: Boolean get() = this == IDENTITY

  /** The map back from screen to 2D pixels; null when it squashes the plane flat. */
  fun inverse(): CcAffine? {
    val det = a * d - b * c
    if (abs(det) < EPS) return null
    val ia = d / det
    val ib = -b / det
    val ic = -c / det
    val id = a / det
    return CcAffine(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
  }

  /** How far a unit step along any 2D direction can reach horizontally / vertically: a circle's oval radii. */
  val rowX: Float get() = kotlin.math.hypot(a, c)
  val rowY: Float get() = kotlin.math.hypot(b, d)

  /** How much a vertical extent of 2D pixels grows on screen (labels use it to decide what fits). */
  val verticalScale: Float get() = abs(b) + abs(d)

  companion object {
    val IDENTITY = CcAffine(1f, 0f, 0f, 1f, 0f, 0f)
    private const val EPS = 1e-6f
  }
}

/** Cells → screen in the isometric view: `screen = (dx, dy) + scale · iso(cell)`, centred in the canvas. */
internal data class CcIsoFit(val scale: Float, val dx: Float, val dy: Float) {
  fun x(cx: Float, cy: Float): Float = dx + scale * CcIso.isoX(cx, cy)
  fun y(cx: Float, cy: Float): Float = dy + scale * CcIso.isoY(cx, cy)
}

/**
 * The mock's isometric view (`matrix(.866 .5 -.866 .5 0 0)`): rooms laid flat and rotated 45°,
 * squashed to a 30° tilt, walls standing [WALL_CELLS] tall and the house on a [SLAB_CELLS] slab.
 * Pure math, unit tested: the fit, the 2D → 3D blend the switch animates through, and the wall
 * faces in painter's order.
 */
internal object CcIso {
  const val COS = 0.866f
  const val SIN = 0.5f
  /** The mock's 20-unit wall on its 20-unit grid. */
  const val WALL_CELLS = 1f
  /** The mock's 34-unit slab under the house. */
  const val SLAB_CELLS = 1.7f
  /** The mock's 50-unit iso margin (2.5 cells). */
  const val MARGIN_CELLS = 2.5f
  private const val TILT_DEG = 45f
  /** `ISO = K · squash(Q) · rotate(45°)`: the uniform part and the vertical squash of the mock's matrix. */
  private val K = COS * sqrt(2f)
  private const val Q = SIN / COS

  fun isoX(x: Float, y: Float): Float = (x - y) * COS
  fun isoY(x: Float, y: Float): Float = (x + y) * SIN

  /**
   * Fits the floor's [box] (cells) as seen isometrically — walls above, slab below, [margin] all
   * round — into [w]×[h] px, centred, at most [maxScale] px per cell.
   */
  fun fit(box: CcMapBox, w: Float, h: Float, maxScale: Float = Float.MAX_VALUE, margin: Float = MARGIN_CELLS): CcIsoFit {
    val xs = floatArrayOf(isoX(box.l, box.t), isoX(box.r, box.t), isoX(box.l, box.b), isoX(box.r, box.b))
    val ys = floatArrayOf(isoY(box.l, box.t), isoY(box.r, box.t), isoY(box.l, box.b), isoY(box.r, box.b))
    val left = xs.min() - margin
    val right = xs.max() + margin
    val top = ys.min() - WALL_CELLS - margin
    val bottom = ys.max() + SLAB_CELLS + margin
    val bw = right - left
    val bh = bottom - top
    if (w <= 0f || h <= 0f || bw <= 0f || bh <= 0f) return CcIsoFit(1f, 0f, 0f)
    val scale = min(min(w / bw, h / bh), maxScale)
    return CcIsoFit(scale, w / 2f - (left + right) / 2f * scale, h / 2f - (top + bottom) / 2f * scale)
  }

  /**
   * 2D plan pixels → screen at switch progress [e] (0 = the flat plan, 1 = isometric). Between
   * them the plan turns, tilts and rescales about the floor's centre rather than shearing, so the
   * motion reads as the house tipping back. At 1 it is exactly [fit3] applied to the cell under
   * each 2D pixel.
   */
  fun blend(fit2: CcPlanFit, fit3: CcIsoFit, centre: CcMapPt, e: Float): CcAffine {
    val t = e.coerceIn(0f, 1f)
    if (t == 0f) return CcAffine.IDENTITY
    val theta = Math.toRadians((TILT_DEG * t).toDouble())
    val cs = cos(theta).toFloat()
    val sn = sin(theta).toFloat()
    val squash = 1f + (Q - 1f) * t
    val k = 1f + (fit3.scale * K / fit2.scale - 1f) * t
    // L = k · squash(q) · rotate(θ)
    val a = k * cs
    val b = k * squash * sn
    val c = -k * sn
    val d = k * squash * cs
    val fromX = fit2.x(centre.x)
    val fromY = fit2.y(centre.y)
    val toX = fromX + (fit3.x(centre.x, centre.y) - fromX) * t
    val toY = fromY + (fit3.y(centre.x, centre.y) - fromY) * t
    return CcAffine(a, b, c, d, toX - (a * fromX + c * fromY), toY - (b * fromX + d * fromY))
  }

  /**
   * [blend] split into what two nested render-node layers can apply to an already rasterized
   * texture: an inner rotation by [Parts.degrees] about the pivot, then an outer scale
   * ([Parts.scaleX], [Parts.scaleY]) about the same pivot plus a translation. The switch moves the
   * cached flat plan this way, so no vector is re-rasterized under a new matrix every frame.
   */
  data class Parts(val degrees: Float, val scaleX: Float, val scaleY: Float, val tx: Float, val ty: Float, val pivotX: Float, val pivotY: Float)

  fun parts(fit2: CcPlanFit, fit3: CcIsoFit, centre: CcMapPt, e: Float): Parts {
    val t = e.coerceIn(0f, 1f)
    val k = 1f + (fit3.scale * K / fit2.scale - 1f) * t
    val squash = 1f + (Q - 1f) * t
    val fromX = fit2.x(centre.x)
    val fromY = fit2.y(centre.y)
    return Parts(TILT_DEG * t, k, k * squash, (fit3.x(centre.x, centre.y) - fromX) * t, (fit3.y(centre.x, centre.y) - fromY) * t, fromX, fromY)
  }

  /** A standing wall (or slab side) between two grid points, in cells. */
  data class Face(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    /** Painter's key: faces with a larger x + y are nearer the viewer and drawn later. */
    val depth: Float get() = x1 + y1 + x2 + y2
  }

  /**
   * Every indoor wall as faces split wherever another wall meets it, back to front. Splitting at
   * junctions keeps painter's order right where a short wall stands behind a long one.
   */
  fun walls(rooms: List<CcRoom>): List<Face> {
    val h = HashSet<Long>()
    val v = HashSet<Long>()
    for (r in rooms) if (!r.outdoor) with(r.rect) {
      for (i in x until right) { h += key(y, i); h += key(bottom, i) }
      for (j in y until bottom) { v += key(x, j); v += key(right, j) }
    }
    // A grid point is a junction when a wall of the other direction touches it.
    fun vAt(px: Int, py: Int) = key(px, py) in v || key(px, py - 1) in v
    fun hAt(px: Int, py: Int) = key(py, px) in h || key(py, px - 1) in h
    val out = ArrayList<Face>()
    runs(h) { line, from, to -> splitRuns(from, to, { p -> vAt(p, line) }) { s, e -> out += Face(s.toFloat(), line.toFloat(), e.toFloat(), line.toFloat()) } }
    runs(v) { line, from, to -> splitRuns(from, to, { p -> hAt(line, p) }) { s, e -> out += Face(line.toFloat(), s.toFloat(), line.toFloat(), e.toFloat()) } }
    return out.sortedBy { it.depth }
  }

  /**
   * The slab's visible sides: indoor edges facing the viewer (south and east) with no indoor
   * room beyond them, as straight runs, back to front.
   */
  fun slab(rooms: List<CcRoom>): List<Face> {
    val indoor = HashSet<Long>()
    for (r in rooms) if (!r.outdoor) with(r.rect) { for (i in x until right) for (j in y until bottom) indoor += key(i, j) }
    val south = HashSet<Long>()
    val east = HashSet<Long>()
    for (cell in indoor) {
      val cx = (cell shr SHIFT).toInt()
      val cy = cell.toInt()
      if (key(cx, cy + 1) !in indoor) south += key(cy + 1, cx)
      if (key(cx + 1, cy) !in indoor) east += key(cx + 1, cy)
    }
    val out = ArrayList<Face>()
    runs(south) { line, from, to -> out += Face(from.toFloat(), line.toFloat(), to.toFloat(), line.toFloat()) }
    runs(east) { line, from, to -> out += Face(line.toFloat(), from.toFloat(), line.toFloat(), to.toFloat()) }
    return out.sortedBy { it.depth }
  }

  private fun key(a: Int, b: Int): Long = (a.toLong() shl SHIFT) or (b.toLong() and LOW)

  /** Unit steps keyed (line, step) → maximal straight runs `[from, to)`. */
  private inline fun runs(steps: Set<Long>, emit: (line: Int, from: Int, to: Int) -> Unit) {
    val byLine = steps.groupBy({ (it shr SHIFT).toInt() }, { it.toInt() }).toSortedMap()
    for ((line, list) in byLine) {
      val sorted = list.sorted()
      var start = sorted.first()
      var prev = start
      for (s in sorted.drop(1)) { if (s != prev + 1) { emit(line, start, prev + 1); start = s }; prev = s }
      emit(line, start, prev + 1)
    }
  }

  private inline fun splitRuns(from: Int, to: Int, junction: (Int) -> Boolean, emit: (Int, Int) -> Unit) {
    var start = from
    for (p in from + 1 until to) if (junction(p)) { emit(start, p); start = p }
    emit(start, to)
  }

  private const val SHIFT = 32
  private const val LOW = 0xFFFFFFFFL

  /** Every projected corner of [box] on screen (the fit keeps them inside the canvas). */
  fun corners(box: CcMapBox, fit: CcIsoFit): List<CcMapPt> =
      listOf(box.l to box.t, box.r to box.t, box.l to box.b, box.r to box.b).map { (x, y) -> CcMapPt(fit.x(x, y), fit.y(x, y)) }
}
