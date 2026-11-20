/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Everything the overview canvas draws for one floor, derived purely from the state (unit
 * tested): room views, openings with their wall segments, pulses (capped), badges, light dots
 * and chips. Positions are grid cells. Rebuilt only when the floor, entities, settings or the
 * minute change; the canvas never reads entities itself.
 */
internal enum class CcMapOpeningKind { WINDOW, DOOR, GARAGE }

internal data class CcOpeningView(
    val opening: CcOpening,
    val kind: CcMapOpeningKind,
    val line: CcMapLine,
    val open: Boolean,
    /** Garage: share of the door that is open (0..1). */
    val progress: Float,
)

/** [OCCUPIED]: someone is in the room (fused occupancy); drawn in the motion blue, as soft as its confidence. */
internal enum class CcMapPulseTone { MOTION, OPEN, LEAK, OCCUPIED }

/** [strength] 0..1 scales the ring's opacity (a fading motion trail pulses softer than a Portal that sees someone). */
internal data class CcMapPulse(val x: Float, val y: Float, val radius: Float, val tone: CcMapPulseTone, val strength: Float = 1f)

/** A person Who's there? recognised in a room, as the mock's avatar chip (initial on their colour). */
internal data class CcMapPerson(val key: String, val x: Float, val y: Float, val initial: String, val rgb: Int, val name: String)

internal enum class CcMapBadgeKind { LOCKED, UNLOCKED, CAMERA, CLIMATE, APPLIANCE, APPLIANCE_DONE, LEAK }

internal data class CcMapBadge(val entityId: String, val x: Float, val y: Float, val kind: CcMapBadgeKind, val icon: String,
                            val label: String? = null, val hot: Boolean = false)

/** A light on the plan; [rgb] null = off. */
internal data class CcMapDot(val entityId: String, val x: Float, val y: Float, val rgb: Int?)

internal data class CcMapChip(val entityId: String, val x: Float, val y: Float, val text: String)

@Immutable
internal data class CcPlanModel(
    val floor: CcFloor,
    val bounds: CcMapBox,
    val views: List<CcRoomView>,
    val openings: List<CcOpeningView>,
    val pulses: List<CcMapPulse>,
    val badges: List<CcMapBadge>,
    val dots: List<CcMapDot>,
    val chips: List<CcMapChip>,
    val lightsOn: Int,
    val people: List<CcMapPerson> = emptyList(),
) {
  fun view(roomId: String): CcRoomView? = views.firstOrNull { it.room.id == roomId }

  companion object {
    /** At most this many pulses animate at once (Portal GPU budget). */
    const val MAX_PULSES = 6
    private const val MS_PER_MIN = 60_000L
    private const val MIN_PER_H = 60L
    private const val PCT = 100f
    /** Mock sizes on its 20-unit grid, in cells. */
    private const val MOTION_R = 3f
    private const val OPEN_R = 2f
    private const val GARAGE_R = 3.5f
    private const val LEAK_R = 4.5f
    private const val LOCK_RAISE = 1.3f
    private const val CORNER = 1.7f
    private const val CAMERA_CORNER = 1.4f
    private const val APPLIANCE_STEP = 2.4f
    private const val LEAK_RAISE = 2.5f
    /** The mock's people row and "someone" ripple sit below the room name (plan units 62 / 40 on its 20-unit grid). */
    private const val PEOPLE_DROP = 3.1f
    private const val PEOPLE_STEP = 2.6f
    private const val OCCUPIED_DROP = 2f

    fun build(
        floor: CcFloor,
        entities: Map<String, CcEntity>,
        settings: ControlCenterConfig.Settings,
        now: Long,
        appliancesDone: Map<String, Long> = emptyMap(),
        /** This floor's fused occupancy ([CcOccupancy.forFloor]); already filtered by [CcPeoplePolicy]. */
        occupancy: Map<String, CcRoomOcc> = emptyMap(),
    ): CcPlanModel {
      // Only kids mode hides anything; otherwise reuse the map as is (no copy of every entity).
      val visible = if (settings.kids) entities.filterValues { !CcPolicy.hidden(it, settings) } else entities
      val climates = floor.rooms.flatMap { r -> r.devices.mapNotNull { visible[it.entityId] } }.filter { it.domain == "climate" }
      val views = floor.rooms.map { CcRooms.view(it, visible, climates) }
      val openings = floor.openings.mapNotNull { o -> opening(o, floor, entities, settings) }
      val badges = views.flatMap { badges(it, visible, appliancesDone) }
      val dots = views.flatMap(::dots)
      val chips = openings.mapNotNull { chip(it, entities, now) }
      val rooms = CcPeoplePolicy.anonymous(settings)
      return CcPlanModel(floor, CcPlanGeo.bounds(floor), views, openings, pulses(views, openings, visible, if (rooms) occupancy else null),
          badges, dots, chips, views.sumOf { it.lightsOn }, if (rooms) people(views, occupancy) else emptyList())
    }

    /** Each room's recognised people in a row under its name, centred (the mock's `.pp`). */
    fun people(views: List<CcRoomView>, occupancy: Map<String, CcRoomOcc>): List<CcMapPerson> = views.flatMap { v ->
      val ps = occupancy[v.room.id]?.people.orEmpty()
      val c = CcPlanGeo.center(v.room.rect)
      ps.mapIndexed { i, p ->
        CcMapPerson(p.key, c.x - (ps.size - 1) * PEOPLE_STEP / 2f + i * PEOPLE_STEP, c.y + PEOPLE_DROP, p.initial, p.rgb, p.name)
      }
    }

    private fun opening(o: CcOpening, floor: CcFloor, entities: Map<String, CcEntity>, s: ControlCenterConfig.Settings): CcOpeningView? {
      val room = floor.rooms.firstOrNull { it.id == o.roomId } ?: return null
      val e = entities[o.entityId]
      val kind = when (e?.let(CcKind::of)) { CcKind.GARAGE -> CcMapOpeningKind.GARAGE; CcKind.DOOR, CcKind.LOCK -> CcMapOpeningKind.DOOR; else -> CcMapOpeningKind.WINDOW }
      // Kids mode hides the garage: it is drawn shut, with no chip or pulse.
      val hidden = e != null && CcPolicy.hidden(e, s)
      val open = !hidden && e != null && !e.unavailable && (if (e.domain == "lock") e.state == "open" else e.isOpen)
      val pos = e?.position?.takeIf { it > 0 }
      val progress = if (!open) 0f else (pos?.let { it / PCT } ?: 1f).coerceIn(0f, 1f)
      return CcOpeningView(o, kind, CcPlanGeo.opening(room.rect, o.side, o.at, o.span), open, progress)
    }

    /**
     * Leaks first, then open things, then people; never more than [MAX_PULSES]. With [occupancy] (the map
     * shows who's where) each occupied room without a chip gets one soft ring at its centre — motion sensors
     * feed that fusion, so they don't ring again at their own spot. Null (setting Off): motion rings as before.
     */
    fun pulses(views: List<CcRoomView>, openings: List<CcOpeningView>, entities: Map<String, CcEntity>,
               occupancy: Map<String, CcRoomOcc>? = null): List<CcMapPulse> {
      val leaks = views.filter { v -> v.markers.any { it.kind == CcKind.LEAK && it.active } }
          .map { v -> CcPlanGeo.center(v.room.rect).let { CcMapPulse(it.x, it.y, LEAK_R, CcMapPulseTone.LEAK) } }
      val open = openings.filter { it.open }.map { o ->
        val m = o.line.mid
        CcMapPulse(m.x, m.y, if (o.kind == CcMapOpeningKind.GARAGE) GARAGE_R else OPEN_R, CcMapPulseTone.OPEN)
      }
      val motion = if (occupancy != null) views.mapNotNull { v ->
        val o = occupancy[v.room.id]?.takeIf { it.occupied && it.people.isEmpty() } ?: return@mapNotNull null
        val c = CcPlanGeo.center(v.room.rect)
        CcMapPulse(c.x, c.y + OCCUPIED_DROP, MOTION_R, CcMapPulseTone.OCCUPIED, o.confidence)
      } else views.flatMap { v ->
        v.markers.filter { it.kind == CcKind.MOTION && it.active && entities[it.entityId] != null }
            .map { m -> CcPlanGeo.device(v.room.rect, m.fx, m.fy).let { CcMapPulse(it.x, it.y, MOTION_R, CcMapPulseTone.MOTION) } }
      }
      return (leaks + open + motion).take(MAX_PULSES)
    }

    private fun badges(v: CcRoomView, entities: Map<String, CcEntity>, done: Map<String, Long>): List<CcMapBadge> {
      val r = v.room.rect
      val out = ArrayList<CcMapBadge>()
      v.markers.filter { it.kind == CcKind.LOCK }.forEach { m ->
        val p = CcPlanGeo.device(r, m.fx, m.fy)
        out += CcMapBadge(m.entityId, p.x, p.y - LOCK_RAISE, if (m.active) CcMapBadgeKind.LOCKED else CcMapBadgeKind.UNLOCKED, if (m.active) "lock" else "unlock")
      }
      v.climateId?.let(entities::get)?.let { c ->
        val t = c.targetTemp ?: c.currentTemp
        out += CcMapBadge(c.id, r.x + CORNER, r.bottom - CORNER, CcMapBadgeKind.CLIMATE, "heat", t?.let { "${Math.round(it)}°" } ?: "—",
            hot = c.hvacAction == "heating")
      }
      v.markers.firstOrNull { it.kind == CcKind.CAMERA }?.let { m ->
        out += CcMapBadge(m.entityId, r.x + CAMERA_CORNER, r.y + CAMERA_CORNER, CcMapBadgeKind.CAMERA, "cam")
      }
      v.markers.filter { it.kind == CcKind.APPLIANCE && (it.active || it.entityId in done) }.forEachIndexed { i, m ->
        out += CcMapBadge(m.entityId, r.right - CORNER - i * APPLIANCE_STEP, r.y + CORNER,
            if (m.entityId in done) CcMapBadgeKind.APPLIANCE_DONE else CcMapBadgeKind.APPLIANCE, applianceIcon(m.entityId))
      }
      if (v.markers.any { it.kind == CcKind.LEAK && it.active }) {
        val c = CcPlanGeo.center(r)
        out += CcMapBadge(v.markers.first { it.kind == CcKind.LEAK && it.active }.entityId, c.x, c.y - LEAK_RAISE, CcMapBadgeKind.LEAK, "leak")
      }
      return out
    }

    fun applianceIcon(id: String): String = when {
      "dish" in id -> "dish"
      "dryer" in id -> "dryer"
      "oven" in id -> "oven"
      else -> "washer"
    }

    private fun dots(v: CcRoomView): List<CcMapDot> {
      val on = v.glows.associateBy { it.entityId }
      return v.markers.filter { it.kind == CcKind.LIGHT || (it.kind == CcKind.SWITCH && "light" in it.entityId) }.map { m ->
        val p = CcPlanGeo.device(v.room.rect, m.fx, m.fy)
        CcMapDot(m.entityId, p.x, p.y, on[m.entityId]?.rgb)
      }
    }

    private fun chip(o: CcOpeningView, entities: Map<String, CcEntity>, now: Long): CcMapChip? {
      if (o.kind != CcMapOpeningKind.GARAGE || !o.open) return null
      val e = entities[o.opening.entityId] ?: return null
      val p = CcPlanGeo.chipAnchor(o.line, o.opening.side)
      return CcMapChip(e.id, p.x, p.y, garageLabel(e, now))
    }

    /** "OPEN 17m", "OPEN 1h 5m", "OPENING", "CLOSING". */
    fun garageLabel(e: CcEntity, now: Long): String = when (e.state) {
      "opening" -> "OPENING"
      "closing" -> "CLOSING"
      else -> {
        val mins = if (e.lastChanged > 0) ((now - e.lastChanged) / MS_PER_MIN).coerceAtLeast(0) else -1
        when {
          mins < 0 -> "OPEN"
          mins < MIN_PER_H -> "OPEN ${mins}m"
          else -> "OPEN ${mins / MIN_PER_H}h ${mins % MIN_PER_H}m"
        }
      }
    }
  }
}
