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

/** [CcSlices] device page and room slice: kids-mode hiding, gates, back labels, who/when lines. */
class ControlCenterSheetSlicesCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L, user: String? = null) =
      CcEntity(id, state, mapOf(*attrs), changed, user)

  private val lock = e("lock.front", "locked", "friendly_name" to "Front door")
  private val garage = e("cover.garage", "open", "device_class" to "garage")
  private val thermo = e("climate.hall", "heat", "current_humidity" to 41)
  private val lamp = e("light.lamp", "on")
  private val hall = CcRoom("hall", "Hall", CcRect(0, 0, 4, 4), devices = listOf(lock, thermo, lamp).map { CcPlaced(it.id, 0.5f, 0.5f) })
  private val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(hall))))
  private val live = CcConnection(CcPhase.LIVE)
  private val state = CcUiState(entities = listOf(lock, garage, thermo, lamp).associateBy { it.id }, plan = plan, connection = live)
  private val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)

  @Test fun kidsModeNeverOpensAHiddenDevicePage() {
    val d = CcSlices.device(state.copy(settings = kids), lock.id, listOf(CcSheet.Device(lock.id)))
    assertNull(d.entity)
    assertFalse(d.gate.enabled)
    assertEquals("Back", d.backLabel)
    assertNull(CcSlices.device(state, "light.missing", emptyList()).entity)
  }

  @Test fun backLabelsComeFromTheStackElseTheDevicesRoom() {
    assertEquals("Hall", CcSlices.device(state, lamp.id, listOf(CcSheet.Room("hall"), CcSheet.Device(lamp.id))).backLabel)
    assertEquals("Hall", CcSlices.device(state, lamp.id, listOf(CcSheet.Device("x"), CcSheet.Device(lamp.id))).backLabel)
    assertEquals("Back", CcSlices.device(state, lamp.id, listOf(CcSheet.Device(lamp.id))).backLabel)
    assertEquals("Back", CcSlices.device(state, garage.id, listOf(CcSheet.Device("x"), CcSheet.Device(garage.id))).backLabel)
  }

  @Test fun gatesFollowTheConnectionAndKidsThermostatLock() {
    assertTrue(CcSlices.device(state, lamp.id, emptyList()).gate.enabled)
    val stale = CcSlices.device(state.copy(connection = CcConnection(CcPhase.STALE)), lamp.id, emptyList())
    assertFalse(stale.gate.enabled)
    assertEquals(CcConnection(CcPhase.STALE).disabledReason, stale.gate.reason)
    val locked = CcSlices.device(state.copy(settings = kids.copy(kidsLockThermostat = true)), thermo.id, emptyList())
    assertTrue(locked.readOnly)
    assertEquals(CcGate(false, "Kids mode · look only"), locked.gate)
    val dead = CcSlices.device(state.copy(entities = state.entities + (lamp.id to lamp.copy(state = "unavailable"))), lamp.id, emptyList())
    assertFalse(dead.gate.enabled)
  }

  @Test fun climatePagesCarryRoomsAndHumidity() {
    val d = CcSlices.device(state, thermo.id, emptyList())
    assertEquals(listOf("hall"), d.rooms.map { it.roomId })
    assertEquals(41, d.humidity)
    val unplaced = CcSlices.device(state.copy(plan = null), thermo.id, emptyList())
    assertEquals(41, unplaced.humidity)
    assertTrue(unplaced.rooms.isEmpty())
  }

  @Test fun lockAndGarageWhoLines() {
    assertEquals("Home Assistant hasn't said who changed it", CcSlices.lockWhoWhen(state, lock))
    assertEquals("Locked by Sam", CcSlices.lockWhoWhen(state, lock.copy(attrs = lock.attrs + ("changed_by" to "Sam"))))
    val timed = CcSlices.lockWhoWhen(state, lock.copy(lastChanged = 1_000L))
    assertTrue(timed, timed.startsWith("Locked · "))
    assertEquals("", CcSlices.garageSince(state, garage))
    assertTrue(CcSlices.garageSince(state, garage.copy(lastChanged = 1_000L)).startsWith("Since "))
    val closed = CcSlices.garageSince(state, garage.copy(state = "closed", lastChanged = 1_000L, attrs = garage.attrs + ("changed_by" to "Mia")))
    assertTrue(closed, closed.startsWith("Closed at ") && closed.endsWith(" · Mia"))
    val g = CcSlices.device(state, garage.id, emptyList())
    assertEquals("", g.who)
    assertNull(g.cameraId)
    assertEquals("Home Assistant hasn't said who changed it", CcSlices.device(state, lock.id, emptyList()).who)
  }

  @Test fun roomSliceForAMissingRoomIsNullAndPendingIsScopedToTheRoom() {
    assertNull(CcSlices.room(state, "nope"))
    assertNull(CcSlices.room(state.copy(plan = null), "hall"))
    val r = CcSlices.room(state.copy(pending = setOf(lamp.id, garage.id), appliancesDone = mapOf(garage.id to 1L)), "hall")!!
    assertEquals(setOf(lamp.id), r.pending)
    assertTrue(r.done.isEmpty())
    assertEquals(1, r.head.lightsOn)
    assertFalse(r.kids)
    assertFalse(r.climateReadOnly)
    assertTrue(r.canControl)
  }
}
