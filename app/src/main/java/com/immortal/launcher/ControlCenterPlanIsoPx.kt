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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

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
  /**
   * Reused while the switch animates. Faces go out as plain triangles and outlines as lines, never
   * as paths: a path under a matrix that changes every frame gets a new GPU mask every frame, and
   * those masks pile up in the renderer's cache (measured: ~110 MB on a Portal+).
   */
  private val wallTris = FloatArray(walls.size * TRI_FLOATS)
  private val wallLines = FloatArray(walls.size * LINE_FLOATS)
  private val southTris = FloatArray(slab.count { it.isSouth } * TRI_FLOATS)
  private val eastTris = FloatArray(slab.count { !it.isSouth } * TRI_FLOATS)
  private val fillPaint = android.graphics.Paint()
  private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE
    strokeCap = android.graphics.Paint.Cap.ROUND
  }

  /** The map at switch progress [e] (0 = flat, 1 = [view]). */
  fun blend(e: Float): CcAffine = if (e >= 1f) view else CcIso.blend(px.fit, fit, centre, e)

  /** Runs [block] with the flat layers projected at progress [e]; nothing to do for the 2D plan. */
  inline fun projected(s: DrawScope, e: Float, crossinline block: DrawScope.() -> Unit) {
    if (e <= 0f) { s.block(); return }
    s.withTransform({ transform(matrix(e)) }) { block() }
  }

  /** [blend] at [e] as layer properties (see [CcIso.parts]). */
  fun parts(e: Float): CcIso.Parts = CcIso.parts(px.fit, fit, centre, e)

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

  /** Walls rising while the view tips back (one batch of triangles and one of lines; no per-face painter's order). */
  fun drawWallsLive(s: DrawScope, e: Float, c: CcColors, outline: Stroke) {
    if (!LIVE_VERTICES) { drawWallsLivePaths(s, e, c, outline); return }
    val m = blend(e)
    val lift = -CcIso.WALL_CELLS * fit.scale * e
    var t = 0
    var l = 0
    for (f in walls) {
      val x1 = m.x(px.fit.x(f.x1), px.fit.y(f.y1)); val y1 = m.y(px.fit.x(f.x1), px.fit.y(f.y1))
      val x2 = m.x(px.fit.x(f.x2), px.fit.y(f.y2)); val y2 = m.y(px.fit.x(f.x2), px.fit.y(f.y2))
      t = tris(wallTris, t, x1, y1, x2, y2, lift)
      l = line(wallLines, l, x1, y1 + lift, x2, y2 + lift)
      l = line(wallLines, l, x1, y1, x1, y1 + lift)
      l = line(wallLines, l, x2, y2, x2, y2 + lift)
      l = line(wallLines, l, x1, y1, x2, y2)
    }
    fillPaint.color = c.wallFace.copy(alpha = FACE_ALPHA * e.coerceIn(0f, 1f)).toArgb()
    linePaint.color = c.wall.toArgb()
    linePaint.strokeWidth = outline.width
    s.drawIntoCanvas { cv ->
      cv.nativeCanvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, t, wallTris, 0, null, 0, null, 0, null, 0, 0, fillPaint)
      cv.nativeCanvas.drawLines(wallLines, 0, l, linePaint)
    }
  }

  fun drawSlabLive(s: DrawScope, e: Float, c: CcColors) {
    if (!LIVE_VERTICES) { drawSlabLivePaths(s, e, c); return }
    val m = blend(e)
    val drop = CcIso.SLAB_CELLS * fit.scale * e
    var south = 0
    var east = 0
    for (f in slab) {
      val x1 = m.x(px.fit.x(f.x1), px.fit.y(f.y1)); val y1 = m.y(px.fit.x(f.x1), px.fit.y(f.y1))
      val x2 = m.x(px.fit.x(f.x2), px.fit.y(f.y2)); val y2 = m.y(px.fit.x(f.x2), px.fit.y(f.y2))
      if (f.isSouth) south = tris(southTris, south, x1, y1, x2, y2, drop) else east = tris(eastTris, east, x1, y1, x2, y2, drop)
    }
    s.drawIntoCanvas { cv ->
      fillPaint.color = c.slab.toArgb()
      cv.nativeCanvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, south, southTris, 0, null, 0, null, 0, null, 0, 0, fillPaint)
      fillPaint.color = c.slab2.toArgb()
      cv.nativeCanvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, east, eastTris, 0, null, 0, null, 0, null, 0, 0, fillPaint)
    }
  }

  /** Android 9's hardware canvas ignores drawVertices: the older Portals rebuild the faces as paths. */
  private fun drawWallsLivePaths(s: DrawScope, e: Float, c: CcColors, outline: Stroke) {
    val m = blend(e)
    val p = Path()
    for (f in walls) addQuad(p, f, m, -CcIso.WALL_CELLS * fit.scale * e)
    s.drawPath(p, c.wallFace, alpha = FACE_ALPHA * e.coerceIn(0f, 1f))
    s.drawPath(p, c.wall, style = outline)
  }

  private fun drawSlabLivePaths(s: DrawScope, e: Float, c: CcColors) {
    val m = blend(e)
    val south = Path()
    val east = Path()
    for (f in slab) addQuad(if (f.isSouth) south else east, f, m, CcIso.SLAB_CELLS * fit.scale * e)
    s.drawPath(south, c.slab)
    s.drawPath(east, c.slab2)
  }

  /** Two triangles for the face on base (x1, y1)–(x2, y2) reaching [lift] px; returns the next free float. */
  private fun tris(out: FloatArray, at: Int, x1: Float, y1: Float, x2: Float, y2: Float, lift: Float): Int {
    out[at] = x1; out[at + 1] = y1; out[at + 2] = x2; out[at + 3] = y2; out[at + 4] = x2; out[at + 5] = y2 + lift
    out[at + 6] = x1; out[at + 7] = y1; out[at + 8] = x2; out[at + 9] = y2 + lift; out[at + 10] = x1; out[at + 11] = y1 + lift
    return at + TRI_FLOATS
  }

  private fun line(out: FloatArray, at: Int, x1: Float, y1: Float, x2: Float, y2: Float): Int {
    out[at] = x1; out[at + 1] = y1; out[at + 2] = x2; out[at + 3] = y2
    return at + LINE_STEP
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

    /** Hardware drawVertices arrived in Android 10 (API 29). */
    private val LIVE_VERTICES = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    /** Two triangles × three vertices × (x, y). */
    private const val TRI_FLOATS = 12
    /** Four lines per wall face × (x1, y1, x2, y2). */
    private const val LINE_FLOATS = 16
    private const val LINE_STEP = 4
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
