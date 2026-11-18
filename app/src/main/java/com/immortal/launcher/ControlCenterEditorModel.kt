/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The floor-plan editor's state machine (pure, unit tested). A first build walks five steps
 * (found → rooms → devices → doors & windows → ready); editing an existing plan opens on Rooms
 * with Devices and Doors & windows as tabs. Every operation returns a new [CcEdState] whose
 * [CcEdState.plan] is a fresh immutable [CcPlan] built with [CcPlanEdit].
 */
internal enum class CcEdStep(val number: Int, val title: String, val tab: String) {
  FOUND(1, "We found your home", "Found"),
  ROOMS(2, "Lay out the rooms", "Rooms"),
  DEVICES(3, "Put devices in rooms", "Devices"),
  OPENINGS(4, "Doors & windows", "Doors & windows"),
  READY(5, "Your home is ready", "Ready");

  companion object {
    const val COUNT = 5
    /** The tabs an existing plan is edited with (no wizard). */
    val TABS = listOf(ROOMS, DEVICES, OPENINGS)
  }
}

internal enum class CcEdTool { DRAW, MOVE }

internal enum class CcAreaStatus(val label: String) { PLACED("placed"), OUTDOOR("outdoor"), TO_DRAW("to draw") }

internal data class CcEdState(
    /** True for the five-step first build (the saved plan was empty). */
    val first: Boolean,
    val step: CcEdStep,
    /** The working copy; only saved when the household taps Save. */
    val plan: CcPlan,
    val floorId: String,
    val tool: CcEdTool,
    /** Dashed "Suggest a layout" boxes waiting to be accepted. */
    val suggestions: List<CcRoom> = emptyList(),
    val selectedRoom: String? = null,
    /** An area tapped in the list: the next drawn box takes its name. */
    val selectedArea: String? = null,
    /** A freshly drawn box waiting for its area (or a name). */
    val pendingRect: CcRect? = null,
    /** Bumped on every auto-place so the canvas replays the fly-in for [flyIds]. */
    val flySeq: Int = 0,
    val flyIds: Set<String> = emptySet(),
    val dirty: Boolean = false,
    /** One-line result of the last action ("Drew Kitchen", "Drop it inside a room"). */
    val message: String? = null,
) {
  /** "Step 2 of 5" while building, the tab name while editing. */
  val stepLabel: String get() = if (first) "Step ${step.number} of ${CcEdStep.COUNT}" else step.tab
}

/** Counts for the summary on the last step. */
internal data class CcPlanSummary(val rooms: Int, val devices: Int, val openings: Int)

internal object CcEd {
  /** Smallest room a drag may draw, in grid cells (smaller drags are treated as slips). */
  const val MIN_ROOM_CELLS = 2
  private const val ROOM_PREFIX = "room_"

  fun start(saved: CcPlan?, reg: CcRegistry): CcEdState {
    val plan = saved ?: CcPlan.EMPTY
    val first = plan.isEmpty
    return CcEdState(first = first, step = if (first) CcEdStep.FOUND else CcEdStep.ROOMS, plan = plan,
        floorId = floors(reg, plan).first().id, tool = if (first) CcEdTool.DRAW else CcEdTool.MOVE)
  }

  /** HA floors (or one "Home"), plus any floor the saved plan has that HA no longer lists. */
  fun floors(reg: CcRegistry, plan: CcPlan): List<CcHaFloor> {
    val ha = CcPlanEdit.floorsFor(reg)
    val extra = plan.floors.filter { f -> ha.none { it.id == f.id } }.map { CcHaFloor(it.id, it.name, null) }
    return ha + extra
  }

  fun floor(st: CcEdState, reg: CcRegistry): CcFloor = st.plan.floors.firstOrNull { it.id == st.floorId }
      ?: CcFloor(st.floorId, floors(reg, st.plan).firstOrNull { it.id == st.floorId }?.name ?: st.floorId)

  private fun withFloor(st: CcEdState, floor: CcFloor, now: Long, message: String? = st.message): CcEdState =
      st.copy(plan = CcPlanEdit.replaceFloor(st.plan, floor, now), dirty = true, message = message)

  fun setFloor(st: CcEdState, id: String): CcEdState =
      st.copy(floorId = id, suggestions = emptyList(), selectedRoom = null, selectedArea = null, pendingRect = null, message = null)

  fun setTool(st: CcEdState, tool: CcEdTool): CcEdState = st.copy(tool = tool, pendingRect = null)

  fun goStep(st: CcEdState, step: CcEdStep): CcEdState = st.copy(step = step, selectedRoom = null, selectedArea = null,
      pendingRect = null, suggestions = if (step == CcEdStep.ROOMS) st.suggestions else emptyList(), message = null)

  fun next(st: CcEdState): CcEdState = CcEdStep.entries.getOrNull(st.step.ordinal + 1)?.let { goStep(st, it) } ?: st
  fun previous(st: CcEdState): CcEdState = CcEdStep.entries.getOrNull(st.step.ordinal - 1)?.let { goStep(st, it) } ?: st

  /** Rooms must exist before devices can go anywhere; every other step can always move on. */
  fun canContinue(st: CcEdState): Boolean = st.step != CcEdStep.ROOMS || st.plan.floors.any { it.rooms.isNotEmpty() }

  // ---- rooms ----

  /** Areas of this floor not drawn yet on any floor. */
  fun unassignedAreas(st: CcEdState, reg: CcRegistry): List<CcHaArea> {
    val drawn = st.plan.floors.flatMap { f -> f.rooms.map { it.id } }.toSet()
    return CcPlanEdit.areasFor(reg, st.floorId).filter { it.id !in drawn }
  }

  fun areaStatus(st: CcEdState, area: CcHaArea): CcAreaStatus = when {
    st.plan.room(area.id) != null -> CcAreaStatus.PLACED
    CcPlanEdit.isOutdoor(area.name) -> CcAreaStatus.OUTDOOR
    else -> CcAreaStatus.TO_DRAW
  }

  fun selectArea(st: CcEdState, areaId: String): CcEdState =
      st.copy(selectedArea = if (st.selectedArea == areaId) null else areaId, tool = CcEdTool.DRAW, message = null)

  fun suggest(st: CcEdState, reg: CcRegistry): CcEdState {
    val areas = CcPlanEdit.areasFor(reg, st.floorId)
    if (areas.isEmpty()) return st.copy(message = "Suggestions need areas from Home Assistant")
    val left = unassignedAreas(st, reg).map { it.id }.toSet()
    val sugg = CcPlanEdit.suggestRooms(areas).filter { it.id in left }
    return st.copy(suggestions = sugg, message = if (sugg.isEmpty()) "Every area on this floor is drawn" else null)
  }

  fun acceptSuggestion(st: CcEdState, id: String, reg: CcRegistry, now: Long): CcEdState {
    val room = st.suggestions.firstOrNull { it.id == id } ?: return st
    return withFloor(st, CcPlanEdit.putRoom(floor(st, reg), room), now, "Added ${room.name}")
        .copy(suggestions = st.suggestions.filterNot { it.id == id })
  }

  fun acceptAll(st: CcEdState, reg: CcRegistry, now: Long): CcEdState {
    if (st.suggestions.isEmpty()) return st
    val f = st.suggestions.fold(floor(st, reg)) { acc, r -> CcPlanEdit.putRoom(acc, r) }
    return withFloor(st, f, now, "Added ${st.suggestions.size} rooms").copy(suggestions = emptyList())
  }

  /** A drag on the grid finished: the selected area takes the box at once, otherwise ask which room it is. */
  fun drawn(st: CcEdState, rect: CcRect, reg: CcRegistry, now: Long): CcEdState {
    if (rect.w < MIN_ROOM_CELLS || rect.h < MIN_ROOM_CELLS) return st.copy(message = "Drag a bigger box to draw a room")
    val area = unassignedAreas(st, reg).firstOrNull { it.id == st.selectedArea }
    return if (area != null) assign(st.copy(pendingRect = rect), area, reg, now) else st.copy(pendingRect = rect, selectedRoom = null, message = null)
  }

  fun assign(st: CcEdState, area: CcHaArea, reg: CcRegistry, now: Long): CcEdState {
    val rect = st.pendingRect ?: return st
    val room = CcRoom(area.id, area.name, rect, CcPlanEdit.isOutdoor(area.name))
    return placeNew(st, room, reg, now)
  }

  fun nameRoom(st: CcEdState, name: String, reg: CcRegistry, now: Long): CcEdState {
    val rect = st.pendingRect ?: return st
    val clean = name.trim().take(MAX_NAME)
    if (clean.isEmpty()) return st.copy(message = "Give the room a name")
    return placeNew(st, CcRoom(nextRoomId(st.plan), clean, rect, CcPlanEdit.isOutdoor(clean)), reg, now)
  }

  private fun placeNew(st: CcEdState, room: CcRoom, reg: CcRegistry, now: Long): CcEdState =
      withFloor(st, CcPlanEdit.putRoom(floor(st, reg), room), now, "Drew ${room.name}")
          .copy(pendingRect = null, selectedArea = null, selectedRoom = room.id, suggestions = st.suggestions.filterNot { it.id == room.id })

  fun cancelPending(st: CcEdState): CcEdState = st.copy(pendingRect = null)

  fun nextRoomId(plan: CcPlan): String {
    val used = plan.floors.flatMap { f -> f.rooms.mapNotNull { it.id.removePrefix(ROOM_PREFIX).takeIf { _ -> it.id.startsWith(ROOM_PREFIX) }?.toIntOrNull() } }
    return ROOM_PREFIX + ((used.maxOrNull() ?: 0) + 1)
  }

  fun selectRoom(st: CcEdState, id: String?): CcEdState = st.copy(selectedRoom = id, pendingRect = null)

  fun moveRoom(st: CcEdState, id: String, dx: Int, dy: Int, reg: CcRegistry, now: Long): CcEdState =
      if (dx == 0 && dy == 0) st.copy(selectedRoom = id)
      else withFloor(st, CcPlanEdit.moveRoom(floor(st, reg), id, dx, dy), now).copy(selectedRoom = id)

  fun resizeRoom(st: CcEdState, id: String, w: Int, h: Int, reg: CcRegistry, now: Long): CcEdState = withFloor(st,
      CcPlanEdit.resizeRoom(floor(st, reg), id, w.coerceAtLeast(MIN_ROOM_CELLS), h.coerceAtLeast(MIN_ROOM_CELLS)), now)

  fun deleteRoom(st: CcEdState, id: String, reg: CcRegistry, now: Long): CcEdState {
    val name = st.plan.room(id)?.name ?: return st
    return withFloor(st, CcPlanEdit.deleteRoom(floor(st, reg), id), now, "Removed $name").copy(selectedRoom = null)
  }

  // ---- devices & openings ----

  fun unplacedDevices(st: CcEdState, reg: CcRegistry, live: Map<String, CcEntity>): List<String> =
      CcPlanEdit.unplaced(st.plan, floor(st, reg), reg, live).filterNot { CcPlanEdit.isOpening(it, reg, live[it]) }

  fun unplacedOpenings(st: CcEdState, reg: CcRegistry, live: Map<String, CcEntity>): List<String> =
      CcPlanEdit.unplaced(st.plan, floor(st, reg), reg, live).filter { CcPlanEdit.isOpening(it, reg, live[it]) }

  /** Unplaced devices Auto-place can handle (their HA area is a room on this floor). */
  fun autoPlaceable(ids: List<String>, st: CcEdState, reg: CcRegistry): Int {
    val rooms = floor(st, reg).rooms.map { it.id }.toSet()
    return ids.count { reg.areaOf(it) in rooms }
  }

  /** Placed vs total devices (not openings) on this floor — the "33 of 38 placed" tag. */
  fun deviceProgress(st: CcEdState, reg: CcRegistry, live: Map<String, CcEntity>): Pair<Int, Int> {
    val placed = floor(st, reg).rooms.sumOf { it.devices.size }
    return placed to placed + unplacedDevices(st, reg, live).size
  }

  /** Auto-places devices by HA area (openings wait for their own step) and arms the fly-in. */
  fun autoPlaceDevices(st: CcEdState, reg: CcRegistry, live: Map<String, CcEntity>, now: Long): CcEdState {
    val before = floor(st, reg)
    val after = CcPlanEdit.autoPlace(before, reg, live).copy(openings = before.openings)
    val had = before.rooms.flatMap { r -> r.devices.map { it.entityId } }.toSet()
    val fresh = after.rooms.flatMap { r -> r.devices.map { it.entityId } }.filterNot { it in had }.toSet()
    if (fresh.isEmpty()) return st.copy(message = "Nothing left with a Home Assistant area")
    return withFloor(st, after, now, "Placed ${fresh.size} by Home Assistant area").copy(flySeq = st.flySeq + 1, flyIds = fresh)
  }

  fun snapOpenings(st: CcEdState, reg: CcRegistry, live: Map<String, CcEntity>, now: Long): CcEdState {
    val before = floor(st, reg)
    val after = CcPlanEdit.autoPlace(before, reg, live).copy(rooms = before.rooms)
    val n = after.openings.size - before.openings.size
    if (n == 0) return st.copy(message = "No door or window sensors with an area left")
    return withFloor(st, after, now, "Snapped $n to walls")
  }

  /** A device chip or dot was dropped at grid point ([gx], [gy]); null point = dropped off the canvas. */
  fun drop(st: CcEdState, entityId: String, gx: Float?, gy: Float?, reg: CcRegistry, live: Map<String, CcEntity>,
           name: String, now: Long): CcEdState {
    val f = floor(st, reg)
    val onPlan = st.plan.entityIds.contains(entityId)
    if (gx == null || gy == null) return if (onPlan) withFloor(st, CcPlanEdit.removeEntity(f, entityId), now, "$name unplaced") else st
    val placed = CcPlanEdit.dropDevice(f, entityId, gx, gy, reg, live[entityId])
        ?: return st.copy(message = "Drop it inside a room")
    val room = placed.rooms.lastOrNull { it.rect.contains(gx, gy) }?.name.orEmpty()
    return withFloor(st, placed, now, "$name → $room")
  }

  /** Moves an opening to the wall nearest the finger (of the room under it, else the closest room). */
  fun moveOpening(st: CcEdState, entityId: String, gx: Float, gy: Float, reg: CcRegistry, now: Long): CcEdState {
    val f = floor(st, reg)
    val moved = CcEdGeo.moveOpening(f, entityId, gx, gy) ?: return st
    return withFloor(st, moved, now)
  }

  fun summary(plan: CcPlan): CcPlanSummary = CcPlanSummary(
      rooms = plan.floors.sumOf { it.rooms.size },
      devices = plan.floors.sumOf { f -> f.rooms.sumOf { it.devices.size } },
      openings = plan.floors.sumOf { it.openings.size })

  /** The plan to save: only floors that have rooms, stamped [now]. */
  fun forSave(st: CcEdState, now: Long): CcPlan = st.plan.copy(floors = st.plan.floors.filter { it.rooms.isNotEmpty() }, updatedAt = now)

  /** A registry stand-in when HA withholds its registries (non-admin token): live entities, no areas. */
  fun fallbackRegistry(live: Map<String, CcEntity>): CcRegistry = CcRegistry(
      entities = live.keys,
      entityClass = live.values.mapNotNull { e -> e.deviceClass?.let { e.id to it } }.toMap())

  /** The mock icon for an entity, from its live state or (before it arrives) its registry class. */
  fun iconFor(entityId: String, reg: CcRegistry, live: CcEntity?): String {
    val e = live ?: CcEntity(entityId, "", reg.entityClass[entityId]?.let { mapOf("device_class" to it) }.orEmpty())
    return CcIcons.forKind(CcKind.of(e))
  }

  private const val MAX_NAME = 40
}

/** Step 1's "We found your home" numbers. */
internal data class CcFoundCounts(val floors: Int, val areas: Int, val withArea: Int, val withoutArea: Int)

internal object CcEdFound {
  fun counts(reg: CcRegistry, live: Map<String, CcEntity>): CcFoundCounts {
    val areaIds = reg.areas.map { it.id }.toSet()
    val worthy = reg.entities.filter { CcPlanEdit.planWorthy(it, reg, live[it]) }
    val withArea = worthy.count { reg.areaOf(it) in areaIds }
    val floors = if (reg.areas.isEmpty()) 0 else CcPlanEdit.floorsFor(reg).size
    return CcFoundCounts(floors, reg.areas.size, withArea, worthy.size - withArea)
  }
}
