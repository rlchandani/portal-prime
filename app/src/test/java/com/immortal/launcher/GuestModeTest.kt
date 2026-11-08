/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestModeTest {
  private val zone = ZoneId.of("Europe/Dublin")
  private fun at(day: Int, hour: Int, minute: Int = 0) =
      LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

  @After fun reset() = GuestMode.set(GuestModeState.OFF)

  @Test fun offHidesNothing() {
    GuestContent.entries.forEach { assertFalse(it.name, GuestMode.hides(it, at(7, 12))) }
  }

  @Test fun onHidesEveryPersonalSurface() {
    GuestMode.set(GuestModeState(active = true, untilMs = null))
    GuestContent.entries.forEach { assertTrue(it.name, GuestMode.hides(it, at(7, 12))) }
  }

  @Test fun playbackControlsAreNotPersonalContent() {
    GuestMode.set(GuestModeState(active = true, untilMs = null))
    assertFalse(GuestContent.entries.map { it.name }.contains("PLAYBACK"))
  }

  @Test fun scheduledModeExpiresAtItsEnd() {
    GuestMode.set(GuestModeState(active = true, untilMs = at(8, 11)))
    assertTrue(GuestMode.hides(GuestContent.PHOTOS, at(8, 10, 59)))
    assertFalse(GuestMode.hides(GuestContent.PHOTOS, at(8, 11)))
    assertFalse(GuestMode.isOn(at(9, 9)))
  }

  @Test fun untilTomorrowAtPicksTheNextDaysTime() =
      assertEquals(at(8, 11), GuestSchedule.UntilTomorrow(11, 0).endMs(at(7, 22), zone))

  @Test fun untilTomorrowEarlyMorningStillMeansTheNextCalendarDay() =
      assertEquals(at(8, 11), GuestSchedule.UntilTomorrow(11, 0).endMs(at(7, 1), zone))

  @Test fun forDaysAddsWholeDays() =
      assertEquals(at(9, 15, 30), GuestSchedule.ForDays(2).endMs(at(7, 15, 30), zone))

  @Test fun untilEndedHasNoEnd() = assertNull(GuestSchedule.UntilEnded.endMs(at(7, 12), zone))

  @Test fun startUsesTheSchedule() {
    val s = GuestModeState.start(GuestSchedule.ForDays(1), at(7, 12), zone)
    assertEquals(GuestModeState(true, at(8, 12)), s)
  }

  @Test fun scheduleParsingRejectsOutOfRangeValues() {
    assertEquals(GuestSchedule.ForDays(2), GuestSchedule.parse(days = 2, hour = null, minute = null))
    assertEquals(GuestSchedule.UntilTomorrow(11, 0), GuestSchedule.parse(days = null, hour = 11, minute = 0))
    assertEquals(GuestSchedule.UntilEnded, GuestSchedule.parse(days = null, hour = null, minute = null))
    assertNull(GuestSchedule.parse(days = 0, hour = null, minute = null))
    assertNull(GuestSchedule.parse(days = GuestSchedule.MAX_DAYS + 1, hour = null, minute = null))
    assertNull(GuestSchedule.parse(days = null, hour = 24, minute = 0))
    assertNull(GuestSchedule.parse(days = null, hour = 10, minute = 60))
    assertNull(GuestSchedule.parse(days = 1, hour = 10, minute = 0))
  }

  @Test fun stateRoundTripsThroughItsStoredForm() {
    val s = GuestModeState(true, at(8, 11))
    assertEquals(s, GuestModeState.decode(s.encode()))
    assertEquals(GuestModeState.OFF, GuestModeState.decode("garbage"))
    assertEquals(GuestModeState.OFF, GuestModeState.decode(null))
  }

  @Test fun observersSeeChanges() {
    GuestMode.set(GuestModeState(true, null))
    assertTrue(GuestMode.state.value.active)
  }
}
