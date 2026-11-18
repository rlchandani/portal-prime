/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterSecurityLogicTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L) = CcEntity(id, state, mapOf(*attrs), changed)
  private val panel = e("alarm_control_panel.house", "disarmed", "supported_features" to 7)
  private val time: (Long) -> String = { "t$it" }

  @Test fun exitDelay_countsDownAndClamps() {
    assertEquals(30, CcSecurity.exitRemaining(1_000L, 30, 1_000L))
    assertEquals(21, CcSecurity.exitRemaining(1_000L, 30, 10_000L))
    assertEquals(21, CcSecurity.exitRemaining(1_000L, 30, 10_500L))   // rounds up: 20.5 s left shows 21
    assertEquals(0, CcSecurity.exitRemaining(1_000L, 30, 60_000L))
    assertEquals(30, CcSecurity.exitRemaining(10_000L, 30, 1_000L))  // clock skew never shows more than the delay
    assertEquals(0, CcSecurity.exitRemaining(0L, 30, 1_000L))
    assertEquals(0, CcSecurity.exitRemaining(1_000L, 0, 1_000L))
    assertEquals(0.7f, CcSecurity.exitFraction(21, 30), 1e-6f)
    assertEquals(0f, CcSecurity.exitFraction(5, 0), 0f)
  }

  @Test fun ringToneTitleAndSubtitle() {
    assertEquals(CcRingTone.OFF, CcSecurity.tone("disarmed"))
    assertEquals(CcRingTone.ARMED, CcSecurity.tone("armed_night"))
    assertEquals(CcRingTone.ARMING, CcSecurity.tone("arming"))
    assertEquals(CcRingTone.ALERT, CcSecurity.tone("triggered"))
    val arming = e("alarm_control_panel.house", "arming", "next_state" to "armed_away")
    assertEquals("Arming Away", CcSecurity.title(arming))
    assertEquals("Arming", CcSecurity.title(e("alarm_control_panel.x", "arming")))
    assertEquals("Leave now · 21s to go", CcSecurity.subtitle(arming, 21))
    assertEquals("Armed · Night", CcSecurity.title(e("alarm_control_panel.x", "armed_night")))
    assertEquals("Disarming needs the household PIN", CcSecurity.subtitle(e("alarm_control_panel.x", "armed_home"), 0))
    assertTrue(CcSecurity.subtitle(panel, 0).startsWith("Arm with one tap"))
  }

  @Test fun currentMode_readsStateOrNextState() {
    assertEquals(CcAlarmMode.DISARM, CcSecurity.currentMode(panel))
    assertEquals(CcAlarmMode.NIGHT, CcSecurity.currentMode(e("a.b", "armed_night")))
    assertEquals(CcAlarmMode.AWAY, CcSecurity.currentMode(e("a.b", "arming", "next_state" to "armed_away")))
    assertNull(CcSecurity.currentMode(e("a.b", "triggered")))
  }

  @Test fun tap_armsThroughTheCheckAndDisarmsThroughThePin() {
    assertEquals(CcModal.ArmCheck(panel.id, CcAlarmMode.AWAY), CcSecurity.tap(panel, CcAlarmMode.AWAY))
    assertNull(CcSecurity.tap(panel, CcAlarmMode.DISARM))
    val armed = panel.copy(state = "armed_away")
    val pin = CcSecurity.tap(armed, CcAlarmMode.DISARM) as CcModal.Pin
    assertEquals(CcAlarmMode.DISARM, pin.mode)
    assertEquals("Alarm is armed · away", pin.subtitle)
    assertNull(CcSecurity.tap(armed, CcAlarmMode.AWAY))
    assertEquals(CcModal.ArmCheck(panel.id, CcAlarmMode.HOME), CcSecurity.tap(armed, CcAlarmMode.HOME))
    assertEquals("leave", CcSecurity.modeIcon(CcAlarmMode.AWAY))
    assertEquals("Night", CcSecurity.modeWord(CcAlarmMode.NIGHT))
  }

  @Test fun checklist_derivesOnlyRowsTheHouseHas() {
    val ents = listOf(
        e("lock.front", "locked"), e("binary_sensor.back_door", "off", "device_class" to "door"),
        e("binary_sensor.office_window", "on", "device_class" to "window", "friendly_name" to "Office window"),
        e("cover.garage", "open", "device_class" to "garage", changed = 7L),
        e("valve.water_main", "open", "friendly_name" to "Water main"),
        e("binary_sensor.hall_motion", "on", "device_class" to "motion"),
        e("binary_sensor.dead", "unavailable", "device_class" to "window"))
    val rows = CcSecurity.checklist(ents, time).associateBy { it.title }
    assertEquals(listOf("Doors & locks", "Windows", "Garage", "Water", "Motion"), CcSecurity.checklist(ents, time).map { it.title })
    assertEquals(true, rows.getValue("Doors & locks").ok)
    assertEquals("All closed and locked", rows.getValue("Doors & locks").detail)
    assertEquals("1 open", rows.getValue("Windows").tag)
    assertEquals("Office window", rows.getValue("Windows").detail)
    assertEquals("Open since t7", rows.getValue("Garage").detail)
    assertEquals(true, rows.getValue("Water").ok)
    assertNull(rows.getValue("Motion").ok)
    assertEquals(2, CcSecurity.openCount(ents))
    assertTrue(CcSecurity.checklist(emptyList(), time).isEmpty())
  }

  @Test fun checklist_flagsUnlockedAndShutValve() {
    val rows = CcSecurity.checklist(listOf(e("lock.front", "unlocked", "friendly_name" to "Front door"),
        e("switch.water_main_valve", "off", "friendly_name" to "Main valve"), e("cover.garage", "closed", "device_class" to "garage")), time)
        .associateBy { it.title }
    assertEquals("1 unlocked", rows.getValue("Doors & locks").tag)
    assertEquals("Front door unlocked", rows.getValue("Doors & locks").detail)
    assertEquals("Off", rows.getValue("Water").tag)
    assertEquals("Closed", rows.getValue("Garage").detail)
  }

  @Test fun notes() {
    assertEquals("Ready to arm.", CcSecurity.note(0))
    assertEquals("1 thing open — you'll be asked before arming.", CcSecurity.note(1))
    assertEquals("2 things open — you'll be asked before arming.", CcSecurity.note(2))
  }

  @Test fun people_whereAndHow() {
    assertEquals("Home · Phone · Wi-Fi", CcSecurity.personLine(e("person.alex", "home", "source" to "device_tracker.alex_phone_wifi")))
    assertEquals("Away", CcSecurity.personLine(e("person.sam", "not_home")))
    assertEquals("Work · GPS", CcSecurity.personLine(e("person.sam", "work", "source" to "device_tracker.sam_gps")))
    assertEquals("Home · Room sensor", CcSecurity.personLine(e("person.mia", "home", "source" to "sensor.mia_bermuda_area")))
    assertNull(CcSecurity.sourceLabel("device_tracker.x"))
    assertNull(CcSecurity.sourceLabel(null))
    assertTrue(CcSecurity.isHome(e("person.a", "home")))
  }

  @Test fun side_slicesPeopleChecksAndOpen() {
    val people = listOf(e("person.alex", "home", "friendly_name" to "Alex"), e("person.sam", "not_home", "friendly_name" to "Sam"))
    val ents = people + e("binary_sensor.office_window", "on", "device_class" to "window")
    val side = CcSecurity.side(people, ents, time)
    assertEquals(listOf(CcPersonItem("person.alex", "Alex", 0, true, "Home"), CcPersonItem("person.sam", "Sam", 1, false, "Away")), side.people)
    assertEquals(1, side.open)
    assertEquals(listOf("Windows"), side.checks.map { it.title })
    assertEquals(side, CcSecurity.side(people, ents, time))
  }
}
