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

class ControlCenterModalLogicTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  private val entities = listOf(
      e("light.a", "on"), e("light.b", "off"), e("light.c", "on"),
      e("cover.blinds", "open"), e("lock.front", "locked"), e("media_player.tv", "playing"),
      e("cover.garage_door", "open", "device_class" to "garage", "friendly_name" to "Garage door"),
      e("binary_sensor.office_window", "on", "device_class" to "window", "friendly_name" to "Office window"),
      e("lock.back", "unlocked", "friendly_name" to "Back door"),
      e("binary_sensor.front_door", "off", "device_class" to "door"),
  ).associateBy { it.id }

  @Test fun sceneLinesGroupMembersByKind() {
    val lines = CcModalLogic.sceneLines(listOf("light.a", "light.b", "light.c", "cover.blinds", "lock.front", "media_player.tv", "input_boolean.x"), entities)
    assertEquals(listOf("Sets 3 lights", "Sets 1 TV or speaker", "Sets 1 blind", "Sets 1 lock", "Sets 1 other device"), lines.map { it.text })
    assertEquals("bulb", lines.first().icon)
    assertTrue(CcModalLogic.sceneLines(emptyList(), entities).isEmpty())
  }

  @Test fun roomsLineJoinsNamesAndShortensLongLists() {
    val rooms = (1..5).map { CcRoom("r$it", "Room $it", CcRect(0, 0, 1, 1)) }
    val plan = CcPlan(listOf(CcFloor("g", "G", rooms)))
    assertEquals("Room 1", CcModalLogic.roomsLine(plan, listOf("r1")))
    assertEquals("Room 1, Room 2 and Room 3", CcModalLogic.roomsLine(plan, listOf("r1", "r2", "r3")))
    assertEquals("Room 1, Room 2, Room 3 and 2 more", CcModalLogic.roomsLine(plan, rooms.map { it.id }))
    assertNull(CcModalLogic.roomsLine(null, listOf("r1")))
  }

  @Test fun openThingsPutTheGarageFirst() {
    val open = CcModalLogic.openThings(entities)
    assertEquals(listOf("cover.garage_door", "lock.back", "binary_sensor.office_window"), open.map { it.id })
    assertEquals("Garage door is open", CcModalLogic.openLine(open[0]).text)
    assertEquals("Back door is unlocked", CcModalLogic.openLine(open[1]).text)
    assertEquals("window", CcModalLogic.openLine(open[2]).icon)
    assertEquals("door", CcModalLogic.openLine(e("binary_sensor.d", "on", "device_class" to "door")).icon)
  }

  @Test fun onlyMovingCoversCanBeClosedForYou() {
    assertTrue(CcModalLogic.closable(entities.getValue("cover.garage_door")))
    assertFalse(CcModalLogic.closable(e("cover.garage_door", "closing", "device_class" to "garage")))
    assertFalse(CcModalLogic.closable(e("binary_sensor.garage", "on", "device_class" to "garage_door")))
  }

  @Test fun labels() {
    assertEquals("Away", CcModalLogic.modeName(CcAlarmMode.AWAY))
    assertEquals("Everything is closed and locked.", CcModalLogic.openCountLine(0))
    assertEquals("1 thing is open:", CcModalLogic.openCountLine(1))
    assertEquals("3 things are open:", CcModalLogic.openCountLine(3))
  }
}
