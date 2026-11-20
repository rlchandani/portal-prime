/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How occupancy reaches the plan model, the model cache, the room sheet and the phone. */
class ControlCenterPlanPeopleTest {
  private val now = 20_000_000L
  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 10, 10), devices = listOf(CcPlaced("binary_sensor.k_motion", 0.2f, 0.2f)))
  private val hall = CcRoom("hall", "Hall", CcRect(10, 0, 6, 10))
  private val floor = CcFloor("g", "Ground", listOf(kitchen, hall))
  private val motion = mapOf("binary_sensor.k_motion" to CcEntity("binary_sensor.k_motion", "on", mapOf("device_class" to "motion")))
  private val maya = CcPlacedPerson("a:maya", "Maya", "M", 0xFFE8834A.toInt())
  private val sam = CcPlacedPerson("a:sam", "Sam", "S", 0xFF2F9C95.toInt())

  private fun build(mode: String, occ: Map<String, CcRoomOcc>, ents: Map<String, CcEntity> = motion) =
      CcPlanModel.build(floor, ents, ControlCenterConfig.Settings(mapPeople = mode), now, occupancy = occ)

  @Test fun offKeepsTheSensorMotionRingsAndNoPeople() {
    val m = build(ControlCenterConfig.MAP_OFF, mapOf("kitchen" to CcRoomOcc(0.8f, setOf(CcOccSource.MOTION), listOf(maya))))
    assertEquals(listOf(CcMapPulseTone.MOTION), m.pulses.map { it.tone })
    assertTrue(m.people.isEmpty())
  }

  @Test fun someoneRingsOncePerOccupiedRoomAtItsCentreAsSoftAsItsConfidence() {
    val m = build(ControlCenterConfig.MAP_SOMEONE, mapOf("kitchen" to CcRoomOcc(0.8f, setOf(CcOccSource.MOTION)),
        "hall" to CcRoomOcc(0.4f, setOf(CcOccSource.MOTION_FADING))))
    assertEquals(listOf(CcMapPulseTone.OCCUPIED, CcMapPulseTone.OCCUPIED), m.pulses.map { it.tone })
    val k = m.pulses.first()
    assertEquals(5f, k.x, 1e-4f)
    assertEquals(7f, k.y, 1e-4f)
    assertEquals(0.8f, k.strength, 1e-4f)
    assertEquals(0.4f, m.pulses[1].strength, 1e-4f)
  }

  @Test fun nothingOccupiedMeansNothingAnimates() {
    val m = build(ControlCenterConfig.MAP_SOMEONE, emptyMap(), ents = emptyMap())
    assertTrue(m.pulses.isEmpty())
    assertTrue(m.people.isEmpty())
    // A room below the show threshold doesn't ring either.
    assertTrue(build(ControlCenterConfig.MAP_SOMEONE, mapOf("hall" to CcRoomOcc(0.2f, setOf(CcOccSource.MOTION_FADING))), emptyMap()).pulses.isEmpty())
  }

  @Test fun recognisedPeopleGetChipsInsteadOfARing() {
    val m = build(ControlCenterConfig.MAP_NAMES, mapOf("kitchen" to CcRoomOcc(0.99f, setOf(CcOccSource.RECOGNITION), listOf(maya, sam))))
    assertTrue(m.pulses.none { it.tone == CcMapPulseTone.OCCUPIED })
    assertEquals(listOf("M", "S"), m.people.map { it.initial })
    assertEquals(3.7f, m.people[0].x, 1e-4f)
    assertEquals(6.3f, m.people[1].x, 1e-4f)
    assertEquals(8.1f, m.people[0].y, 1e-4f)
  }

  @Test fun thePlanDescriptionSaysWhoIsWhereAsDrawn() {
    val named = build(ControlCenterConfig.MAP_NAMES, mapOf("kitchen" to CcRoomOcc(0.99f, setOf(CcOccSource.RECOGNITION), listOf(maya, sam)),
        "hall" to CcRoomOcc(0.9f, setOf(CcOccSource.PRESENCE))))
    assertEquals("Maya and Sam here", CcSpeech.who(named, kitchen))
    assertEquals("someone here", CcSpeech.who(named, hall))
    assertEquals("", CcSpeech.who(build(ControlCenterConfig.MAP_OFF, emptyMap()), hall))
  }

  @Test fun aChangeInOccupancyMakesANewModelKey() {
    val deps = CcPlanDeps.of(CcPlanDeps.ids(floor), motion)
    val s = ControlCenterConfig.Settings()
    val a = CcModelKey(floor, deps, s, 1L, emptyMap(), mapOf("kitchen" to CcRoomOcc(0.8f, setOf(CcOccSource.MOTION))))
    val b = CcModelKey(floor, deps, s, 1L, emptyMap(), mapOf("kitchen" to CcRoomOcc(0.8f, setOf(CcOccSource.MOTION))))
    assertEquals(a, b)
    assertNotEquals(a, b.copy(occupancy = emptyMap()))
  }

  @Test fun theModelCacheRebuildsWhenOccupancyChanges() {
    val cache = CcPlanModelCache()
    val base = CcUiState(entities = motion, plan = CcPlan(listOf(floor)), now = now)
    val first = cache.of(base).getValue("g")
    assertTrue(first === cache.of(base).getValue("g"))
    val occ = CcOccupancy(mapOf("hall" to CcRoomOcc(0.9f, setOf(CcOccSource.PRESENCE))))
    val second = cache.of(base.copy(occupancy = occ)).getValue("g")
    assertFalse(first === second)
    assertTrue(second.pulses.any { it.tone == CcMapPulseTone.OCCUPIED && it.x == 13f })
  }

  @Test fun theRoomSheetSaysSomeoneOrTheirNames() {
    val s = CcUiState(entities = motion, plan = CcPlan(listOf(floor)), now = now)
    assertEquals("Motion now" to true, CcSheetLogic.presence(s, kitchen))
    val someone = s.copy(occupancy = CcOccupancy(mapOf("hall" to CcRoomOcc(0.9f, setOf(CcOccSource.PRESENCE)))))
    assertEquals("Someone here" to true, CcSheetLogic.presence(someone, hall))
    val named = s.copy(occupancy = CcOccupancy(mapOf("kitchen" to CcRoomOcc(0.99f, setOf(CcOccSource.RECOGNITION), listOf(maya, sam)))))
    assertEquals("Maya & Sam here" to true, CcSheetLogic.presence(named, kitchen))
    // Motion alone keeps its own words.
    val moving = s.copy(occupancy = CcOccupancy(mapOf("kitchen" to CcRoomOcc(0.8f, setOf(CcOccSource.MOTION)))))
    assertEquals("Motion now" to true, CcSheetLogic.presence(moving, kitchen))
  }

  @Test fun thePhoneGetsOccupancyAndOnlyAllowedNames() {
    val s = CcUiState(entities = motion, plan = CcPlan(listOf(floor)), now = now,
        occupancy = CcOccupancy(mapOf("kitchen" to CcRoomOcc(0.99f, setOf(CcOccSource.RECOGNITION), listOf(maya)),
            "hall" to CcRoomOcc(0.2f, setOf(CcOccSource.MOTION_FADING)))))
    val rooms = CcRemoteSlices.roomsJson(s)
    val byId = (0 until rooms.length()).map { rooms.getJSONObject(it) }.associateBy { it.getString("id") }
    val k = byId.getValue("kitchen")
    assertEquals("0.99", k.get("occ").toString())
    val p: JSONObject = k.getJSONArray("ppl").getJSONObject(0)
    assertEquals("Maya", p.getString("n"))
    assertEquals("M", p.getString("i"))
    assertEquals(0xE8834A, p.getInt("c"))
    assertFalse("below the threshold: nothing", byId.getValue("hall").has("occ"))
    val none = CcRemoteSlices.roomsJson(s.copy(occupancy = CcOccupancy.NONE))
    assertFalse(none.getJSONObject(0).has("occ"))
    assertFalse(none.getJSONObject(0).has("ppl"))
  }
}
