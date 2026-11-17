/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The household alert carries an age (not a clock), validates every field and fits one datagram. */
class DoorbellWireTest {
  private val alert = DoorbellAlert("01JCX7Q4ZK3M8N2P5R6S7T8V9W", DoorbellKind.RING, "event.front_doorbell",
      "camera.front_door", "Living Area", detectedAtMs = 10_000L)

  @Test fun roundTripReanchorsToTheReceiversClock() {
    val body = DoorbellWire.encode(alert, nowMs = 12_000L)
    assertEquals(2_000L, body.getLong("age"))
    val back = DoorbellWire.decode(JSONObject(body.toString()), nowMs = 500_000L)
    assertEquals(alert.copy(detectedAtMs = 498_000L), back)
  }

  @Test fun rejectsBadFields() {
    val good = DoorbellWire.encode(alert, 10_000L)
    fun with(key: String, value: Any) = JSONObject(good.toString()).put(key, value)
    assertNull(DoorbellWire.decode(with("i", "no spaces!"), 0L))
    assertNull(DoorbellWire.decode(with("k", "knock"), 0L))
    assertNull(DoorbellWire.decode(with("e", "light.porch"), 0L))
    assertNull(DoorbellWire.decode(with("c", "light.porch"), 0L))
    assertNull(DoorbellWire.decode(with("age", DoorbellWire.MAX_AGE_MS + 1), 0L))
    assertNull(DoorbellWire.decode(with("age", -1), 0L))
    assertNull(DoorbellWire.decode(with("r", ""), 0L))
    assertNull(DoorbellWire.decode(JSONObject(good.toString()).apply { remove("c") }, 0L))
  }

  @Test fun motionNeedsABinarySensorAndCameraMayBeBlank() {
    val motion = alert.copy(kind = DoorbellKind.MOTION, entityId = "binary_sensor.porch_motion", camera = "")
    assertEquals(motion.copy(detectedAtMs = 0L), DoorbellWire.decode(DoorbellWire.encode(motion, 10_000L), 0L))
    val wrong = DoorbellWire.encode(motion.copy(entityId = "event.front_doorbell"), 10_000L)
    assertNull(DoorbellWire.decode(wrong, 0L))
  }

  @Test fun fitsOneSignedHouseholdMessage() {
    val long = alert.copy(entityId = "event." + "a".repeat(128), camera = "camera." + "b".repeat(128), room = "R".repeat(80))
    val key = ByteArray(32) { it.toByte() }
    val wire = HouseholdMessage.encodeOrNull(HouseholdMessage(DoorbellWire.ALERT, "portal-abc", 0L, "0123456789ab",
        DoorbellWire.encode(long, 10_000L)), key)
    assertTrue(wire != null)
  }

  @Test fun routeEventCarriesAgeAndNoEntityIds() {
    val json = DoorbellRoute.eventJson(alert, now = 15_000L)
    assertEquals(5_000L, json.getLong("ageMs"))
    assertEquals("Someone’s at the door", json.getString("title"))
    assertEquals("Front door", json.getString("camera"))
    assertEquals("ring", json.getString("kind"))
    assertEquals("Living Area", json.getString("room"))
  }
}
