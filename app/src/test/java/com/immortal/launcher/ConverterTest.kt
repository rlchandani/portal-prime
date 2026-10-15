/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Offline unit-conversion math (currency needs the network and isn't covered here). */
class ConverterTest {

  @Test
  fun `length round-trips through the base unit`() {
    assertEquals(1609.344, Converter.convert("Length", "mi", "m", 1.0), 1e-6)
    assertEquals(1.0, Converter.convert("Length", "m", "mi", 1609.344), 1e-9)
    assertEquals(12.0, Converter.convert("Length", "ft", "in", 1.0), 1e-9)
    assertEquals(100.0, Converter.convert("Length", "m", "cm", 1.0), 1e-9)
  }

  @Test
  fun `mass converts kg to pounds`() {
    assertEquals(2.20462262, Converter.convert("Mass", "kg", "lb", 1.0), 1e-6)
    assertEquals(16.0, Converter.convert("Mass", "lb", "oz", 1.0), 1e-6)
  }

  @Test
  fun `temperature is affine, not a factor`() {
    assertEquals(32.0, Converter.convertTemp("°C", "°F", 0.0), 1e-9)
    assertEquals(212.0, Converter.convertTemp("°C", "°F", 100.0), 1e-9)
    assertEquals(0.0, Converter.convertTemp("°F", "°C", 32.0), 1e-9)
    assertEquals(273.15, Converter.convertTemp("°C", "K", 0.0), 1e-9)
    assertEquals(37.0, Converter.convertTemp("°F", "°C", 98.6), 1e-9)
  }

  @Test
  fun `speed converts kmh to mph`() {
    assertEquals(62.137119, Converter.convert("Speed", "km/h", "mph", 100.0), 1e-4)
  }

  @Test
  fun `factor unit identities preserve the finite double range`() {
    for ((category, units) in Converter.UNIT_CATEGORIES.filter { it.first != "Temperature" }) {
      for (unit in units.keys) {
        for (value in listOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -0.0)) {
          assertEquals("$category $unit identity for $value", value.toBits(),
              Converter.convert(category, unit, unit, value).toBits())
        }
      }
    }
  }

  @Test
  fun `large scale down does not overflow through the base unit`() {
    assertEquals(6.21371192237334e307,
        Converter.convert("Length", "km", "mi", 1e308), 1e293)
  }

  @Test
  fun `fahrenheit scale down keeps a representable result finite`() {
    assertEquals(5.555555555555556e307,
        Converter.convertTemp("°F", "°C", 1e308), 1e293)
  }

  @Test
  fun `celsius scale up avoids a spurious intermediate overflow`() {
    assertEquals(9e307,
        Converter.convertTemp("°C", "°F", 5e307), 1e293)
  }

  @Test
  fun `temperature identities preserve the finite double range`() {
    for (unit in listOf("°C", "°F", "K")) {
      for (value in listOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -0.0)) {
        assertEquals("$unit identity for $value", value.toBits(),
            Converter.convertTemp(unit, unit, value).toBits())
      }
    }
  }

  @Test
  fun `a genuinely unrepresentable scaled result still overflows`() {
    org.junit.Assert.assertTrue(
        Converter.convert("Length", "km", "mm", 1e308).isInfinite())
  }

  @Test
  fun `same unit is identity`() {
    assertEquals(5.0, Converter.convert("Volume", "L", "L", 5.0), 1e-9)
  }
}
