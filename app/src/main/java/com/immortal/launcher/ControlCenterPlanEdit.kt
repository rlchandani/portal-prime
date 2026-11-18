/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure plan-editor operations (unit tested): the floors HA knows, suggested room boxes per area,
 * auto-placing devices by HA area, dragging leftovers into a room, drawing/moving rooms on the snap
 * grid and snapping door/window sensors to walls. Every function returns a new plan.
 */
internal object CcPlanEdit {
  private const val MARGIN = 1
  private const val ASPECT = 1.5
  private const val DEVICE_COLS = 4
  private val OUTDOOR_HINTS = listOf("driveway", "patio", "garden", "yard", "porch", "terrace", "deck", "balcony", "outside", "front")
  private val SENSOR_CLASSES = setOf("temperature", "humidity", "power")
  private val BINARY_CLASSES = CcEntity.OPENING_CLASSES + setOf("motion", "occupancy", "presence", "moisture", "smoke", "gas", "carbon_monoxide")
  private val PLAN_DOMAINS = setOf("light", "switch", "fan", "lock", "cover", "climate", "media_player", "camera", "valve")
  const val UNASSIGNED_FLOOR = "home"

  /** HA floors, or one "Home" floor when the install has none (HA < 2024.4 or never set up). */
  fun floorsFor(reg: CcRegistry): List<CcHaFloor> {
    val used = reg.floors.filter { f -> reg.areas.any { it.floorId == f.id } }
    val orphans = reg.areas.any { a -> a.floorId == null || reg.floors.none { it.id == a.floorId } }
    return when {
      used.isEmpty() -> listOf(CcHaFloor(UNASSIGNED_FLOOR, "Home", 0))
      orphans -> used + CcHaFloor(UNASSIGNED_FLOOR, "Other rooms", null)
      else -> used
    }
  }

  fun areasFor(reg: CcRegistry, floorId: String): List<CcHaArea> {
    val known = reg.floors.map { it.id }.toSet()
    return if (floorId == UNASSIGNED_FLOOR) reg.areas.filter { it.floorId == null || it.floorId !in known }
    else reg.areas.filter { it.floorId == floorId }
  }

  /** Does this entity belong on a plan (a control or a sensor that changes how a room looks)? */
  fun planWorthy(entityId: String, reg: CcRegistry, live: CcEntity?): Boolean {
    val domain = entityId.substringBefore('.')
    val cls = live?.deviceClass ?: reg.entityClass[entityId]
    return when (domain) {
      in PLAN_DOMAINS -> true
      "binary_sensor" -> cls in BINARY_CLASSES
      "sensor" -> cls in SENSOR_CLASSES
      else -> false
    }
  }

  /** Suggested room boxes for a floor's areas: a balanced grid that fills the canvas. */
  fun suggestRooms(areas: List<CcHaArea>): List<CcRoom> {
    if (areas.isEmpty()) return emptyList()
    val cols = max(1, ceil(sqrt(areas.size * ASPECT)).toInt()).coerceAtMost(areas.size)
    val rows = ceil(areas.size / cols.toDouble()).toInt()
    val usableW = CcPlan.GRID_W - 2 * MARGIN
    val usableH = CcPlan.GRID_H - 2 * MARGIN
    val cellH = usableH / rows
    return areas.chunked(cols).flatMapIndexed { row, chunk ->
      val cellW = usableW / chunk.size
      chunk.mapIndexed { col, a ->
        val w = if (col == chunk.lastIndex) usableW - cellW * col else cellW
        val h = if (row == rows - 1) usableH - cellH * row else cellH
        CcRoom(a.id, a.name, CcRect(MARGIN + col * cellW, MARGIN + row * cellH, w, h).clamped(), isOutdoor(a.name))
      }
    }
  }

  fun isOutdoor(name: String): Boolean = name.lowercase().let { n -> OUTDOOR_HINTS.any { it in n } && !n.contains("room") }

  /** Places every plan-worthy entity whose HA area is a room on this floor; openings go to walls. */
  fun autoPlace(floor: CcFloor, reg: CcRegistry, live: Map<String, CcEntity>): CcFloor {
    val placed = floor.rooms.flatMap { r -> r.devices.map { it.entityId } }.toSet() + floor.openings.map { it.entityId }
    val byRoom = reg.entities.filter { it !in placed && planWorthy(it, reg, live[it]) }
        .groupBy { reg.areaOf(it) }.filterKeys { k -> floor.rooms.any { it.id == k } }
    var openings = floor.openings
    val rooms = floor.rooms.map { room ->
      val ids = byRoom[room.id].orEmpty().sorted()
      val (walls, inside) = ids.partition { isOpening(it, reg, live[it]) }
      walls.forEachIndexed { i, id -> openings = openings + snapOpening(id, room, i, reg, live[id]) }
      room.copy(devices = room.devices + layout(inside, room.devices.size))
    }
    return floor.copy(rooms = rooms, openings = openings)
  }

  fun isOpening(entityId: String, reg: CcRegistry, live: CcEntity?): Boolean {
    val domain = entityId.substringBefore('.')
    val cls = live?.deviceClass ?: reg.entityClass[entityId]
    return (domain == "binary_sensor" && cls in CcEntity.OPENING_CLASSES) || (domain == "cover" && (cls == "garage" || cls == "gate"))
  }

  private fun snapOpening(id: String, room: CcRoom, index: Int, reg: CcRegistry, live: CcEntity?): CcOpening {
    val cls = live?.deviceClass ?: reg.entityClass[id]
    val side = when (cls) {
      "garage_door", "garage", "gate" -> CcSide.W
      "window" -> if (index % 2 == 0) CcSide.N else CcSide.E
      else -> CcSide.S
    }
    val at = (0.3f + 0.4f * (index % 2)).coerceIn(0.15f, 0.85f)
    return CcOpening(id, room.id, side, at, CcPlanJson.OPENING_SPAN.toFloat())
  }

  private fun layout(ids: List<String>, startIndex: Int): List<CcPlaced> = ids.mapIndexed { i, id ->
    val n = startIndex + i
    val col = n % DEVICE_COLS
    val row = n / DEVICE_COLS
    CcPlaced(id, (0.2f + col * 0.2f).coerceIn(0.1f, 0.9f), (0.25f + row * 0.25f).coerceIn(0.1f, 0.9f))
  }

  /** Plan-worthy entities on this floor's areas (or with no area) that are on no room yet. */
  fun unplaced(plan: CcPlan, floor: CcFloor, reg: CcRegistry, live: Map<String, CcEntity>): List<String> {
    val onPlan = plan.entityIds
    val floorAreas = floor.rooms.map { it.id }.toSet()
    return reg.entities.filter { id ->
      id !in onPlan && planWorthy(id, reg, live[id]) && (reg.areaOf(id) == null || reg.areaOf(id) in floorAreas)
    }.sorted()
  }

  /** Drops [entityId] at plan cell ([gx], [gy]) on the room under it; null when no room is there. */
  fun dropDevice(floor: CcFloor, entityId: String, gx: Float, gy: Float, reg: CcRegistry, live: CcEntity?): CcFloor? {
    val room = floor.rooms.lastOrNull { it.rect.contains(gx, gy) } ?: return null
    val cleaned = removeEntity(floor, entityId)
    if (isOpening(entityId, reg, live)) return cleaned.copy(openings = cleaned.openings + nearestWall(entityId, room, gx, gy))
    val fx = ((gx - room.rect.x) / room.rect.w).coerceIn(0.05f, 0.95f)
    val fy = ((gy - room.rect.y) / room.rect.h).coerceIn(0.05f, 0.95f)
    return cleaned.copy(rooms = cleaned.rooms.map { if (it.id == room.id) it.copy(devices = it.devices + CcPlaced(entityId, fx, fy)) else it })
  }

  /** The wall of [room] closest to the drop point, with the opening centred where it was dropped. */
  fun nearestWall(entityId: String, room: CcRoom, gx: Float, gy: Float): CcOpening {
    val r = room.rect
    val d = mapOf(CcSide.N to gy - r.y, CcSide.S to r.bottom - gy, CcSide.W to gx - r.x, CcSide.E to r.right - gx)
    val side = d.minByOrNull { it.value }!!.key
    val at = when (side) { CcSide.N, CcSide.S -> (gx - r.x) / r.w; CcSide.E, CcSide.W -> (gy - r.y) / r.h }
    return CcOpening(entityId, room.id, side, at.coerceIn(0.1f, 0.9f), CcPlanJson.OPENING_SPAN.toFloat())
  }

  fun removeEntity(floor: CcFloor, entityId: String): CcFloor = floor.copy(
      rooms = floor.rooms.map { r -> r.copy(devices = r.devices.filterNot { it.entityId == entityId }) },
      openings = floor.openings.filterNot { it.entityId == entityId })

  /** A rectangle from two grid points (any drag direction), snapped to whole cells. */
  fun rectFrom(ax: Float, ay: Float, bx: Float, by: Float): CcRect {
    val x0 = minOf(ax, bx).roundToInt(); val y0 = minOf(ay, by).roundToInt()
    val x1 = maxOf(ax, bx).roundToInt(); val y1 = maxOf(ay, by).roundToInt()
    return CcRect(x0, y0, max(1, x1 - x0), max(1, y1 - y0)).clamped()
  }

  fun putRoom(floor: CcFloor, room: CcRoom): CcFloor =
      floor.copy(rooms = floor.rooms.filterNot { it.id == room.id } + room.copy(rect = room.rect.clamped()))

  fun moveRoom(floor: CcFloor, roomId: String, dx: Int, dy: Int): CcFloor = floor.copy(rooms = floor.rooms.map {
    if (it.id == roomId) it.copy(rect = it.rect.copy(x = it.rect.x + dx, y = it.rect.y + dy).clamped()) else it
  })

  fun resizeRoom(floor: CcFloor, roomId: String, w: Int, h: Int): CcFloor = floor.copy(rooms = floor.rooms.map {
    if (it.id == roomId) it.copy(rect = it.rect.copy(w = w, h = h).clamped()) else it
  })

  fun deleteRoom(floor: CcFloor, roomId: String): CcFloor =
      floor.copy(rooms = floor.rooms.filterNot { it.id == roomId }, openings = floor.openings.filterNot { it.roomId == roomId })

  fun replaceFloor(plan: CcPlan, floor: CcFloor, now: Long): CcPlan {
    val exists = plan.floors.any { it.id == floor.id }
    return plan.copy(floors = if (exists) plan.floors.map { if (it.id == floor.id) floor else it } else plan.floors + floor, updatedAt = now)
  }

  /** Placed vs total plan-worthy entities in this floor's areas (the editor's "33 of 38 placed"). */
  fun progress(plan: CcPlan, floor: CcFloor, reg: CcRegistry, live: Map<String, CcEntity>): Pair<Int, Int> {
    val left = unplaced(plan, floor, reg, live).size
    val placed = floor.rooms.sumOf { it.devices.size } + floor.openings.size
    return placed to placed + left
  }
}
