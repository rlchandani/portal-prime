/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * People in rooms: what the plan knows about who is where, fused from the signals the household
 * already has — each Portal's presence (Meta's camera presence and touches, this Portal's own and the
 * others' over the household bus), Home Assistant motion and occupancy sensors per area, and (only when allowed, see
 * [CcPeoplePolicy]) Who's there? recognitions. Pure and immutable; JVM-tested.
 *
 * Every signal decays on its own clock: motion fades over [MOTION_FADE_MS] after the sensor clears,
 * a Portal's presence holds until that Portal says absent (or goes quiet for [PRESENCE_STALE_MS]),
 * and a recognition holds for [SIGHTING_MS] unless that room has since reported absent.
 */

/** One Portal's presence as last heard. [roomHint] is the plan room id that Portal chose for itself, if any. */
@Immutable
internal data class CcPortalPresence(
    val portalId: String,
    val portalName: String,
    val presence: Presence,
    /** When this presence began (epoch ms, re-anchored to this Portal's clock). */
    val sinceMs: Long,
    /** When it was last reported. */
    val seenMs: Long,
    val roomHint: String? = null,
    val self: Boolean = false,
)

/**
 * A person Who's there? recognised on a Portal. Only ever built for people the policy lets
 * through ([CcPeoplePolicy.shareable] on the recognising Portal, [CcPeoplePolicy.accept] here).
 * [key] is `portalId:personId` (person ids are per Portal).
 */
@Immutable
internal data class CcSighting(
    val key: String,
    val name: String,
    val colorIndex: Int,
    val portalId: String,
    val portalName: String,
    val seenMs: Long,
    val roomHint: String? = null,
)

/** Everything the fusion reads besides Home Assistant. Held in [CcUiState]. */
@Immutable
internal data class CcRoomSignals(
    val portals: List<CcPortalPresence> = emptyList(),
    val sightings: List<CcSighting> = emptyList(),
) {
  companion object {
    val NONE = CcRoomSignals()
  }
}

/** Which signals said a room is occupied. */
internal enum class CcOccSource { PRESENCE, OCCUPANCY, MOTION, MOTION_FADING, RECOGNITION }

/** A person drawn on the plan: an avatar chip (initial + colour). */
@Immutable
internal data class CcPlacedPerson(val key: String, val name: String, val initial: String, val rgb: Int)

/** One room's fused occupancy. [confidence] is 0..1 (noisy-OR of the sources). */
@Immutable
internal data class CcRoomOcc(
    val confidence: Float,
    val sources: Set<CcOccSource>,
    val people: List<CcPlacedPerson> = emptyList(),
) {
  /** Strong enough to say "someone is here" (a fading motion trail alone still shows, softer). */
  val occupied: Boolean get() = confidence >= CcOccupancyFusion.SHOW_AT
}

/** Where a named person was last seen, for Security › Who's home. */
@Immutable
internal data class CcLastSeen(val roomId: String, val roomName: String, val seenMs: Long)

/** The whole house: occupied rooms by plan room id, named people's last rooms, and when to look again. */
@Immutable
internal data class CcOccupancy(
    val rooms: Map<String, CcRoomOcc> = emptyMap(),
    /** Lower-cased first name → where they were last seen (only when names are allowed). */
    val lastSeen: Map<String, CcLastSeen> = emptyMap(),
    /** The next time a signal decays (epoch ms), so the engine republishes then; null = nothing pending. */
    val nextChangeMs: Long? = null,
) {
  fun room(id: String): CcRoomOcc? = rooms[id]

  /** The rooms of [floor] (what one floor's plan model depends on). */
  fun forFloor(floor: CcFloor): Map<String, CcRoomOcc> =
      if (rooms.isEmpty()) emptyMap() else floor.rooms.mapNotNull { r -> rooms[r.id]?.let { r.id to it } }.toMap()

  companion object {
    val NONE = CcOccupancy()
  }
}

internal object CcOccupancyFusion {
  /** Motion that cleared this long ago no longer counts. */
  const val MOTION_FADE_MS = 2 * 60_000L
  /** A recognition shows the person's chip this long (unless the room reports absent since). */
  const val SIGHTING_MS = 5 * 60_000L
  /** A Portal that hasn't spoken this long is ignored (its heartbeat is every 2 min). */
  const val PRESENCE_STALE_MS = FollowMePolicy.PRESENCE_STALE_MS
  /** Below this the room isn't drawn at all. */
  const val SHOW_AT = 0.3f

  /** Per-source evidence weights (noisy-OR combined). */
  private const val W_RECOGNITION = 0.99f
  private const val W_OCCUPANCY = 0.95f
  private const val W_PRESENCE = 0.9f
  private const val W_MOTION = 0.8f
  private const val W_FADING = 0.4f
  private const val MAX_PEOPLE_PER_ROOM = 4
  private val OCCUPANCY_CLASSES = setOf("occupancy", "presence")
  private val MOTION_CLASSES = setOf("motion") + OCCUPANCY_CLASSES

  /**
   * Fuses every signal into per-room occupancy for [plan]. [named] lets recognised people through
   * as chips (the caller has applied [CcPeoplePolicy]); [anonymous] false returns [CcOccupancy.NONE].
   */
  fun fuse(
      plan: CcPlan?,
      entities: Map<String, CcEntity>,
      registry: CcRegistry?,
      signals: CcRoomSignals,
      anonymous: Boolean,
      named: Boolean,
      now: Long,
  ): CcOccupancy {
    if (!anonymous || plan == null || plan.isEmpty) return CcOccupancy.NONE
    val rooms = plan.floors.flatMap { it.rooms }
    val evidence = HashMap<String, MutableList<Pair<CcOccSource, Float>>>()
    val next = NextChange()
    fun add(room: String, s: CcOccSource, w: Float) { evidence.getOrPut(room) { ArrayList(2) }.add(s to w) }

    sensorEvidence(rooms, entities, registry, now, next, ::add)
    val portalRooms = portalEvidence(plan, signals, now, next, ::add)
    val people = if (named) sightings(plan, signals, portalRooms, now, next) else emptyList()
    people.forEach { add(it.roomId, CcOccSource.RECOGNITION, W_RECOGNITION) }

    val byRoom = people.groupBy { it.roomId }
    val out = LinkedHashMap<String, CcRoomOcc>()
    for (r in rooms) {
      val ev = evidence[r.id] ?: continue
      val conf = noisyOr(ev.map { it.second })
      if (conf < SHOW_AT) continue
      val chips = byRoom[r.id].orEmpty().sortedBy { it.person.name.lowercase() }.take(MAX_PEOPLE_PER_ROOM).map { it.person }
      out[r.id] = CcRoomOcc(conf, ev.map { it.first }.toSet(), chips)
    }
    val last = people.associate { it.person.name.lowercase().substringBefore(' ') to CcLastSeen(it.roomId, it.roomName, it.seenMs) }
    return CcOccupancy(out, last, next.at)
  }

  /** 1 − Π(1 − wᵢ), rounded to 2 places so equal evidence always compares equal. */
  fun noisyOr(weights: List<Float>): Float {
    var miss = 1f
    for (w in weights) miss *= (1f - w.coerceIn(0f, 1f))
    return Math.round((1f - miss) * ROUND) / ROUND
  }

  private class NextChange {
    var at: Long? = null
    fun at(t: Long) { at = at?.let { minOf(it, t) } ?: t }
  }

  /** Motion and occupancy sensors placed in a room or filed under its HA area. */
  private fun sensorEvidence(rooms: List<CcRoom>, entities: Map<String, CcEntity>, registry: CcRegistry?, now: Long, next: NextChange,
                             add: (String, CcOccSource, Float) -> Unit) {
    for (r in rooms) {
      for (e in sensorsOf(r, entities, registry)) {
        if (e.unavailable) continue
        val cls = e.deviceClass ?: registry?.classOf(e.id)
        when {
          e.isOn && cls in OCCUPANCY_CLASSES -> add(r.id, CcOccSource.OCCUPANCY, W_OCCUPANCY)
          e.isOn -> add(r.id, CcOccSource.MOTION, W_MOTION)
          e.lastChanged > 0 && now - e.lastChanged in 0 until MOTION_FADE_MS -> {
            add(r.id, CcOccSource.MOTION_FADING, W_FADING)
            next.at(e.lastChanged + MOTION_FADE_MS)
          }
        }
      }
    }
  }

  fun sensorsOf(room: CcRoom, entities: Map<String, CcEntity>, registry: CcRegistry?): List<CcEntity> {
    val placed = room.devices.mapNotNull { entities[it.entityId] }
    val inArea = registry?.let { reg -> reg.areaSensors[room.id].orEmpty().mapNotNull { entities[it] } }.orEmpty()
    return (placed + inArea).distinctBy { it.id }.filter { it.domain == "binary_sensor" && isMotionClass(it, registry) }
  }

  private fun isMotionClass(e: CcEntity, registry: CcRegistry?): Boolean = (e.deviceClass ?: registry?.classOf(e.id)) in MOTION_CLASSES

  /** Each Portal's presence in its room. Returns portal id → plan room. */
  private fun portalEvidence(plan: CcPlan, signals: CcRoomSignals, now: Long, next: NextChange,
                             add: (String, CcOccSource, Float) -> Unit): Map<String, CcRoom> {
    val out = HashMap<String, CcRoom>()
    for (p in signals.portals) {
      if (now - p.seenMs > PRESENCE_STALE_MS) continue
      val room = CcPortalRooms.resolve(plan, p.portalName, p.roomHint) ?: continue
      out[p.portalId] = room
      next.at(p.seenMs + PRESENCE_STALE_MS)
      if (p.presence == Presence.PRESENT) add(room.id, CcOccSource.PRESENCE, W_PRESENCE)
    }
    return out
  }

  private class Seen(val person: CcPlacedPerson, val roomId: String, val roomName: String, val seenMs: Long)

  /** Recent recognitions, one room per person (the newest wins), dropped once their room reports absent. */
  private fun sightings(plan: CcPlan, signals: CcRoomSignals, portalRooms: Map<String, CcRoom>, now: Long, next: NextChange): List<Seen> {
    val presence = signals.portals.associateBy { it.portalId }
    val newest = HashMap<String, Seen>()
    for (s in signals.sightings) {
      val age = now - s.seenMs
      if (age !in 0 until SIGHTING_MS) continue
      val p = presence[s.portalId]
      // They left: the room went empty after they were seen.
      if (p != null && p.presence == Presence.ABSENT && p.sinceMs > s.seenMs) continue
      val room = portalRooms[s.portalId] ?: CcPortalRooms.resolve(plan, s.portalName, s.roomHint) ?: continue
      next.at(s.seenMs + SIGHTING_MS)
      val who = s.name.lowercase()
      val seen = Seen(CcPlacedPerson(s.key, s.name, initialOf(s.name), WhosThereColors.at(s.colorIndex).tint.toInt()), room.id, room.name, s.seenMs)
      if ((newest[who]?.seenMs ?: Long.MIN_VALUE) < s.seenMs) newest[who] = seen
    }
    return newest.values.toList()
  }

  fun initialOf(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

  private const val ROUND = 100f
}

/** Which plan room a Portal stands in: its chosen room id, else its name matched to a room's name or area id. */
internal object CcPortalRooms {
  private val NOISE = Regex("\\b(portal|plus|mini|tv|go|meta|hearthpane)\\b", RegexOption.IGNORE_CASE)
  private val SPACE = Regex("[^a-z0-9]+")

  fun resolve(plan: CcPlan, portalName: String, hint: String?): CcRoom? {
    hint?.takeIf { it.isNotBlank() }?.let { id -> plan.room(id)?.let { return it } }
    return byName(plan.floors.flatMap { it.rooms }, portalName)
  }

  /** Exact name / area id first, then the name without words like "Portal", then a unique room name inside it. */
  fun byName(rooms: List<CcRoom>, portalName: String): CcRoom? {
    val full = norm(portalName)
    if (full.isEmpty()) return null
    rooms.firstOrNull { norm(it.name) == full || norm(it.id) == full }?.let { return it }
    val bare = norm(NOISE.replace(portalName, " "))
    if (bare.isNotEmpty()) rooms.firstOrNull { norm(it.name) == bare || norm(it.id) == bare }?.let { return it }
    // "Leo's room Portal" → "Leo's room"; only when exactly one room's name is inside the Portal's name.
    val inside = rooms.filter { r -> norm(r.name).let { it.length >= MIN_INSIDE && " $full ".contains(" $it ") } }
    return inside.singleOrNull()
  }

  fun norm(s: String): String = SPACE.replace(s.lowercase().replace("'", ""), " ").trim()

  private const val MIN_INSIDE = 3
}
