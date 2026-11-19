/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import kotlin.math.atan2

/**
 * The plan's pixel geometry for one floor at one canvas size: room rectangles, the wall path,
 * outdoor slabs, opening segments and door swings. Built once per (floor, size) and reused by
 * every frame, so drawing never allocates.
 */
internal class CcPlanPx(val fit: CcPlanFit, model: CcPlanModel) {
  /** Px rect per room id, in the floor's order. */
  val rects: Map<String, Rect> = model.floor.rooms.associate { it.id to rect(it.rect) }
  val indoor: List<Pair<String, Rect>> = model.floor.rooms.filter { !it.outdoor }.map { it.id to rects.getValue(it.id) }
  val outdoor: List<Pair<String, RoundRect>> = model.floor.rooms.filter { it.outdoor }.map {
    val r = rects.getValue(it.id)
    it.id to RoundRect(r, CornerRadius(OUTDOOR_CORNER_CELLS * fit.scale))
  }
  val outdoorPaths: List<Path> = outdoor.map { (_, rr) -> Path().apply { addRoundRect(rr) } }
  /** Every indoor wall, shared edges once ([ccWalls]), as one path of straight runs. */
  val walls: Path = Path().apply {
    ccWalls(model.floor.rooms).forEach { l -> moveTo(fit.x(l.x1), fit.y(l.y1)); lineTo(fit.x(l.x2), fit.y(l.y2)) }
  }
  val openings: List<OpeningPx> = model.openings.map { o -> opening(o) }

  class OpeningPx(val a: Offset, val b: Offset, val swing: Offset, val arc: Path, val chip: Offset)

  fun pt(p: CcMapPt): Offset = Offset(fit.x(p.x), fit.y(p.y))
  fun pt(x: Float, y: Float): Offset = Offset(fit.x(x), fit.y(y))

  private fun rect(r: CcRect) = Rect(fit.x(r.x.toFloat()), fit.y(r.y.toFloat()), fit.x(r.right.toFloat()), fit.y(r.bottom.toFloat()))

  private fun opening(o: CcOpeningView): OpeningPx {
    val a = Offset(fit.x(o.line.x1), fit.y(o.line.y1))
    val b = Offset(fit.x(o.line.x2), fit.y(o.line.y2))
    val swing = pt(CcPlanGeo.doorSwing(o.line, o.opening.side))
    val radius = (b - a).getDistance()
    val start = deg(atan2(b.y - a.y, b.x - a.x))
    var sweep = deg(atan2(swing.y - a.y, swing.x - a.x)) - start
    if (sweep > HALF_TURN) sweep -= FULL_TURN
    if (sweep < -HALF_TURN) sweep += FULL_TURN
    val arc = Path().apply { arcTo(Rect(a, radius), start, sweep, forceMoveTo = true) }
    return OpeningPx(a, b, swing, arc, pt(CcPlanGeo.chipAnchor(o.line, o.opening.side)))
  }

  private fun deg(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()

  companion object {
    /** The mock's `rx=14` outdoor corner on its 20-unit grid. */
    const val OUTDOOR_CORNER_CELLS = 0.7f
    private const val HALF_TURN = 180f
    private const val FULL_TURN = 360f
  }
}

/** A tiny least-recently-used cache (floors already shown keep their geometry / model). Not thread-safe; UI thread only. */
internal class CcLru<K, V>(private val max: Int) {
  private val map = LinkedHashMap<K, V>(max + 1, LOAD, true)
  val size: Int get() = map.size

  operator fun get(key: K): V? = map[key]

  fun getOrPut(key: K, make: () -> V): V = map[key] ?: make().also { put(key, it) }

  fun put(key: K, value: V) {
    map[key] = value
    if (map.size > max) map.remove(map.keys.first())
  }

  private companion object { const val LOAD = 0.75f }
}

/** What a floor's pixel geometry depends on. */
internal data class CcPxKey(val floor: CcFloor, val width: Int, val height: Int, val maxScale: Float)
