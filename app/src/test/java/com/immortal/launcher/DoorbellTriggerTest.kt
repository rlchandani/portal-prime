/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Event entities ring on each new event; binary sensors only on off -> on; restarts never ring. */
class DoorbellTriggerTest {
  private val settings = DoorbellConfig.Settings(trigger = "event.front_doorbell", camera = "camera.front_door",
      motion = "binary_sensor.porch_motion, binary_sensor.drive_motion")

  private fun state(value: String, context: String? = "01JCX7Q4ZK3M8N2P5R6S7T8V9W", changed: String = "2026-11-17T10:00:00+00:00") =
      JSONObject().put("state", value).put("last_changed", changed).apply {
        if (context != null) put("context", JSONObject().put("id", context))
      }

  private fun fire(entity: String, old: JSONObject?, new: JSONObject?) =
      DoorbellTriggers.fromChange(entity, old, new, settings, "Kitchen", nowMs = 5_000L)

  @Test fun eventEntityRingsOnANewTimestamp() {
    val alert = fire("event.front_doorbell", state("2026-11-17T09:00:00.000+00:00"), state("2026-11-17T10:00:00.000+00:00"))
    assertNotNull(alert)
    assertEquals(DoorbellKind.RING, alert!!.kind)
    assertEquals("camera.front_door", alert.camera)
    assertEquals("Kitchen", alert.room)
    assertEquals(5_000L, alert.detectedAtMs)
    assertEquals("01JCX7Q4ZK3M8N2P5R6S7T8V9W", alert.id)
  }

  @Test fun eventEntityFirstEverPressFromUnknownRings() {
    assertNotNull(fire("event.front_doorbell", state("unknown"), state("2026-11-17T10:00:00.000+00:00")))
  }

  @Test fun eventEntityRestoredAfterRestartDoesNotRing() {
    assertNull(fire("event.front_doorbell", state("unavailable"), state("2026-11-17T10:00:00.000+00:00")))
    assertNull(fire("event.front_doorbell", null, state("2026-11-17T10:00:00.000+00:00")))
  }

  @Test fun eventEntityAttributeOnlyUpdateOrGoingAwayDoesNotRing() {
    val same = "2026-11-17T10:00:00.000+00:00"
    assertNull(fire("event.front_doorbell", state(same), state(same)))
    assertNull(fire("event.front_doorbell", state(same), state("unavailable")))
  }

  @Test fun binarySensorRingsOnlyOffToOn() {
    val sensor = settings.copy(trigger = "binary_sensor.doorbell")
    fun go(old: String?, new: String) = DoorbellTriggers.fromChange("binary_sensor.doorbell", old?.let { state(it) }, state(new),
        sensor, "Kitchen", 0L)
    assertNotNull(go("off", "on"))
    assertNull(go("on", "off"))
    assertNull(go("on", "on"))
    assertNull(go("unavailable", "on"))
    assertNull(go("unknown", "on"))
    assertNull(go(null, "on"))
  }

  @Test fun motionSensorsAreMotionAlerts() {
    val alert = fire("binary_sensor.drive_motion", state("off"), state("on"))
    assertEquals(DoorbellKind.MOTION, alert?.kind)
    assertNull(fire("binary_sensor.drive_motion", state("on"), state("off")))
  }

  @Test fun unwatchedEntitiesNeverAlert() {
    assertNull(fire("binary_sensor.kitchen_motion", state("off"), state("on")))
    assertNull(fire("light.porch", state("off"), state("on")))
  }

  @Test fun missingContextIdFallsBackToAStableDigest() {
    val a = DoorbellTriggers.eventId("event.front_doorbell", state("x", context = null, changed = "2026-11-17T10:00:00+00:00"))
    val b = DoorbellTriggers.eventId("event.front_doorbell", state("x", context = "bad id!", changed = "2026-11-17T10:00:00+00:00"))
    assertEquals(a, b)
    assertEquals(24, a.length)
    assert(DoorbellWire.isId(a))
  }

  @Test fun watchedListsThePressThenMotion() {
    assertEquals(listOf("event.front_doorbell", "binary_sensor.porch_motion", "binary_sensor.drive_motion"), settings.watched)
    assertEquals(emptyList<String>(), DoorbellConfig.Settings(trigger = "light.porch").watched)
  }
}
