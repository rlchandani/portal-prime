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
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcSecurityList]: grouping, attention, inline actions, tags and summaries for every group. */
class ControlCenterSecurityListCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))
  private val full = ControlCenterConfig.Settings()
  private fun sections(vararg es: CcEntity, s: ControlCenterConfig.Settings = full) = CcSecurityList.sections(es.toList(), s) { null }
  private fun section(group: CcSecGroup, vararg es: CcEntity) = sections(*es).single { it.group == group }

  @Test fun groupsByDomainAndClass() {
    assertEquals(CcSecGroup.GARAGE, CcSecurityList.groupOf(e("cover.g", "open", "device_class" to "gate")))
    assertNull(CcSecurityList.groupOf(e("cover.blind", "open", "device_class" to "blind")))
    assertEquals(CcSecGroup.GARAGE, CcSecurityList.groupOf(e("binary_sensor.g", "on", "device_class" to "garage_door")))
    assertEquals(CcSecGroup.DOORS, CcSecurityList.groupOf(e("binary_sensor.d", "on", "device_class" to "opening")))
    assertEquals(CcSecGroup.HAZARDS, CcSecurityList.groupOf(e("binary_sensor.s", "on", "device_class" to "safety")))
    assertEquals(CcSecGroup.MOTION, CcSecurityList.groupOf(e("binary_sensor.p", "on", "device_class" to "presence")))
    assertEquals(CcSecGroup.TAMPER, CcSecurityList.groupOf(e("binary_sensor.t", "on", "device_class" to "tamper")))
    assertNull(CcSecurityList.groupOf(e("binary_sensor.x", "on")))
    assertNull(CcSecurityList.groupOf(e("light.x", "on")))
  }

  @Test fun inlineActionsOnlyForAnOpenGarageCoverOrAnUnlockedLock() {
    assertEquals(CcSecAction.CLOSE, CcSecurityList.item(e("cover.g", "opening", "device_class" to "garage"), CcSecGroup.GARAGE, null).action)
    assertNull(CcSecurityList.item(e("cover.g", "closing", "device_class" to "garage"), CcSecGroup.GARAGE, null).action)
    assertNull(CcSecurityList.item(e("binary_sensor.g", "on", "device_class" to "garage"), CcSecGroup.GARAGE, null).action)
    assertEquals(CcSecAction.LOCK, CcSecurityList.item(e("lock.l", "open"), CcSecGroup.LOCKS, null).action)
    assertNull(CcSecurityList.item(e("lock.l", "jammed"), CcSecGroup.LOCKS, null).action)
    val gone = CcSecurityList.item(e("lock.l", "unavailable"), CcSecGroup.LOCKS, "Hall")
    assertNull(gone.action)
    assertFalse(gone.attention)
    assertTrue(gone.unavailable)
    assertEquals("Hall", gone.area)
  }

  @Test fun attentionPerGroup() {
    assertTrue(CcSecurityList.item(e("lock.l", "jammed"), CcSecGroup.LOCKS, null).attention)
    assertTrue(CcSecurityList.item(e("alarm_control_panel.a", "pending"), CcSecGroup.ALARM, null).attention)
    assertFalse(CcSecurityList.item(e("alarm_control_panel.a", "armed_home"), CcSecGroup.ALARM, null).attention)
    assertTrue(CcSecurityList.item(e("binary_sensor.t", "on", "device_class" to "tamper"), CcSecGroup.TAMPER, null).attention)
    assertFalse(CcSecurityList.item(e("binary_sensor.m", "on", "device_class" to "motion"), CcSecGroup.MOTION, null).attention)
  }

  @Test fun kidsModeDropsHiddenGroups() {
    val kids = full.copy(portalMode = ControlCenterConfig.MODE_KIDS)
    val out = sections(e("lock.l", "unlocked"), e("binary_sensor.d", "on", "device_class" to "door"), s = kids)
    assertEquals(listOf(CcSecGroup.DOORS), out.map { it.group })
  }

  @Test fun tagsAndSummariesForTheAlarm() {
    val a = section(CcSecGroup.ALARM, e("alarm_control_panel.a", "triggered"))
    assertEquals(false, a.ok)
    assertEquals(a.items.first().state, a.tag)
    val gone = section(CcSecGroup.ALARM, e("alarm_control_panel.a", "unavailable"))
    assertNull("an unavailable panel is neither OK nor a problem", gone.ok)
    assertEquals("1 unavailable", gone.summary)
    val quiet = section(CcSecGroup.ALARM, e("alarm_control_panel.a", "disarmed"))
    assertEquals(true, quiet.ok)
    assertEquals(quiet.items.first().state, quiet.summary)
  }

  @Test fun tagsAndSummariesForLocksHazardsAndTamper() {
    val locks = section(CcSecGroup.LOCKS, e("lock.a", "unlocked", "friendly_name" to "Back"), e("lock.b", "unlocked", "friendly_name" to "Annex"))
    assertEquals("2 unlocked", locks.tag)
    assertEquals("Annex, Back", locks.summary)
    assertEquals("All 2 locked", section(CcSecGroup.LOCKS, e("lock.a", "locked"), e("lock.b", "locked")).summary)
    assertEquals("Locked", section(CcSecGroup.LOCKS, e("lock.a", "locked")).summary)

    val one = section(CcSecGroup.HAZARDS, e("binary_sensor.s", "on", "device_class" to "smoke"))
    assertEquals("1 alert", one.tag)
    val two = section(CcSecGroup.HAZARDS, e("binary_sensor.s", "on", "device_class" to "smoke"), e("binary_sensor.g", "on", "device_class" to "gas"))
    assertEquals("2 alerts", two.tag)
    assertEquals("Clear", section(CcSecGroup.HAZARDS, e("binary_sensor.s", "off", "device_class" to "smoke")).summary)
    assertEquals("All 2 clear", section(CcSecGroup.HAZARDS, e("binary_sensor.s", "off", "device_class" to "smoke"),
        e("binary_sensor.w", "off", "device_class" to "moisture")).summary)

    assertEquals("1 tampered", section(CcSecGroup.TAMPER, e("binary_sensor.t", "on", "device_class" to "tamper")).tag)
    assertEquals("No tampering", section(CcSecGroup.TAMPER, e("binary_sensor.t", "off", "device_class" to "tamper")).summary)
    assertEquals("All 2 OK", section(CcSecGroup.TAMPER, e("binary_sensor.t", "off", "device_class" to "tamper"),
        e("binary_sensor.u", "off", "device_class" to "tamper")).summary)
  }

  @Test fun motionCountsActiveSensorsAndNeverAsksForAttention() {
    val m = section(CcSecGroup.MOTION, e("binary_sensor.m", "on", "device_class" to "motion"),
        e("binary_sensor.o", "on", "device_class" to "occupancy"), e("binary_sensor.q", "off", "device_class" to "motion"))
    assertEquals("2 active", m.tag)
    assertNull(m.ok)
    assertEquals("Active in 2 of 3 · ignored in Home & Night", m.summary)
    val q = section(CcSecGroup.MOTION, e("binary_sensor.q", "off", "device_class" to "motion"))
    assertEquals("Quiet", q.tag)
    assertEquals("No motion right now · 1 sensors", q.summary)
  }

  @Test fun doorsWithUnavailableRowsSortLastAndAreCounted() {
    val d = section(CcSecGroup.DOORS, e("binary_sensor.a", "unavailable", "device_class" to "door", "friendly_name" to "A"),
        e("binary_sensor.b", "off", "device_class" to "door", "friendly_name" to "B"),
        e("binary_sensor.c", "off", "device_class" to "door", "friendly_name" to "C"))
    assertEquals(listOf("binary_sensor.b", "binary_sensor.c", "binary_sensor.a"), d.items.map { it.id })
    assertEquals("OK", d.tag)
    assertEquals("All 2 closed · 1 unavailable", d.summary)
    assertEquals("Closed", section(CcSecGroup.WINDOWS, e("binary_sensor.w", "off", "device_class" to "window")).summary)
    val open = section(CcSecGroup.WINDOWS, e("binary_sensor.w", "on", "device_class" to "window", "friendly_name" to "W"),
        e("binary_sensor.x", "unavailable", "device_class" to "window"))
    assertEquals("1 open", open.tag)
    assertEquals("W · 1 unavailable", open.summary)
  }

  @Test fun problemSectionsComeFirst() {
    val out = sections(e("lock.a", "locked"), e("binary_sensor.w", "on", "device_class" to "window"))
    assertEquals(listOf(CcSecGroup.WINDOWS, CcSecGroup.LOCKS), out.map { it.group })
  }

  @Test fun stateWordsForEveryGroup() {
    fun w(e: CcEntity, g: CcSecGroup) = CcSecurityList.stateWord(e, g)
    assertEquals("Unknown", w(e("lock.a", "unknown"), CcSecGroup.LOCKS))
    assertEquals("Unknown", w(e("lock.a", ""), CcSecGroup.LOCKS))
    assertEquals("Jammed", w(e("lock.a", "jammed"), CcSecGroup.LOCKS))
    assertEquals("Opening", w(e("cover.g", "opening", "device_class" to "garage"), CcSecGroup.GARAGE))
    assertEquals("Closed", w(e("binary_sensor.d", "off", "device_class" to "door"), CcSecGroup.DOORS))
    assertEquals("CO detected", w(e("binary_sensor.c", "on", "device_class" to "carbon_monoxide"), CcSecGroup.HAZARDS))
    assertEquals("Gas detected", w(e("binary_sensor.c", "on", "device_class" to "gas"), CcSecGroup.HAZARDS))
    assertEquals("Wet", w(e("binary_sensor.c", "on", "device_class" to "moisture"), CcSecGroup.HAZARDS))
    assertEquals("Unsafe", w(e("binary_sensor.c", "on", "device_class" to "safety"), CcSecGroup.HAZARDS))
    assertEquals("Smoke", w(e("binary_sensor.c", "on", "device_class" to "smoke"), CcSecGroup.HAZARDS))
    assertEquals("Occupied", w(e("binary_sensor.o", "on", "device_class" to "occupancy"), CcSecGroup.MOTION))
    assertEquals("Clear", w(e("binary_sensor.o", "off", "device_class" to "occupancy"), CcSecGroup.MOTION))
    assertEquals("Tampered", w(e("binary_sensor.t", "on"), CcSecGroup.TAMPER))
    assertEquals("OK", w(e("binary_sensor.t", "off"), CcSecGroup.TAMPER))
  }

  @Test fun sinceLines() {
    val time = { t: Long -> "T$t" }
    val day = { t: Long -> "D${t / 100}" }
    assertEquals("", CcSecurityList.sinceLine(0, 10, time, day))
    assertEquals("since T150", CcSecurityList.sinceLine(150, 160, time, day))
    assertEquals("since D1 T150", CcSecurityList.sinceLine(150, 250, time, day))
    assertEquals("since same T150", CcSecurityList.sinceLine(150, 150 + 24L * 60 * 60 * 1000, time) { "same" })
  }
}
