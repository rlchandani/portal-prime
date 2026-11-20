/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** People in rooms: fusing presence, motion/occupancy sensors and recognitions, with each signal's decay. */
class ControlCenterOccupancyTest {
  private val now = 50_000_000L
  private val min = 60_000L

  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 10, 10), devices = listOf(CcPlaced("binary_sensor.kitchen_motion", 0.5f, 0.5f)))
  private val living = CcRoom("living_room", "Living Area", CcRect(10, 0, 12, 10))
  private val leo = CcRoom("room_3", "Leo's room", CcRect(0, 10, 8, 8))
  private val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(kitchen, living)), CcFloor("u", "Upstairs", listOf(leo))))

  private fun sensor(id: String, state: String, cls: String = "motion", changed: Long = 0L) =
      CcEntity(id, state, mapOf("device_class" to cls), changed)

  private fun portal(id: String, name: String, p: Presence, since: Long = now - min, seen: Long = now - 1_000, hint: String? = null, self: Boolean = false) =
      CcPortalPresence(id, name, p, since, seen, hint, self)

  private fun seen(who: String, portalId: String, portalName: String, at: Long = now - min, color: Int = 2, hint: String? = null) =
      CcSighting("$portalId:${who.lowercase()}id", who, color, portalId, portalName, at, hint)

  private fun fuse(entities: Map<String, CcEntity> = emptyMap(), signals: CcRoomSignals = CcRoomSignals.NONE, registry: CcRegistry? = null,
                   anonymous: Boolean = true, named: Boolean = false, at: Long = now) =
      CcOccupancyFusion.fuse(plan, entities, registry, signals, anonymous, named, at)

  @Test fun nothingKnownMeansNoRooms() {
    val o = fuse()
    assertTrue(o.rooms.isEmpty())
    assertNull(o.nextChangeMs)
  }

  @Test fun offOrNoPlanIsNone() {
    val signals = CcRoomSignals(listOf(portal("a", "Kitchen Portal", Presence.PRESENT)))
    assertSame(CcOccupancy.NONE, fuse(signals = signals, anonymous = false))
    assertSame(CcOccupancy.NONE, CcOccupancyFusion.fuse(null, emptyMap(), null, signals, true, true, now))
    assertSame(CcOccupancy.NONE, CcOccupancyFusion.fuse(CcPlan.EMPTY, emptyMap(), null, signals, true, true, now))
  }

  @Test fun motionOnIsLikelyAndClearedMotionFadesForTwoMinutes() {
    val on = fuse(mapOf("binary_sensor.kitchen_motion" to sensor("binary_sensor.kitchen_motion", "on")))
    assertEquals(0.8f, on.room("kitchen")!!.confidence, 1e-4f)
    assertEquals(setOf(CcOccSource.MOTION), on.room("kitchen")!!.sources)
    assertTrue(on.room("kitchen")!!.occupied)

    val cleared = now - 30_000
    val fading = fuse(mapOf("binary_sensor.kitchen_motion" to sensor("binary_sensor.kitchen_motion", "off", changed = cleared)))
    assertEquals(setOf(CcOccSource.MOTION_FADING), fading.room("kitchen")!!.sources)
    assertEquals(0.4f, fading.room("kitchen")!!.confidence, 1e-4f)
    assertEquals(cleared + CcOccupancyFusion.MOTION_FADE_MS, fading.nextChangeMs)

    val gone = fuse(mapOf("binary_sensor.kitchen_motion" to sensor("binary_sensor.kitchen_motion", "off", changed = now - 3 * min)))
    assertNull(gone.room("kitchen"))
  }

  @Test fun occupancySensorsAreStrongAndUnavailableSensorsCountForNothing() {
    val occ = fuse(mapOf("binary_sensor.kitchen_motion" to sensor("binary_sensor.kitchen_motion", "on", cls = "occupancy")))
    assertEquals(setOf(CcOccSource.OCCUPANCY), occ.room("kitchen")!!.sources)
    assertEquals(0.95f, occ.room("kitchen")!!.confidence, 1e-4f)
    assertNull(fuse(mapOf("binary_sensor.kitchen_motion" to sensor("binary_sensor.kitchen_motion", "unavailable"))).room("kitchen"))
  }

  @Test fun sensorsFiledUnderTheRoomsAreaCountEvenWhenNotPlaced() {
    val reg = CcRegistry(entityArea = mapOf("binary_sensor.living_mmwave" to "living_room", "light.x" to "living_room"),
        entityClass = mapOf("binary_sensor.living_mmwave" to "presence"))
    val o = fuse(mapOf("binary_sensor.living_mmwave" to CcEntity("binary_sensor.living_mmwave", "on")), registry = reg)
    assertEquals(setOf(CcOccSource.OCCUPANCY), o.room("living_room")!!.sources)
    // A door sensor in the area is not occupancy.
    val door = fuse(mapOf("binary_sensor.living_mmwave" to sensor("binary_sensor.living_mmwave", "on", cls = "door")), registry = reg)
    assertNull(door.room("living_room"))
  }

  @Test fun aPortalsPresenceHoldsUntilAbsentOrSilent() {
    val present = fuse(signals = CcRoomSignals(listOf(portal("a", "Living Area Portal", Presence.PRESENT))))
    assertEquals(setOf(CcOccSource.PRESENCE), present.room("living_room")!!.sources)
    assertEquals(0.9f, present.room("living_room")!!.confidence, 1e-4f)
    assertEquals(now - 1_000 + CcOccupancyFusion.PRESENCE_STALE_MS, present.nextChangeMs)

    assertNull(fuse(signals = CcRoomSignals(listOf(portal("a", "Living Area Portal", Presence.ABSENT)))).room("living_room"))
    assertNull(fuse(signals = CcRoomSignals(listOf(portal("a", "Living Area Portal", Presence.UNKNOWN)))).room("living_room"))
    val silent = portal("a", "Living Area Portal", Presence.PRESENT, seen = now - CcOccupancyFusion.PRESENCE_STALE_MS - 1)
    assertNull(fuse(signals = CcRoomSignals(listOf(silent))).room("living_room"))
  }

  @Test fun aPortalsChosenRoomBeatsItsName() {
    val o = fuse(signals = CcRoomSignals(listOf(portal("a", "Kitchen Portal", Presence.PRESENT, hint = "room_3"))))
    assertNull(o.room("kitchen"))
    assertTrue(o.room("room_3")!!.occupied)
    // A hint for a room this plan doesn't have falls back to the name.
    val stale = fuse(signals = CcRoomSignals(listOf(portal("a", "Kitchen Portal", Presence.PRESENT, hint = "gone"))))
    assertTrue(stale.room("kitchen")!!.occupied)
  }

  @Test fun evidenceCombinesByNoisyOr() {
    val o = fuse(mapOf("binary_sensor.kitchen_motion" to sensor("binary_sensor.kitchen_motion", "on")),
        CcRoomSignals(listOf(portal("a", "Kitchen", Presence.PRESENT))))
    assertEquals(0.98f, o.room("kitchen")!!.confidence, 1e-4f)
    assertEquals(setOf(CcOccSource.MOTION, CcOccSource.PRESENCE), o.room("kitchen")!!.sources)
    assertEquals(0f, CcOccupancyFusion.noisyOr(emptyList()), 0f)
    assertEquals(1f, CcOccupancyFusion.noisyOr(listOf(2f)), 0f)
  }

  @Test fun recognitionsShowOnlyWhenNamedAndForFiveMinutes() {
    val signals = CcRoomSignals(listOf(portal("a", "Kitchen Portal", Presence.PRESENT)), listOf(seen("Maya", "a", "Kitchen Portal")))
    val anon = fuse(signals = signals)
    assertTrue(anon.room("kitchen")!!.people.isEmpty())
    assertTrue(anon.lastSeen.isEmpty())

    val named = fuse(signals = signals, named = true)
    val chip = named.room("kitchen")!!.people.single()
    assertEquals("Maya", chip.name)
    assertEquals("M", chip.initial)
    assertEquals(WhosThereColors.at(2).tint.toInt(), chip.rgb)
    assertTrue(CcOccSource.RECOGNITION in named.room("kitchen")!!.sources)
    assertEquals(CcLastSeen("kitchen", "Kitchen", now - min), named.lastSeen["maya"])
    assertEquals(now - min + CcOccupancyFusion.SIGHTING_MS, minOf(named.nextChangeMs!!, now - min + CcOccupancyFusion.SIGHTING_MS))

    val old = CcRoomSignals(signals.portals, listOf(seen("Maya", "a", "Kitchen Portal", at = now - CcOccupancyFusion.SIGHTING_MS)))
    assertTrue(fuse(signals = old, named = true).room("kitchen")!!.people.isEmpty())
    val future = CcRoomSignals(signals.portals, listOf(seen("Maya", "a", "Kitchen Portal", at = now + min)))
    assertTrue(fuse(signals = future, named = true).room("kitchen")!!.people.isEmpty())
  }

  @Test fun aRecognitionEndsWhenItsRoomReportsEmptyAfterward() {
    val left = CcRoomSignals(listOf(portal("a", "Kitchen", Presence.ABSENT, since = now - 10_000)), listOf(seen("Maya", "a", "Kitchen", at = now - min)))
    assertNull(fuse(signals = left, named = true).room("kitchen"))
    // Absent from before the sighting doesn't cancel it.
    val earlier = CcRoomSignals(listOf(portal("a", "Kitchen", Presence.ABSENT, since = now - 2 * min)), listOf(seen("Maya", "a", "Kitchen", at = now - min)))
    assertEquals("Maya", fuse(signals = earlier, named = true).room("kitchen")!!.people.single().name)
  }

  @Test fun aPersonIsInOneRoomTheNewestSighting() {
    val signals = CcRoomSignals(emptyList(), listOf(seen("Maya", "a", "Kitchen", at = now - 3 * min), seen("Maya", "b", "Living Area", at = now - min)))
    val o = fuse(signals = signals, named = true)
    assertTrue(o.room("kitchen")?.people.isNullOrEmpty())
    assertEquals("Maya", o.room("living_room")!!.people.single().name)
    assertEquals("living_room", o.lastSeen["maya"]!!.roomId)
  }

  @Test fun sightingsFromAnUnmappedPortalAreDropped() {
    val o = fuse(signals = CcRoomSignals(emptyList(), listOf(seen("Maya", "z", "Garage Portal"))), named = true)
    assertTrue(o.rooms.isEmpty())
  }

  @Test fun atMostFourChipsARoomSortedByName() {
    val names = listOf("Zed", "Ava", "Leo", "Mia", "Dev")
    val o = fuse(signals = CcRoomSignals(emptyList(), names.map { seen(it, "a", "Kitchen") }), named = true)
    assertEquals(listOf("Ava", "Dev", "Leo", "Mia"), o.room("kitchen")!!.people.map { it.name })
  }

  @Test fun forFloorSlicesOnlyThatFloorsRooms() {
    val o = fuse(signals = CcRoomSignals(listOf(portal("a", "Kitchen", Presence.PRESENT), portal("b", "Leo's room Portal", Presence.PRESENT))))
    assertEquals(setOf("kitchen"), o.forFloor(plan.floors[0]).keys)
    assertEquals(setOf("room_3"), o.forFloor(plan.floors[1]).keys)
    assertTrue(CcOccupancy.NONE.forFloor(plan.floors[0]).isEmpty())
  }

  @Test fun equalEvidenceGivesEqualResults() {
    val s = CcRoomSignals(listOf(portal("a", "Kitchen", Presence.PRESENT)))
    assertEquals(fuse(signals = s), fuse(signals = s))
  }

  @Test fun portalNamesMatchRooms() {
    val rooms = plan.floors.flatMap { it.rooms }
    assertEquals("kitchen", CcPortalRooms.byName(rooms, "Kitchen")?.id)
    assertEquals("kitchen", CcPortalRooms.byName(rooms, "kitchen portal")?.id)
    assertEquals("living_room", CcPortalRooms.byName(rooms, "Living Area Portal+")?.id)
    assertEquals("living_room", CcPortalRooms.byName(rooms, "living_room")?.id)
    assertEquals("room_3", CcPortalRooms.byName(rooms, "Leo's room Portal Mini")?.id)
    assertEquals("room_3", CcPortalRooms.byName(rooms, "Leos Room")?.id)
    assertNull(CcPortalRooms.byName(rooms, "Garage"))
    assertNull(CcPortalRooms.byName(rooms, ""))
    assertNull(CcPortalRooms.byName(rooms, "Portal"))
    // Two rooms named inside the Portal's name: ambiguous, no guess.
    val both = listOf(CcRoom("a", "Den", CcRect(0, 0, 1, 1)), CcRoom("b", "Office", CcRect(1, 1, 1, 1)))
    assertNull(CcPortalRooms.byName(both, "Den and Office Portal"))
    assertEquals("b", CcPortalRooms.byName(both, "Upstairs Office Portal")?.id)
    assertFalse(CcPortalRooms.resolve(plan, "Nowhere", "kitchen") == null)
  }
}
