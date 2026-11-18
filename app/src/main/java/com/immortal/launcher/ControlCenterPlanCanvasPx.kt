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
import androidx.compose.ui.graphics.PathEffect
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
  val center: Offset = pt(model.bounds.center)
  val bounds: Rect = Rect(fit.x(model.bounds.l), fit.y(model.bounds.t), fit.x(model.bounds.r), fit.y(model.bounds.b))

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

/** Energy-lens flow curves from the home hub, rebuilt only when the flowing rooms change. */
internal class CcFlowPx(px: CcPlanPx, flows: List<CcMapFlow>, sources: CcMapSources) {
  class Line(val path: Path, val widthDp: Float, val rgb: Int, val reverse: Boolean)

  val hub: Offset = px.center
  val solar: Offset? = if (sources.solarW != null) Offset(px.center.x, px.bounds.top) else null
  val grid: Offset? = if (sources.gridW != null) Offset(px.bounds.left, px.center.y) else null
  val battery: Offset? = if (sources.batteryW != null) Offset(px.bounds.left + px.bounds.width * BATTERY_AT, px.bounds.bottom) else null
  val lines: List<Line> = buildList {
    val bend = BEND_CELLS * px.fit.scale
    solar?.takeIf { (sources.solarW ?: 0.0) > MIN_W }?.let { add(Line(curve(it, hub, bend), width(sources.solarW), SOLAR_RGB, false)) }
    battery?.takeIf { kotlin.math.abs(sources.batteryW ?: 0.0) > MIN_W }?.let {
      add(Line(curve(it, hub, -bend), width(sources.batteryW), BATTERY_RGB, (sources.batteryW ?: 0.0) < 0))
    }
    grid?.takeIf { kotlin.math.abs(sources.gridW ?: 0.0) > MIN_W }?.let {
      add(Line(curve(it, hub, bend), width(sources.gridW), GRID_RGB, (sources.gridW ?: 0.0) < 0))
    }
    flows.take((CcPlanModel.MAX_FLOWS - size).coerceAtLeast(0)).forEach { f ->
      add(Line(curve(hub, px.pt(f.target), bend), width(f.kw * W_PER_KW), ROOM_RGB, false))
    }
  }

  private fun curve(a: Offset, b: Offset, bend: Float) = Path().apply {
    moveTo(a.x, a.y)
    quadraticTo((a.x + b.x) / 2f + bend, (a.y + b.y) / 2f - bend, b.x, b.y)
  }

  /** The mock's `5 + min(kW, 4) × 4` stroke. */
  private fun width(w: Double?): Float = BASE_W + (kotlin.math.abs(w ?: 0.0) / W_PER_KW).coerceAtMost(MAX_KW).toFloat() * PER_KW

  companion object {
    const val SOLAR_RGB = 0xF4C542
    const val BATTERY_RGB = 0x66C49E
    const val GRID_RGB = 0x8FA3FF
    const val ROOM_RGB = 0xF0A65A
    private const val BATTERY_AT = 0.25f
    private const val BEND_CELLS = 1.5f
    private const val MIN_W = 50.0
    private const val W_PER_KW = 1000.0
    private const val BASE_W = 5f
    private const val PER_KW = 4f
    private const val MAX_KW = 4.0
  }
}

/** The house-level power figures the energy lens draws as nodes (null = the house has no such sensor). */
internal data class CcMapSources(val homeW: Double?, val solarW: Double?, val gridW: Double?, val batteryW: Double?, val batteryPct: Int?) {
  companion object {
    val NONE = CcMapSources(null, null, null, null, null)
    fun of(e: CcEnergyNow) = CcMapSources(e.homeW, e.solarW, e.gridW, e.batteryW, e.batteryPct)
  }
}

/** Dash patterns for the moving flow lines, one per phase step, built once per density. */
internal class CcMapDashes(dashPx: Float, gapPx: Float) {
  val effects: List<PathEffect> = (0 until STEPS).map { i ->
    PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), -(dashPx + gapPx) * i / STEPS)
  }

  fun at(phase: Float): PathEffect = effects[((phase.coerceIn(0f, 1f) * STEPS).toInt()).coerceIn(0, STEPS - 1)]

  companion object { const val STEPS = 25 }
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
