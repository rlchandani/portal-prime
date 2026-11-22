/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaClimateTest {
  @Test fun `dial fraction maps Celsius 10-30 and Fahrenheit 50-86`() {
    assertEquals(0.55f, HaClimate.fraction(21f), 1e-4f)
    assertEquals(0f, HaClimate.fraction(5f), 1e-4f)
    assertEquals(0.5f, HaClimate.fraction(68f), 1e-4f)
    assertEquals(1f, HaClimate.fraction(95f), 1e-4f)
  }

  @Test fun `steps are HA's own, else half a degree C or one F, and snap`() {
    assertEquals(0.5f, HaClimate.step(haDetail("climate.c"), 21f))
    assertEquals(1f, HaClimate.step(haDetail("climate.c"), 70f))
    assertEquals(0.1f, HaClimate.step(haDetail("climate.c", step = 0.1f), 21f))
    assertEquals(21.5f, HaClimate.stepped(21f, 0.5f, 0.5f))
    assertEquals(21f, HaClimate.stepped(21.2f, 0f, 0.5f))
    assertEquals(69f, HaClimate.stepped(70f, -1f, 1f))
  }

  @Test fun `labels`() {
    assertEquals("21°", HaClimate.degrees(21f))
    assertEquals("20.5°", HaClimate.degrees(20.5f))
    assertEquals("21.0°", HaClimate.stepperText(21f))
    assertEquals("70°", HaClimate.stepperText(70f))
    assertEquals("Heat · target 21°", HaClimate.statusLine(haDetail("climate.c", state = "heat"), 21f))
    assertEquals("Off", HaClimate.statusLine(haDetail("climate.c", state = "off"), 21f))
    assertEquals("Auto · target 22°", HaClimate.statusLine(haDetail("climate.c", state = "heat_cool"), 22f))
    assertEquals("Heating", HaClimate.actionChip(haDetail("climate.c", action = "heating")))
    assertEquals("Cooling", HaClimate.actionChip(haDetail("climate.c", action = "cooling")))
    assertNull(HaClimate.actionChip(haDetail("climate.c", action = "idle")))
  }

  @Test fun `target falls back to current, then 21`() {
    assertEquals(22f, HaClimate.target(haDetail("climate.c", target = 22f, current = 19f)))
    assertEquals(19f, HaClimate.target(haDetail("climate.c", current = 19f)))
    assertEquals(21f, HaClimate.target(haDetail("climate.c")))
  }
}
