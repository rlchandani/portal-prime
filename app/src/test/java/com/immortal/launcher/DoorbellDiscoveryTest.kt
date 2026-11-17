/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The picker lists doorbell-like buttons first, every camera, and only motion-like sensors. */
class DoorbellDiscoveryTest {
  private fun entity(id: String, name: String, deviceClass: String? = null) = JSONObject().put("entity_id", id)
      .put("state", "off").put("attributes", JSONObject().put("friendly_name", name).apply { deviceClass?.let { put("device_class", it) } })

  @Test fun groupsAndOrders() {
    val body = JSONArray(listOf(
        entity("binary_sensor.window", "Window", "window"),
        entity("event.front_doorbell", "Front doorbell", "doorbell"),
        entity("binary_sensor.porch_motion", "Porch motion", "motion"),
        entity("camera.front_door", "Front door"),
        entity("camera.garden", "Garden"),
        entity("light.porch", "Porch light"),
    )).toString()
    val choices = DoorbellDiscovery.parse(body)!!
    assertEquals(listOf("event.front_doorbell", "binary_sensor.porch_motion", "binary_sensor.window"),
        choices.buttons.map { it.entityId })
    assertEquals(listOf("camera.front_door", "camera.garden"), choices.cameras.map { it.entityId })
    assertEquals(listOf("binary_sensor.porch_motion"), choices.motion.map { it.entityId })
  }

  @Test fun badBodyIsNull() {
    assertNull(DoorbellDiscovery.parse("<html>"))
  }
}
