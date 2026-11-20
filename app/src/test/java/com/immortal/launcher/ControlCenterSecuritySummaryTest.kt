/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock-fidelity CC-4: the Security card's four summary groups and friendly sensor names. */
class ControlCenterSecuritySummaryTest {
  private val settings = ControlCenterConfig.Settings()
  private fun bs(id: String, state: String, cls: String, name: String = id, changed: Long = 0L) =
      CcEntity("binary_sensor.$id", state, mapOf("device_class" to cls, "friendly_name" to name), changed)

  private fun card(entities: List<CcEntity>, areas: Map<String, String> = emptyMap()) =
      CcSecuritySummary.card(CcSecurityList.sections(entities, settings) { areas[it] })

  // --- names ------------------------------------------------------------------------------

  @Test fun bridgeTagsAreDropped() {
    assertEquals("Back door", CcSecNames.friendly("[Ring]: Back door", null, "door"))
    assertEquals("Back door", CcSecNames.friendly("[Ring] Back door", null, "door"))
    assertEquals("[]", CcSecNames.friendly("[]", null, "door"))
  }

  @Test fun hardwareNamesBecomeTheRoom() {
    assertEquals("Office door", CcSecNames.friendly("Tilt Shock XS Sensor Window/door is open", "Office", "door"))
    assertEquals("Kitchen window", CcSecNames.friendly("ZSE40 Contact", "Kitchen", "window"))
    assertEquals("Hall motion", CcSecNames.friendly("Motion sensor", "Hall", "motion"))
    assertEquals("Office window", CcSecNames.friendly("[Ring]: Window", "Office", "window"))
  }

  @Test fun hardwareNamesWithoutARoomKeepWhatTheySay() {
    assertEquals("Tilt Shock XS Sensor Window/door", CcSecNames.friendly("Tilt Shock XS Sensor Window/door is open", null, "door"))
    assertEquals("Tilt window/door", CcSecNames.friendly("Tilt window/door", null, "door"))
  }

  @Test fun humanNamesKeepTheirWordsAndLoseTheSensorSuffix() {
    assertEquals("Pantry door", CcSecNames.friendly("Pantry Door Sensor", "Pantry", "door"))
    assertEquals("Garage door", CcSecNames.friendly("Garage door sensor", null, "garage door"))
    assertEquals("Deck door", CcSecNames.friendly("Deck Door", null, "door"))
    assertEquals("Stargate Front driver door", CcSecNames.friendly("Stargate Front driver door", "Garage", "door"))
    assertEquals("Rohit's Office", CcSecNames.friendly("Rohit's Office", "Office", "door"))
    assertEquals("Sensor", CcSecNames.friendly("Sensor", null, "door"))
    assertEquals("Alarm", CcSecNames.friendly("Alarm", null, null))
  }

  @Test fun equalNamesInOneGroupAreNumberedByEntityId() {
    val rows = listOf("b", "a", "c").map { CcSecItem("binary_sensor.$it", "Tilt Shock XS Sensor", "Office", "Open", 0L, true, false, null) }
    val out = CcSecNames.rename(rows, CcSecGroup.DOORS).associate { it.id to it.name }
    assertEquals("Office door", out["binary_sensor.a"])
    assertEquals("Office door 2", out["binary_sensor.b"])
    assertEquals("Office door 3", out["binary_sensor.c"])
  }

  @Test fun theSublineDropsTheRoomWhenTheNameAlreadySaysIt() {
    assertEquals("since 7:25 pm", CcSecNames.subline("Office window", "Office", "since 7:25 pm"))
    assertEquals("Kitchen · since 7:25 pm", CcSecNames.subline("Pantry door", "Kitchen", "since 7:25 pm"))
    assertEquals("Kitchen", CcSecNames.subline("Pantry door", "Kitchen", ""))
    assertEquals("", CcSecNames.subline("Pantry door", null, ""))
  }

  @Test fun nounsFollowTheGroup() {
    assertEquals("lock", CcSecNames.noun(CcSecGroup.DOORS, "lock.front"))
    assertEquals("door", CcSecNames.noun(CcSecGroup.DOORS, "binary_sensor.front"))
    assertEquals("garage door", CcSecNames.noun(CcSecGroup.GARAGE, "cover.g"))
    assertEquals(null, CcSecNames.noun(CcSecGroup.ALARM, "alarm_control_panel.a"))
  }

  // --- groups -----------------------------------------------------------------------------

  private val house = listOf(
      bs("front", "off", "door", "Front door"),
      bs("back", "off", "door", "Back door"),
      CcEntity("lock.front", "locked", mapOf("friendly_name" to "Front lock")),
      bs("office_window", "on", "window", "Tilt Shock XS Sensor Window/door is open", 10L),
      bs("kitchen_window", "off", "window", "Kitchen window"),
      CcEntity("cover.garage", "open", mapOf("device_class" to "garage", "friendly_name" to "Garage door"), 25L),
      bs("hall_motion", "off", "motion", "Hall motion"),
      bs("living_motion", "off", "motion", "Living motion"),
      bs("leo_motion", "off", "motion", "Leo motion"),
      bs("smoke", "off", "smoke", "Upstairs smoke"),
      bs("tamper", "off", "tamper", "Front door tamper"),
      CcEntity("alarm_control_panel.home", "disarmed", mapOf("friendly_name" to "Alarm")),
  )
  private val areas = mapOf("binary_sensor.office_window" to "Office", "binary_sensor.kitchen_window" to "Kitchen",
      "binary_sensor.hall_motion" to "Hall", "binary_sensor.living_motion" to "Living room", "binary_sensor.leo_motion" to "Leo's room")

  @Test fun theMocksFourRowsInItsOrder_withTheCalmRestBehindMore() {
    val c = card(house, areas)
    assertEquals(listOf(CcSecGroup.DOORS, CcSecGroup.WINDOWS, CcSecGroup.GARAGE, CcSecGroup.MOTION), c.primary.map { it.group })
    assertEquals(listOf(CcSecGroup.HAZARDS, CcSecGroup.TAMPER, CcSecGroup.ALARM), c.more.map { it.group })
    assertEquals(3, c.moreCount)
    assertEquals("Smoke, CO & leaks, Tamper, Alarm", CcSecuritySummary.moreLine(c))
    assertTrue("nothing starts open while the safety groups are calm", CcSecuritySummary.startOpen(c).isEmpty())
    // Every entity stays reachable.
    assertEquals(house.size, (c.primary + c.more).sumOf { it.items.size })
  }

  @Test fun locksFoldIntoDoors() {
    val doors = card(house, areas).primary.first()
    assertEquals(CcSecGroup.DOORS, doors.group)
    assertEquals(3, doors.items.size)
    assertEquals("OK", doors.tag)
    assertEquals(true, doors.ok)
    assertEquals("All 3 closed and locked", CcSecuritySummary.line(doors, 0L, { "t" }, { "d" }))
  }

  @Test fun anUnlockedLockAndAnOpenDoorReadApart() {
    val c = card(listOf(bs("pantry", "on", "door", "Pantry door"), CcEntity("lock.front", "unlocked", mapOf("friendly_name" to "Front door"))))
    val doors = c.primary.single()
    assertEquals("2 open", doors.tag)
    assertEquals(false, doors.ok)
    assertEquals("Front door unlocked, Pantry door", CcSecuritySummary.line(doors, 0L, { "t" }, { "d" }))
    val onlyLock = CcSecuritySummary.doorsAndLocks(
        CcSecurityList.sections(listOf(bs("a", "off", "door", "A")), settings) { null }.single(),
        CcSecurityList.sections(listOf(CcEntity("lock.l", "unlocked")), settings) { null }.single())
    assertEquals("1 unlocked", onlyLock?.tag)
  }

  @Test fun locksAloneOrDoorsAloneStayAsTheyAre() {
    val locks = card(listOf(CcEntity("lock.l", "locked", mapOf("friendly_name" to "Front lock")))).primary.single()
    assertEquals(CcSecGroup.LOCKS, locks.group)
    assertEquals("Locked", CcSecuritySummary.line(locks, 0L, { "t" }, { "d" }))
    assertEquals(null, CcSecuritySummary.doorsAndLocks(null, null))
  }

  @Test fun windowsNameTheRoom() {
    val windows = card(house, areas).primary[1]
    assertEquals("1 open", windows.tag)
    assertEquals("Office window", CcSecuritySummary.line(windows, 0L, { "t" }, { "d" }))
    assertEquals("Office window", windows.items.first().name)
  }

  @Test fun oneOpenGarageSaysSinceWhen() {
    val garage = card(house, areas).primary[2]
    val day = 24 * 3_600_000L
    assertEquals("Open since 7:25 pm", CcSecuritySummary.line(garage, 26L, { "7:25 pm" }, { "d${it / day}" }))
    assertEquals("1 open", garage.tag)
    val undated = card(listOf(CcEntity("cover.g", "open", mapOf("device_class" to "garage", "friendly_name" to "Garage door")))).primary.single()
    assertEquals("Garage door", CcSecuritySummary.line(undated, 26L, { "t" }, { "d" }))
  }

  @Test fun quietMotionCountsRoomsAndHasNoTag() {
    val motion = card(house, areas).primary[3]
    assertEquals("3 rooms · ignored in Home & Night", CcSecuritySummary.line(motion, 0L, { "t" }, { "d" }))
    assertFalse(CcSecuritySummary.showsTag(motion))
    val noRooms = card(listOf(bs("m", "off", "motion"))).primary.single()
    assertEquals("1 sensor · ignored in Home & Night", CcSecuritySummary.line(noRooms, 0L, { "t" }, { "d" }))
    val active = card(listOf(bs("m", "on", "motion"), bs("n", "off", "motion"))).primary.single()
    assertTrue(CcSecuritySummary.showsTag(active))
    assertEquals("Active in 1 of 2 · ignored in Home & Night", CcSecuritySummary.line(active, 0L, { "t" }, { "d" }))
  }

  @Test fun smokeLeadsTheCardAndStartsOpen() {
    val c = card(house.map { if (it.id == "binary_sensor.smoke") it.copy(state = "on") else it }, areas)
    assertEquals(CcSecGroup.HAZARDS, c.primary.first().group)
    assertEquals(setOf(CcSecGroup.HAZARDS), CcSecuritySummary.startOpen(c))
    assertFalse(c.more.any { it.group == CcSecGroup.HAZARDS })
  }

  @Test fun anEmptyHouseHasAnEmptyCard() {
    assertTrue(card(emptyList()).isEmpty)
  }
}
