/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Sizes the editor's dot layout needs, in grid cells (the canvas converts its dp once per size). */
@Immutable
internal data class CcEdDotSpec(val dotCells: Float, val gapCells: Float) {
  val radius: Float get() = dotCells / 2f
  /** Centre-to-centre distance of two neighbouring dots (mock: r 18 + 46 apart). */
  val pitch: Float get() = dotCells + gapCells

  companion object {
    fun of(cellPx: Float, dotPx: Float, gapPx: Float): CcEdDotSpec {
      val cell = cellPx.coerceAtLeast(MIN_CELL_PX)
      return CcEdDotSpec(dotPx / cell, gapPx / cell)
    }

    private const val MIN_CELL_PX = 0.01f
  }
}

/** One shown dot. [fx]/[fy] are fractions of its room (so it rides a moved or resized room); [gx]/[gy] are grid cells. */
@Immutable
internal data class CcEdDot(val entityId: String, val roomId: String, val fx: Float, val fy: Float, val gx: Float, val gy: Float,
                            val ring: Boolean = false)

/** "+N": the devices that don't fit in [roomId]. [expanded] shows them as a ring around it. */
@Immutable
internal data class CcEdCluster(val roomId: String, val fx: Float, val fy: Float, val gx: Float, val gy: Float, val hidden: List<String>,
                                val expanded: Boolean) {
  val label: String get() = "+${hidden.size}"
}

@Immutable
internal data class CcEdDotLayout(val dots: List<CcEdDot>, val clusters: List<CcEdCluster>) {
  /** The dot under the finger: an open ring first (it's drawn on top), then the nearest room dot. */
  fun deviceAt(gx: Float, gy: Float, tolCells: Float): String? =
      nearest(dots.filter { it.ring }, gx, gy, tolCells) ?: nearest(dots.filterNot { it.ring }, gx, gy, tolCells)

  fun clusterAt(gx: Float, gy: Float, tolCells: Float): CcEdCluster? =
      clusters.map { it to hypot(gx - it.gx, gy - it.gy) }.filter { it.second <= tolCells }.minByOrNull { it.second }?.first

  private fun nearest(list: List<CcEdDot>, gx: Float, gy: Float, tol: Float): String? =
      list.map { it.entityId to hypot(gx - it.gx, gy - it.gy) }.filter { it.second <= tol }.minByOrNull { it.second }?.first

  companion object {
    val EMPTY = CcEdDotLayout(emptyList(), emptyList())
  }
}

/**
 * Where the editor draws device dots. Each room gets a grid of slots one dot + gap apart, inside its
 * walls and clear of its centred label when there's room. Every device takes the free slot nearest
 * its saved spot (in placement order, so earlier devices keep theirs), so no two dots overlap. When
 * a room has more devices than slots, the last slot is a "+N" cluster; tapping it opens the rest as
 * rings around it. Display only: the saved fractions (and the live map) are untouched.
 */
internal object CcEdDots {
  /** [labels]: per room id, the label's half width and half height in cells. */
  fun layout(floor: CcFloor, spec: CcEdDotSpec, expandedRoom: String? = null,
             labels: Map<String, Pair<Float, Float>> = emptyMap()): CcEdDotLayout {
    val dots = ArrayList<CcEdDot>()
    val clusters = ArrayList<CcEdCluster>()
    floor.rooms.forEach { room ->
      if (room.devices.isEmpty()) return@forEach
      val (shown, cluster) = layoutRoom(room, spec, room.id == expandedRoom, labels[room.id])
      dots += shown
      cluster?.let { clusters += it }
    }
    return CcEdDotLayout(dots, clusters)
  }

  /**
   * Tap on the canvas while dots show: a cluster toggles open/closed; anywhere else closes an open
   * one. Returns (consumed, the expanded room after the tap).
   */
  fun tap(layout: CcEdDotLayout, expanded: String?, gx: Float, gy: Float, tolCells: Float): Pair<Boolean, String?> {
    val hit = layout.clusterAt(gx, gy, tolCells) ?: return false to null
    return true to if (hit.roomId == expanded) null else hit.roomId
  }

  private fun layoutRoom(room: CcRoom, spec: CcEdDotSpec, expanded: Boolean, label: Pair<Float, Float>?): Pair<List<CcEdDot>, CcEdCluster?> {
    val r = room.rect
    val all = slots(r, spec, label)
    val n = room.devices.size
    val usable = if (n <= all.clear) all.points.take(all.clear) else all.points
    val overflow = n > usable.size
    // Overflow: the bottom-right slot is the cluster; the rest are filled nearest-first.
    val clusterSlot = if (overflow) usable.maxWith(compareBy<Pt>({ it.y }, { it.x })) else null
    val free = usable.filter { it !== clusterSlot }.toMutableList()
    val shownCount = if (overflow) free.size else n
    val shown = room.devices.take(shownCount).map { d ->
      val want = Pt(d.fx * r.w, d.fy * r.h)
      val slot = free.minWith(compareBy { hypot(it.x - want.x, it.y - want.y) })
      free.remove(slot)
      dot(d.entityId, room, slot)
    }
    if (clusterSlot == null) return shown to null
    val hidden = room.devices.drop(shownCount).map { it.entityId }
    val cluster = CcEdCluster(room.id, clusterSlot.x / r.w, clusterSlot.y / r.h, r.x + clusterSlot.x, r.y + clusterSlot.y, hidden, expanded)
    val ring = if (expanded) ring(cluster, room, spec) else emptyList()
    return (shown + ring) to cluster
  }

  /** Slots in room-local cells, row-major; the first [clear] stay off the label. */
  private class Slots(val points: List<Pt>, val clear: Int)

  private class Pt(val x: Float, val y: Float)

  private fun slots(r: CcRect, spec: CcEdDotSpec, label: Pair<Float, Float>?): Slots {
    val xs = axis(r.w.toFloat(), spec)
    val ys = axis(r.h.toFloat(), spec)
    val grid = ys.flatMap { y -> xs.map { x -> Pt(x, y) } }
    val (halfW, halfH) = label ?: (0f to 0f)
    val onLabel = { p: Pt ->
      label != null && abs(p.x - r.w / 2f) < halfW + spec.radius && abs(p.y - r.h / 2f) < halfH + spec.radius
    }
    val clear = grid.filterNot(onLabel)
    return Slots(clear + grid.filter(onLabel), clear.size)
  }

  /** Dot centres along one wall-to-wall span: as many as fit a pitch apart, centred; one in the middle if none fit. */
  private fun axis(span: Float, spec: CcEdDotSpec): List<Float> {
    val inner = span - 2 * (spec.radius + spec.gapCells / 2f)
    if (inner < 0f) return listOf(span / 2f)
    val count = floor(inner / spec.pitch).toInt() + 1
    val start = (span - (count - 1) * spec.pitch) / 2f
    return List(count) { start + it * spec.pitch }
  }

  /** The cluster's devices on rings a pitch apart around it; a spot off the board is skipped, never stacked. */
  private fun ring(c: CcEdCluster, room: CcRoom, spec: CcEdDotSpec): List<CcEdDot> {
    val out = ArrayList<CcEdDot>(c.hidden.size)
    var ringNo = 1
    while (out.size < c.hidden.size && ringNo <= MAX_RINGS) {
      val radius = ringNo * spec.pitch
      val capacity = max(1, floor(2 * PI * radius / spec.pitch).toInt())
      for (k in 0 until capacity) {
        if (out.size == c.hidden.size) break
        // Start straight up and go clockwise.
        val a = -PI / 2 + 2 * PI * k / capacity
        val gx = c.gx + radius * cos(a).toFloat()
        val gy = c.gy + radius * sin(a).toFloat()
        val onBoard = gx in spec.radius..(CcPlan.GRID_W - spec.radius) && gy in spec.radius..(CcPlan.GRID_H - spec.radius)
        if (onBoard) out += dot(c.hidden[out.size], room, Pt(gx - room.rect.x, gy - room.rect.y), ring = true)
      }
      ringNo++
    }
    return out
  }

  /** More rings than any board can hold dots for; only guards a degenerate spec. */
  private const val MAX_RINGS = 64

  private fun dot(id: String, room: CcRoom, p: Pt, ring: Boolean = false): CcEdDot {
    val r = room.rect
    return CcEdDot(id, room.id, p.x / r.w, p.y / r.h, r.x + p.x, r.y + p.y, ring)
  }
}
