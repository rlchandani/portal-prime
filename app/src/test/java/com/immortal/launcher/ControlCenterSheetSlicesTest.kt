/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterSheetSlicesTest {
  private val now = 10_000_000L + 12_345L

  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L) = CcEntity(id, state, mapOf(*attrs), changed)

  private val living = CcRoom("living", "Living room", CcRect(0, 0, 10, 10),
      devices = listOf("light.lamp", "light.ceiling", "climate.down", "lock.front", "cover.garage").map { CcPlaced(it, 0.5f, 0.5f) })
  private val hall = CcRoom("hall", "Hall", CcRect(10, 0, 4, 10), devices = listOf(CcPlaced("light.hall", 0.5f, 0.5f)))
  private val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(living, hall))))
  private val entities = listOf(
      e("light.lamp", "on", "brightness" to 255, "friendly_name" to "Lamp"),
      e("light.ceiling", "off"),
      e("light.hall", "on"),
      e("climate.down", "heat", "temperature" to 70, "current_temperature" to 69, "current_humidity" to 44, "friendly_name" to "Downstairs"),
      e("lock.front", "locked", "changed_by" to "Sam", changed = 1_000L),
      e("cover.garage", "open", "device_class" to "garage", changed = 9_960_000L - 17 * 60_000L),
      e("scene.movie", "scening", "entity_id" to listOf("light.lamp")),
  ).associateBy { it.id }
  private val state = CcUiState(entities = entities, plan = plan, now = now, connection = CcConnection(CcPhase.LIVE),
      pending = setOf("light.lamp", "light.hall"), entitiesVersion = 1)

  @Test fun roomSliceCarriesOnlyThisRoom() {
    val r = CcSlices.room(state, "living")!!
    assertEquals(CcRoomHead("69°", "44%", null, 1), r.head)
    assertEquals(setOf("light.lamp"), r.pending)
    assertEquals(listOf("scene.movie"), r.scenes.scenes.map { it.id })
    assertTrue(r.scenes.hasLights && r.scenes.canControl)
    assertEquals("climate.down", r.contents.climate?.id)
    assertEquals(true, r.climateGate?.enabled)
    assertFalse(r.climateReadOnly)
    assertEquals(10_000_000L - 10_000_000L % 60_000L, r.minuteNow)
    assertNull(CcSlices.room(state, "nowhere"))
  }

  @Test fun equalSnapshotsGiveEqualSlicesAndAChangeElsewhereKeepsTheRoomEqual() {
    val a = CcSlices.room(state, "living")
    assertEquals(a, CcSlices.room(state.copy(now = now + 1_000), "living"))
    val hallChanged = state.copy(entities = entities + ("light.hall" to e("light.hall", "off")), entitiesVersion = 2)
    assertEquals(a, CcSlices.room(hallChanged, "living"))
    val lampChanged = state.copy(entities = entities + ("light.lamp" to e("light.lamp", "off")), entitiesVersion = 2)
    assertNotEquals(a, CcSlices.room(lampChanged, "living"))
  }

  @Test fun garageAlertCanBeClosedInPlace() {
    val r = CcSlices.room(state, "living")!!
    val garage = r.alerts.firstOrNull { it.alert.entityId == "cover.garage" }
    assertNotNull(garage)
    assertEquals("cover.garage", garage!!.closable?.id)
  }

  @Test fun deviceSliceBackLabelAndKindExtras() {
    val stack = listOf(CcSheet.Room("living"), CcSheet.Device("lock.front"))
    val lock = CcSlices.device(state, "lock.front", stack)
    assertEquals("Living room", lock.backLabel)
    assertTrue(lock.who.startsWith("Locked by Sam · "))
    assertTrue(lock.askUnlock)
    val alone = CcSlices.device(state, "lock.front", listOf(CcSheet.Device("lock.front")))
    assertEquals("Back", alone.backLabel)

    val climate = CcSlices.device(state, "climate.down", stack)
    assertEquals(44, climate.humidity)
    assertEquals(listOf(CcRoomChip("living", "Living room · 69°")), climate.rooms)

    val garage = CcSlices.device(state, "cover.garage", stack)
    assertEquals("Open · 17 min", garage.status)
    assertTrue(garage.who.startsWith("Since "))
    assertNull(garage.cameraId)

    val lamp = CcSlices.device(state, "light.lamp", stack)
    assertTrue(lamp.pending)
    assertTrue(lamp.gate.enabled)
  }

  @Test fun missingOrKidsHiddenDeviceHasNoEntity() {
    assertNull(CcSlices.device(state, "light.gone", emptyList()).entity)
    val kids = state.copy(settings = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS))
    val hidden = CcSlices.device(kids, "lock.front", emptyList())
    assertNull(hidden.entity)
    assertFalse(hidden.gate.enabled)
  }

  @Test fun disconnectedGateExplainsWhy() {
    val stale = state.copy(connection = CcConnection(CcPhase.STALE))
    val d = CcSlices.device(stale, "light.lamp", emptyList())
    assertFalse(d.gate.enabled)
    assertEquals(CcConnection(CcPhase.STALE).disabledReason, d.gate.reason)
  }
}
