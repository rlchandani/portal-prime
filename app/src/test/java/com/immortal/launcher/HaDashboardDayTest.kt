/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.HaDashboardDay.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaDashboardDayTest {

  @Test fun `greeting follows the device clock - 05-11 morning, 12-16 afternoon, 17-20 evening, 21-04 night`() {
    val expected = (0..23).map { h ->
      when (h) { in 5..11 -> Part.MORNING; in 12..16 -> Part.AFTERNOON; in 17..20 -> Part.EVENING; else -> Part.NIGHT }
    }
    assertEquals(expected, (0..23).map { HaDashboardDay.partOf(it) })
    assertEquals("Good morning", HaDashboardDay.partOf(5).greeting)
    assertEquals("Good night", HaDashboardDay.partOf(4).greeting)
    assertEquals("Good evening", HaDashboardDay.partOf(20).greeting)
    assertEquals("Good afternoon", HaDashboardDay.partOf(12).greeting)
  }

  private val ha = listOf("Morning", "Evening lights", "Night", "Movie", "Away", "Focus")
      .mapIndexed { i, n -> HaDashboardApi.Scene("scene.s$i", n) }

  @Test fun `this part of the day's scene leads and is the only Now`() {
    val evening = HaDashboardDay.scenes(ha, Part.EVENING)
    assertEquals("Evening lights", evening.first().name)
    assertTrue(evening.first().now)
    assertEquals(1, evening.count { it.now })
    assertEquals(listOf("Morning", "Night", "Movie", "Away", "Focus"), evening.drop(1).map { it.name })
    assertEquals("Away", HaDashboardDay.scenes(ha, Part.AFTERNOON).first().name)
    assertEquals("Night", HaDashboardDay.scenes(ha, Part.NIGHT).first().name)
  }

  @Test fun `two scenes for the same part keep one Now`() {
    val two = listOf(HaDashboardApi.Scene("scene.a", "Movie"), HaDashboardApi.Scene("scene.b", "Night"),
        HaDashboardApi.Scene("scene.c", "Goodnight kids"))
    val chips = HaDashboardDay.scenes(two, Part.NIGHT)
    assertEquals(listOf("Night", "Movie", "Goodnight kids"), chips.map { it.name })
    assertEquals(listOf(true, false, false), chips.map { it.now })
  }

  @Test fun `no HA scenes falls back to the four fixed ones`() {
    val chips = HaDashboardDay.scenes(emptyList(), Part.MORNING)
    assertEquals(listOf("scene.evening", "scene.night", "scene.movie", "scene.leave"), chips.map { it.id })
    assertFalse(chips.any { it.now })
  }

  @Test fun `scene glyphs and parts come from the name`() {
    assertEquals("sunrise", HaDashboardDay.sceneIcon("Good Morning"))
    assertEquals("sunset", HaDashboardDay.sceneIcon("Evening"))
    assertEquals("moon", HaDashboardDay.sceneIcon("Night"))
    assertEquals("film", HaDashboardDay.sceneIcon("Movie time"))
    assertEquals("leave", HaDashboardDay.sceneIcon("Away"))
    assertEquals("sun", HaDashboardDay.sceneIcon("Focus"))
    assertEquals("sparkle", HaDashboardDay.sceneIcon("Party"))
    assertNull(HaDashboardDay.sceneWhen("Movie"))
    assertEquals(Part.NIGHT, HaDashboardDay.sceneWhen("Bedtime"))
  }

  @Test fun `clock reads 12 or 24 hour`() {
    assertEquals("6:48", HaDashboardDay.clock(18, 48, use24h = false))
    assertEquals("12:05", HaDashboardDay.clock(0, 5, use24h = false))
    assertEquals("12:30", HaDashboardDay.clock(12, 30, use24h = false))
    assertEquals("18:48", HaDashboardDay.clock(18, 48, use24h = true))
    assertEquals("07:02", HaDashboardDay.clock(7, 2, use24h = true))
  }

  @Test fun `subline carries clock, lights and one useful fact`() {
    val f = HaDashboardDay.Facts(lit = 5, lock = "Front door locked", insideTemp = "20.5°", offlineMinutes = null, hasNightScene = true)
    assertEquals("6:48 · 5 lights on · Front door locked · 20.5° inside", HaDashboardDay.subline("6:48", Part.EVENING, f))
    assertEquals("10:31 · 5 lights on · Front door locked · 20.5° inside · tap Night to wind down",
        HaDashboardDay.subline("10:31", Part.NIGHT, f))
    assertEquals("10:31 · All lights off · Front door locked · 20.5° inside",
        HaDashboardDay.subline("10:31", Part.NIGHT, f.copy(lit = 0)))
    assertEquals("6:48 · 1 light on · Last updated 4 min ago",
        HaDashboardDay.subline("6:48", Part.EVENING, f.copy(lit = 1, offlineMinutes = 4)))
    assertEquals("6:48 · 5 lights on", HaDashboardDay.subline("6:48", Part.EVENING, f.copy(lock = null, insideTemp = null)))
  }

  @Test fun `age and lock facts`() {
    assertEquals("Last updated just now", HaDashboardDay.ageText(0))
    assertEquals("Last updated 4 min ago", HaDashboardDay.ageText(4))
    assertEquals("Last updated 2 h ago", HaDashboardDay.ageText(125))
    assertEquals("Front door locked", HaDashboardDay.lockFact(listOf(haDetail("light.x"),
        haDetail("lock.f", state = "locked", name = "Front door"))))
    assertEquals("Back door unlocked", HaDashboardDay.lockFact(listOf(haDetail("lock.b", state = "unlocked", name = "Back door"))))
    assertNull(HaDashboardDay.lockFact(listOf(haDetail("lock.b", state = "unavailable"))))
  }
}
