/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Shared editor test home: two floors, three areas, a few devices (one without an area). */
internal object EdFixture {
  val reg = CcRegistry(
      floors = listOf(CcHaFloor("ground", "Ground", 0), CcHaFloor("up", "Upstairs", 1)),
      areas = listOf(CcHaArea("kitchen", "Kitchen", "ground"), CcHaArea("living", "Living room", "ground"), CcHaArea("bed", "Bedroom", "up")),
      entityArea = mapOf("light.kitchen" to "kitchen", "light.living" to "living", "binary_sensor.front_door" to "living",
          "light.bed" to "bed", "binary_sensor.kitchen_window" to "kitchen"),
      entityClass = mapOf("binary_sensor.front_door" to "door", "binary_sensor.kitchen_window" to "window"),
      entities = setOf("light.kitchen", "light.living", "binary_sensor.front_door", "light.porch", "sensor.random", "light.bed",
          "binary_sensor.kitchen_window"),
  )
  val live: Map<String, CcEntity> = emptyMap()
  val kitchen = CcRoom("kitchen", "Kitchen", CcRect(1, 1, 10, 8))
  val living = CcRoom("living", "Living room", CcRect(11, 1, 12, 8))

  fun withRooms(vararg rooms: CcRoom): CcEdState {
    val st = CcEd.goStep(CcEd.start(null, reg), CcEdStep.ROOMS)
    return st.copy(plan = CcPlan(listOf(CcFloor("ground", "Ground", rooms.toList()))))
  }
}
