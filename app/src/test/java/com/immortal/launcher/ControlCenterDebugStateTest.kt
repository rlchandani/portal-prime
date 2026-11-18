/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterDebugStateTest {
  private val reg = CcRegistry(
      floors = listOf(CcHaFloor("g", "Ground", 0), CcHaFloor("u", "Upstairs", 1)),
      areas = listOf(CcHaArea("kitchen", "Kitchen", "g"), CcHaArea("hall", "Hall", "g"), CcHaArea("bed", "Bedroom", "u")),
      entityArea = mapOf("light.k" to "kitchen", "light.b" to "bed"),
      entities = setOf("light.k", "light.b"))

  @Test fun testPlan_usesSuggestedRoomsAndAutoPlacement() {
    val plan = CcTestPlan.build(reg, emptyMap(), 9L)
    assertEquals(listOf("g", "u"), plan.floors.map { it.id })
    assertEquals(listOf("kitchen", "hall"), plan.floors[0].rooms.map { it.id })
    assertEquals("kitchen", plan.roomOf("light.k")!!.id)
    assertEquals("bed", plan.roomOf("light.b")!!.id)
    assertEquals(9L, plan.updatedAt)
    assertTrue(CcTestPlan.build(CcRegistry(), emptyMap(), 0L).isEmpty)
  }

  @Test fun label_showsOnlyActiveSwitches() {
    assertNull(CcTestPlan.label(false, false))
    assertEquals("Dry run", CcTestPlan.label(true, false))
    assertEquals("Dry run · Test plan", CcTestPlan.label(true, true))
  }

  @Test fun uiState_equalityIgnoresMapInstanceAndSubMinuteClock() {
    val a = CcUiState(entities = mapOf("light.a" to CcEntity("light.a", "on")), entitiesVersion = 3, now = 60_000L)
    val b = a.copy(entities = HashMap(a.entities), now = 119_999L)
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, b.copy(entitiesVersion = 4))
    assertNotEquals(a, b.copy(now = 120_000L))
    assertNotEquals(a, b.copy(debug = "Dry run"))
    assertNotEquals(a, b.copy(toast = CcToast(1, "x")))
  }
}
