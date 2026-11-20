/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * The 3D view of one floor at one canvas size, built the first time the floor is shown in 3D
 * (2D-only Portals never pay for it): the isometric fit, the 2D → screen map the flat layers are
 * replayed through, and the standing walls and slab sides as screen-space paths in painter's
 * order. At rest those paths are recorded once into cached layers like every other pass; during
 * the 2D ↔ 3D switch the walls rise and the slab drops with the progress, drawn live.
 */
internal class CcIsoPx(val px: CcPlanPx, model: CcPlanModel, w: Float, h: Float, maxScale: Float) {
  val fit: CcIsoFit = CcIso.fit(model.bounds, w, h, maxScale = maxScale)
  private val centre = model.bounds.center
  /** 2D plan pixels → screen, fully isometric. */
  val view: CcAffine = CcIso.blend(px.fit, fit, centre, 1f)
  private val viewMatrix = view.toMatrix()
  private val walls = CcIso.walls(model.floor.rooms)
  private val slab = CcIso.slab(model.floor.rooms)
  /** Rest geometry, one closed quad per face (cached layers record these once). */
  private val wallPaths: List<Path> by lazy { walls.map { quad(it, view, -CcIso.WALL_CELLS * fit.scale) } }
  private val slabPaths: List<Pair<Path, Boolean>> by lazy { slab.map { quad(it, view, CcIso.SLAB_CELLS * fit.scale) to it.isSouth } }
  /** Reused while the switch animates (one path for all wall fills, one for their outlines). */
  private val liveFill = Path()
  private val liveSlab = Path()
  private val liveSlabEast = Path()

  /** The map at switch progress [e] (0 = flat, 1 = [view]). */
  fun blend(e: Float): CcAffine = if (e >= 1f) view else CcIso.blend(px.fit, fit, centre, e)

  /** Runs [block] with the flat layers projected at progress [e]; nothing to do for the 2D plan. */
  inline fun projected(s: DrawScope, e: Float, crossinline block: DrawScope.() -> Unit) {
    if (e <= 0f) { s.block(); return }
    s.withTransform({ transform(matrix(e)) }) { block() }
  }

  fun matrix(e: Float): Matrix = if (e >= 1f) viewMatrix else blend(e).toMatrix()

  /** Walls at rest: each face filled and outlined in painter's order (recorded once into a layer). */
  fun drawWalls(s: DrawScope, c: CcColors, outline: Stroke) {
    for (p in wallPaths) {
      s.drawPath(p, c.wallFace, alpha = FACE_ALPHA)
      s.drawPath(p, c.wall, style = outline)
    }
  }

  /** The slab's front (south) and side (east) faces at rest, under the floors. */
  fun drawSlab(s: DrawScope, c: CcColors) {
    for ((p, south) in slabPaths) s.drawPath(p, if (south) c.slab else c.slab2)
  }

  /** Walls rising while the view tips back: all faces in one fill and one outline (no per-face painter's order). */
  fun drawWallsLive(s: DrawScope, e: Float, c: CcColors, outline: Stroke) {
    val m = blend(e)
    liveFill.rewind()
    for (f in walls) addQuad(liveFill, f, m, -CcIso.WALL_CELLS * fit.scale * e)
    s.drawPath(liveFill, c.wallFace, alpha = FACE_ALPHA * e.coerceIn(0f, 1f))
    s.drawPath(liveFill, c.wall, style = outline)
  }

  fun drawSlabLive(s: DrawScope, e: Float, c: CcColors) {
    val m = blend(e)
    liveSlab.rewind()
    liveSlabEast.rewind()
    for (f in slab) addQuad(if (f.isSouth) liveSlab else liveSlabEast, f, m, CcIso.SLAB_CELLS * fit.scale * e)
    s.drawPath(liveSlab, c.slab)
    s.drawPath(liveSlabEast, c.slab2)
  }

  private fun quad(f: CcIso.Face, m: CcAffine, lift: Float): Path = Path().also { addQuad(it, f, m, lift) }

  /** The face standing on [f]'s base (2D cells through [m]) and reaching [lift] px up (negative) or down. */
  private fun addQuad(p: Path, f: CcIso.Face, m: CcAffine, lift: Float) {
    val ax = px.fit.x(f.x1)
    val ay = px.fit.y(f.y1)
    val bx = px.fit.x(f.x2)
    val by = px.fit.y(f.y2)
    val x1 = m.x(ax, ay)
    val y1 = m.y(ax, ay)
    val x2 = m.x(bx, by)
    val y2 = m.y(bx, by)
    p.moveTo(x1, y1)
    p.lineTo(x2, y2)
    p.lineTo(x2, y2 + lift)
    p.lineTo(x1, y1 + lift)
    p.close()
  }

  companion object {
    /** The mock's `.wf` opacity. */
    const val FACE_ALPHA = 0.92f
    /** The mock's 1.2-unit face outline, in dp. */
    const val OUTLINE_DP = 1.5f

    fun outline(dp: Float) = Stroke(OUTLINE_DP * dp, join = StrokeJoin.Round)
  }
}

/** A horizontal face (the slab's front) rather than a vertical one (its east side). */
private val CcIso.Face.isSouth: Boolean get() = y1 == y2

/** The affine as a Compose [Matrix] (`DrawTransform.transform`). */
internal fun CcAffine.toMatrix(): Matrix = Matrix().also { m ->
  m.values[Matrix.ScaleX] = a
  m.values[Matrix.SkewY] = b
  m.values[Matrix.SkewX] = c
  m.values[Matrix.ScaleY] = d
  m.values[Matrix.TranslateX] = tx
  m.values[Matrix.TranslateY] = ty
}
