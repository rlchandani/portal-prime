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

class AuroraKpValidityTest {
  @Test fun objectPositiveInfinityIsNotAReading() = rejects(objectRow("Infinity"))
  @Test fun objectNegativeInfinityIsNotAReading() = rejects(objectRow("-Infinity"))
  @Test fun objectNegativeKpIsNotAReading() = rejects(objectRow(-0.5))
  @Test fun objectAboveNineIsNotAReading() = rejects(objectRow(9.5))
  @Test fun arrayNaNIsNotAReading() = rejects(arrayRow("NaN"))
  @Test fun arrayPositiveInfinityIsNotAReading() = rejects(arrayRow("Infinity"))
  @Test fun arrayNegativeInfinityIsNotAReading() = rejects(arrayRow("-Infinity"))
  @Test fun arrayNegativeKpIsNotAReading() = rejects(arrayRow(-0.5))
  @Test fun arrayAboveNineIsNotAReading() = rejects(arrayRow(9.5))

  @Test fun zeroAndNineRemainValidInBothShapes() {
    for (value in listOf(0.0, 9.0)) {
      assertEquals(value, read(objectRow(value))!!, 0.0)
      assertEquals(value, read(arrayRow(value))!!, 0.0)
    }
  }

  @Test fun fractionalNumbersAndLegacyStringsRemainValid() {
    for (value in listOf<Any>(3.67, "3.67")) {
      assertEquals(3.67, read(objectRow(value))!!, 0.0)
      assertEquals(3.67, read(arrayRow(value))!!, 0.0)
    }
  }

  @Test fun lowercaseFallbackRemainsValid() {
    assertEquals(3.67, read(JSONObject().put("kp", "3.67"))!!, 0.0)
    assertEquals(3.67, read(JSONObject().put("Kp", JSONObject.NULL).put("kp", 3.67))!!, 0.0)
  }

  @Test fun absentAndMalformedValuesRemainMissing() {
    for (row in listOf(JSONObject(), JSONArray(), objectRow("unknown"),
        arrayRow("unknown"), objectRow(JSONObject.NULL), "unrelated")) {
      assertNull(read(row))
    }
  }

  private fun objectRow(value: Any) = JSONObject().put("Kp", value)
  private fun arrayRow(value: Any) = JSONArray().put("generated-time").put(value)
  private fun rejects(row: Any) = assertNull(read(row))

  private fun read(row: Any): Double? {
    val method = Aurora::class.java.getDeclaredMethod("kpOf", Any::class.java)
    method.isAccessible = true
    return method.invoke(Aurora, row) as Double?
  }
}
