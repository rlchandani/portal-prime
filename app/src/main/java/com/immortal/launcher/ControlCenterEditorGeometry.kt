/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editor canvas geometry (pure): the 48×30 snap grid aspect-fitted into the canvas exactly like
 * the map, finger → grid cell mapping, hit-testing (rooms, the resize handle, device dots,
 * openings) and the gesture reducer ([CcEdDrag]) that turns a drag into a plan edit.
 */
@Immutable
internal data class CcEdGeom(val width: Float, val height: Float, val insetTop: Float = 0f, val insetBottom: Float = 0f,
                             val insetSide: Float = 0f) {
  private val usableW = (width - 2 * insetSide).coerceAtLeast(1f)
  private val usableH = (height - insetTop - insetBottom).coerceAtLeast(1f)
  /** One grid cell in pixels. */
  val cell: Float = min(usableW / CcPlan.GRID_W, usableH / CcPlan.GRID_H)
  val originX: Float = insetSide + (usableW - cell * CcPlan.GRID_W) / 2f
  val originY: Float = insetTop + (usableH - cell * CcPlan.GRID_H) / 2f

  fun px(gx: Float): Float = originX + gx * cell
  fun py(gy: Float): Float = originY + gy * cell
  fun gx(px: Float): Float = (px - originX) / cell
  fun gy(py: Float): Float = (py - originY) / cell
  /** A pixel distance expressed in cells (hit tolerances are given in px). */
  fun cells(px: Float): Float = px / cell
  /** Is the pixel inside the plan's 48×30 board? */
  fun onBoard(px: Float, py: Float): Boolean = gx(px) in 0f..CcPlan.GRID_W.toFloat() && gy(py) in 0f..CcPlan.GRID_H.toFloat()
}

/** Wall segment of an opening in grid units. */
internal data class CcWallSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

internal enum class CcEdOpening { DOOR, WINDOW, GARAGE }

internal object CcEdGeo {
  fun roomAt(floor: CcFloor, gx: Float, gy: Float): CcRoom? = floor.rooms.lastOrNull { it.rect.contains(gx, gy) }

  fun suggestionAt(suggestions: List<CcRoom>, gx: Float, gy: Float): CcRoom? = suggestions.lastOrNull { it.rect.contains(gx, gy) }

  /** The resize handle sits on the room's bottom-right corner. */
  fun onHandle(rect: CcRect, gx: Float, gy: Float, tolCells: Float): Boolean = hypot(gx - rect.right, gy - rect.bottom) <= tolCells

  fun devicePoint(room: CcRoom, d: CcPlaced): Pair<Float, Float> = (room.rect.x + d.fx * room.rect.w) to (room.rect.y + d.fy * room.rect.h)

  /** The device dot nearest the finger within [tolCells], topmost room first. */
  fun deviceAt(floor: CcFloor, gx: Float, gy: Float, tolCells: Float): String? = floor.rooms.flatMap { r ->
    r.devices.map { d -> devicePoint(r, d).let { (x, y) -> d.entityId to hypot(gx - x, gy - y) } }
  }.filter { it.second <= tolCells }.minByOrNull { it.second }?.first

  fun segment(rect: CcRect, o: CcOpening): CcWallSeg {
    val a = (o.at - o.span / 2f).coerceIn(0f, 1f)
    val b = (o.at + o.span / 2f).coerceIn(0f, 1f)
    return when (o.side) {
      CcSide.N -> CcWallSeg(rect.x + a * rect.w, rect.y.toFloat(), rect.x + b * rect.w, rect.y.toFloat())
      CcSide.S -> CcWallSeg(rect.x + a * rect.w, rect.bottom.toFloat(), rect.x + b * rect.w, rect.bottom.toFloat())
      CcSide.W -> CcWallSeg(rect.x.toFloat(), rect.y + a * rect.h, rect.x.toFloat(), rect.y + b * rect.h)
      CcSide.E -> CcWallSeg(rect.right.toFloat(), rect.y + a * rect.h, rect.right.toFloat(), rect.y + b * rect.h)
    }
  }

  fun distanceToSegment(px: Float, py: Float, s: CcWallSeg): Float {
    val dx = s.x2 - s.x1
    val dy = s.y2 - s.y1
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((px - s.x1) * dx + (py - s.y1) * dy) / len2).coerceIn(0f, 1f)
    return hypot(px - (s.x1 + t * dx), py - (s.y1 + t * dy))
  }

  fun openingAt(floor: CcFloor, gx: Float, gy: Float, tolCells: Float): String? = floor.openings.mapNotNull { o ->
    val room = floor.rooms.firstOrNull { it.id == o.roomId } ?: return@mapNotNull null
    o.entityId to distanceToSegment(gx, gy, segment(room.rect, o))
  }.filter { it.second <= tolCells }.minByOrNull { it.second }?.first

  fun distanceToRect(r: CcRect, gx: Float, gy: Float): Float {
    val dx = maxOf(r.x - gx, 0f, gx - r.right)
    val dy = maxOf(r.y - gy, 0f, gy - r.bottom)
    return hypot(dx, dy)
  }

  fun nearestRoom(floor: CcFloor, gx: Float, gy: Float): CcRoom? =
      roomAt(floor, gx, gy) ?: floor.rooms.minByOrNull { distanceToRect(it.rect, gx, gy) }

  /** Re-snaps an opening to the nearest wall at the finger, keeping its width; null if no room exists. */
  fun moveOpening(floor: CcFloor, entityId: String, gx: Float, gy: Float): CcFloor? {
    val room = nearestRoom(floor, gx, gy) ?: return null
    val old = floor.openings.firstOrNull { it.entityId == entityId }
    val cx = gx.coerceIn(room.rect.x.toFloat(), room.rect.right.toFloat())
    val cy = gy.coerceIn(room.rect.y.toFloat(), room.rect.bottom.toFloat())
    val snapped = CcPlanEdit.nearestWall(entityId, room, cx, cy).let { if (old != null) it.copy(span = old.span) else it }
    return floor.copy(openings = floor.openings.filterNot { it.entityId == entityId } + snapped)
  }

  fun openingKind(entityId: String, reg: CcRegistry, live: CcEntity?): CcEdOpening {
    val cls = live?.deviceClass ?: reg.entityClass[entityId]
    return when {
      cls == "window" -> CcEdOpening.WINDOW
      entityId.startsWith("cover.") || cls == "garage_door" -> CcEdOpening.GARAGE
      else -> CcEdOpening.DOOR
    }
  }

  /** Fly-in offset (1 = still off to the side, 0 = home) for dot [index] of [count] at overall [progress]. */
  fun flyOffset(progress: Float, index: Int, count: Int): Float {
    val total = FLY_MS + STAGGER_MS * (count - 1).coerceAtLeast(0)
    val t = ((progress * total - index * STAGGER_MS) / FLY_MS).coerceIn(0f, 1f)
    val eased = 1f - (1f - t) * (1f - t) * (1f - t)
    return 1f - eased
  }

  /** Total fly-in length for [count] dots (each starts [STAGGER_MS] after the previous). */
  fun flyDurationMs(count: Int): Int = (FLY_MS + STAGGER_MS * (count - 1).coerceAtLeast(0)).roundToInt()

  const val FLY_MS = 900f
  const val STAGGER_MS = 25f
}

/** One finger drag on the editor canvas, in grid units. */
@Immutable
internal sealed interface CcEdDrag {
  @Immutable data class Draw(val ax: Float, val ay: Float, val bx: Float, val by: Float) : CcEdDrag {
    val rect: CcRect get() = CcPlanEdit.rectFrom(ax, ay, bx, by)
  }
  @Immutable data class Move(val roomId: String, val sx: Float, val sy: Float, val cx: Float, val cy: Float) : CcEdDrag {
    val dx: Int get() = (cx - sx).roundToInt()
    val dy: Int get() = (cy - sy).roundToInt()
  }
  @Immutable data class Resize(val roomId: String, val orig: CcRect, val cx: Float, val cy: Float) : CcEdDrag {
    val w: Int get() = (cx - orig.x).roundToInt().coerceAtLeast(CcEd.MIN_ROOM_CELLS)
    val h: Int get() = (cy - orig.y).roundToInt().coerceAtLeast(CcEd.MIN_ROOM_CELLS)
  }
  /** A device carried by the finger; [gx]/[gy] null while it's off the canvas (drop = unplace). */
  @Immutable data class Device(val entityId: String, val gx: Float?, val gy: Float?) : CcEdDrag
  @Immutable data class Opening(val entityId: String, val gx: Float, val gy: Float) : CcEdDrag
}

internal object CcEdGesture {
  /** What a finger landing at ([gx], [gy]) grabs in the current step/tool, or null (nothing to drag). */
  fun begin(st: CcEdState, floor: CcFloor, gx: Float, gy: Float, tolCells: Float): CcEdDrag? = when (st.step) {
    CcEdStep.ROOMS -> beginRooms(st, floor, gx, gy, tolCells)
    CcEdStep.DEVICES -> CcEdGeo.deviceAt(floor, gx, gy, tolCells)?.let { CcEdDrag.Device(it, gx, gy) }
    CcEdStep.OPENINGS -> CcEdGeo.openingAt(floor, gx, gy, tolCells)?.let { CcEdDrag.Opening(it, gx, gy) }
    CcEdStep.FOUND, CcEdStep.READY -> null
  }

  private fun beginRooms(st: CcEdState, floor: CcFloor, gx: Float, gy: Float, tol: Float): CcEdDrag? {
    val sel = floor.rooms.firstOrNull { it.id == st.selectedRoom }
    if (sel != null && CcEdGeo.onHandle(sel.rect, gx, gy, tol)) return CcEdDrag.Resize(sel.id, sel.rect, gx, gy)
    if (st.pendingRect != null) return null
    return when (st.tool) {
      CcEdTool.DRAW -> CcEdDrag.Draw(gx, gy, gx, gy)
      CcEdTool.MOVE -> CcEdGeo.roomAt(floor, gx, gy)?.let { CcEdDrag.Move(it.id, gx, gy, gx, gy) }
    }
  }

  fun update(d: CcEdDrag, gx: Float?, gy: Float?): CcEdDrag {
    if (d is CcEdDrag.Device) return d.copy(gx = gx, gy = gy)
    if (gx == null || gy == null) return d
    val x = gx.coerceIn(0f, CcPlan.GRID_W.toFloat())
    val y = gy.coerceIn(0f, CcPlan.GRID_H.toFloat())
    return when (d) {
      is CcEdDrag.Draw -> d.copy(bx = x, by = y)
      is CcEdDrag.Move -> d.copy(cx = x, cy = y)
      is CcEdDrag.Resize -> d.copy(cx = x, cy = y)
      is CcEdDrag.Opening -> d.copy(gx = x, gy = y)
      is CcEdDrag.Device -> d
    }
  }

  /** The floor as it looks mid-drag (the dragged device leaves its spot; rooms follow the finger). */
  fun preview(floor: CcFloor, d: CcEdDrag?): CcFloor = when (d) {
    null, is CcEdDrag.Draw -> floor
    is CcEdDrag.Move -> CcPlanEdit.moveRoom(floor, d.roomId, d.dx, d.dy)
    is CcEdDrag.Resize -> CcPlanEdit.resizeRoom(floor, d.roomId, d.w, d.h)
    is CcEdDrag.Device -> CcPlanEdit.removeEntity(floor, d.entityId)
    is CcEdDrag.Opening -> CcEdGeo.moveOpening(floor, d.entityId, d.gx, d.gy) ?: floor
  }

  /**
   * The floor drawn in the static layer while [active] is held: the dragged room (with its
   * openings) or opening is left out — the drag layer draws it per frame. Devices stay (dots
   * follow [liveRect] in layout); a carried device is hidden.
   */
  fun staticFloor(floor: CcFloor, active: CcEdDrag?): CcFloor = when (active) {
    is CcEdDrag.Move -> floor.copy(rooms = floor.rooms.filterNot { it.id == active.roomId }, openings = floor.openings.filterNot { it.roomId == active.roomId })
    is CcEdDrag.Resize -> floor.copy(rooms = floor.rooms.filterNot { it.id == active.roomId }, openings = floor.openings.filterNot { it.roomId == active.roomId })
    is CcEdDrag.Opening -> floor.copy(openings = floor.openings.filterNot { it.entityId == active.entityId })
    is CcEdDrag.Device -> CcPlanEdit.removeEntity(floor, active.entityId)
    null, is CcEdDrag.Draw -> floor
  }

  /** Where room [roomId] (normally at [rect]) is mid-drag: moved / resized boxes follow the finger, clamped like the commit. */
  fun liveRect(roomId: String, rect: CcRect, d: CcEdDrag?): CcRect = when {
    d is CcEdDrag.Move && d.roomId == roomId -> rect.copy(x = rect.x + d.dx, y = rect.y + d.dy).clamped()
    d is CcEdDrag.Resize && d.roomId == roomId -> rect.copy(w = d.w, h = d.h).clamped()
    else -> rect
  }

  /** The room under a carried device, read by the drag layer (no allocation). */
  fun hotRoomAt(floor: CcFloor, d: CcEdDrag?): CcRoom? {
    val dev = d as? CcEdDrag.Device ?: return null
    val x = dev.gx ?: return null
    val y = dev.gy ?: return null
    return CcEdGeo.roomAt(floor, x, y)
  }

  /** The room a carried device would land in (highlighted green). */
  fun hotRoom(floor: CcFloor, d: CcEdDrag?): String? =
      (d as? CcEdDrag.Device)?.let { if (it.gx != null && it.gy != null) CcEdGeo.roomAt(floor, it.gx, it.gy)?.id else null }

  fun commit(st: CcEdState, d: CcEdDrag, reg: CcRegistry, live: Map<String, CcEntity>, now: Long): CcEdState = when (d) {
    is CcEdDrag.Draw -> CcEd.drawn(st, d.rect, reg, now)
    is CcEdDrag.Move -> CcEd.moveRoom(st, d.roomId, d.dx, d.dy, reg, now)
    is CcEdDrag.Resize -> CcEd.resizeRoom(st, d.roomId, d.w, d.h, reg, now)
    is CcEdDrag.Device -> CcEd.drop(st, d.entityId, d.gx, d.gy, reg, live, live[d.entityId]?.name ?: d.entityId, now)
    is CcEdDrag.Opening -> CcEd.moveOpening(st, d.entityId, d.gx, d.gy, reg, now)
  }

  /** A tap: accept a dashed suggestion, else select / deselect the room under the finger. */
  fun tap(st: CcEdState, floor: CcFloor, gx: Float, gy: Float, reg: CcRegistry, now: Long): CcEdState {
    if (st.step != CcEdStep.ROOMS || st.pendingRect != null) return st
    CcEdGeo.suggestionAt(st.suggestions, gx, gy)?.let { return CcEd.acceptSuggestion(st, it.id, reg, now) }
    val room = CcEdGeo.roomAt(floor, gx, gy)?.id
    return CcEd.selectRoom(st, if (room == st.selectedRoom) null else room)
  }
}
