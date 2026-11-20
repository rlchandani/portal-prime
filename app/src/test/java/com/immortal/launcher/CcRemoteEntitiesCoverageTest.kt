/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcRemoteEntities]: the allow-list encoder bounds every value type an HA attribute can hold. */
class CcRemoteEntitiesCoverageTest {
  @Test fun scalarsKeepTheirTypeAndNonFiniteNumbersAreDropped() {
    val a = CcRemoteEntities.encode(CcEntity("climate.x", "heat", mapOf(
        "code_arm_required" to true, "supported_features" to 7, "battery_level" to 5_000_000_000L,
        "temperature" to 21.5, "volume_level" to 0.25f, "min_temp" to Double.NaN, "max_temp" to Double.POSITIVE_INFINITY,
        "current_temperature" to null))).getJSONObject("a")
    assertEquals(true, a.get("code_arm_required"))
    assertEquals(7, a.get("supported_features"))
    assertEquals(5_000_000_000L, a.get("battery_level"))
    assertEquals(21.5, a.getDouble("temperature"), 0.0)
    assertEquals(0.25, a.getDouble("volume_level"), 0.0)
    assertFalse(a.has("min_temp"))
    assertFalse(a.has("max_temp"))
    assertFalse(a.has("current_temperature"))
  }

  @Test fun stringsAndListsAreCappedAndNestedOrUnknownValuesDropped() {
    val long = "x".repeat(500)
    val modes = (1..30).map { "m$it" }
    val a = CcRemoteEntities.encode(CcEntity("light.x", "on", mapOf(
        "media_title" to long, "supported_color_modes" to modes,
        "rgb_color" to listOf(1, listOf(2, 3), null, 4.0, Double.NaN),
        "hvac_modes" to mapOf("k" to "v")))).getJSONObject("a")
    assertEquals(200, a.getString("media_title").length)
    assertEquals(16, a.getJSONArray("supported_color_modes").length())
    val rgb = a.getJSONArray("rgb_color")
    assertEquals(2, rgb.length())
    assertEquals(1, rgb.get(0))
    assertEquals(4.0, rgb.getDouble(1), 0.0)
    assertFalse("a map is never a control's value", a.has("hvac_modes"))
  }

  @Test fun stateAndNameAreCappedAndTheTopLevelIsFixed() {
    val o = CcRemoteEntities.encode(CcEntity("sensor.x", "s".repeat(300), mapOf("friendly_name" to "n".repeat(300)), lastChanged = 42))
    assertEquals(200, o.getString("s").length)
    assertEquals(200, o.getString("n").length)
    assertEquals(42L, o.getLong("lc"))
    assertEquals(setOf("id", "s", "n", "lc", "a"), o.keys().asSequence().toSet())
  }

  @Test fun encodeAllKeepsOrder() {
    val list = listOf(CcEntity("light.a", "on"), CcEntity("light.b", "off"))
    val arr = CcRemoteEntities.encodeAll(list)
    assertEquals(2, arr.length())
    assertEquals("light.b", arr.getJSONObject(1).getString("id"))
    assertTrue(CcRemoteEntities.encodeAll(emptyList()).length() == 0)
  }
}
