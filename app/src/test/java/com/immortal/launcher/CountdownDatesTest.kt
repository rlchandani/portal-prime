/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownDatesTest {
  @Test fun yearlyLeapDayUsesTheNextActualOccurrence() {
    assertEquals(730, CountdownDates.daysUntil(2, 29, 0, localDate(2026, 3, 1)))
    assertEquals(0, CountdownDates.daysUntil(2, 29, 0, localDate(2024, 2, 29)))
    assertEquals(1, CountdownDates.daysUntil(2, 29, 0, localDate(2024, 2, 28)))
    assertEquals(2920, CountdownDates.daysUntil(2, 29, 0, localDate(2096, 3, 1)))
  }

  @Test fun dateBoundariesIgnoreLocalDayLengthAndTimeOfDay() {
    val march8 = localDate(2026, 3, 8, "America/New_York")
    val march9 = localDate(2026, 3, 9, "America/New_York")
    assertEquals(1, CountdownDates.daysUntil(3, 9, 2026, march8))
    assertEquals(-1, CountdownDates.daysUntil(3, 8, 2026, march9))
    assertEquals(0, CountdownDates.daysUntil(3, 8, 2026, march8))
    assertEquals(1, CountdownDates.daysUntil(11, 2, 2026, localDate(2026, 11, 1, "America/New_York")))
  }

  @Test fun calculationsSupportExistingYearsOutsideTheSaveRange() {
    assertEquals(59, CountdownDates.daysUntil(3, 1, 1900, localDate(1900, 1, 1)))
    assertEquals(365, CountdownDates.daysUntil(12, 31, 2101, localDate(2100, 12, 31)))
    assertTrue(CountdownDates.isRealDate(2, 29, 2400))
    assertFalse(CountdownDates.isRealDate(2, 29, 2100))
  }

  @Test fun invalidLegacyDatesAreRejectedByThePureCalculation() {
    listOf(
        Triple(4, 31, 2026), Triple(2, 30, 0), Triple(2, 29, 2026),
        Triple(0, 1, 2026), Triple(13, 1, 2026), Triple(1, 0, 2026),
        Triple(1, 32, 2026), Triple(1, 1, -1),
    ).forEach { (month, day, year) ->
      assertNull(CountdownDates.daysUntil(month, day, year, localDate(2026, 1, 1)))
    }
  }

  @Test fun toolsCalculationAcceptsLegacyYearsWithoutRelaxingSaveValidation() {
    assertEquals(365, ApprovedToolsEverydayValidation.countdownDaysUntil(
        12, 31, 2101, localDate(2100, 12, 31)))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(12, 31, 2101))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(3, 1, 1900))
    assertTrue(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 29, 0))
  }

  @Test fun eventDaysUntilAndPhraseUseCalendarDaysAcrossDst() {
    val countdown = event(month = 3, day = 9, year = 2026)
    val today = localDate(2026, 3, 8, "America/New_York")
    assertEquals(1, countdown.daysUntil(today))
    assertEquals("tomorrow", countdown.phrase(today))
    assertEquals(-1, event(month = 3, day = 8, year = 2026)
        .daysUntil(localDate(2026, 3, 9, "America/New_York")))
  }

  @Test fun eventDaysUntilUsesActualLeapDaysAndExistingPositiveYears() {
    assertEquals(730, event(2, 29, 0).daysUntil(localDate(2026, 3, 1)))
    assertEquals(2920, event(2, 29, 0).daysUntil(localDate(2096, 3, 1)))
    assertEquals(365, event(12, 31, 2101).daysUntil(localDate(2100, 12, 31)))
  }

  @Test fun invalidLegacyEventKeepsItsOriginalLenientFallback() {
    // April 31 historically rolls to May 1. Calculation must not delete or rewrite it.
    val legacy = event(4, 31, 2026)
    assertEquals(30, legacy.daysUntil(localDate(2026, 4, 1)))
    assertEquals("in 30 days", legacy.phrase(localDate(2026, 4, 1)))
    assertNull(ApprovedToolsEverydayValidation.countdownDaysUntil(
        legacy.month, legacy.day, legacy.year, localDate(2026, 4, 1)))
  }

  private fun event(month: Int, day: Int, year: Int) =
      CountdownConfig.Event(1L, "Event", "📅", month, day, year)

  private fun localDate(year: Int, month: Int, day: Int, zone: String = "UTC"): Calendar =
      GregorianCalendar(TimeZone.getTimeZone(zone)).apply {
        clear()
        set(year, month - 1, day, 12, 34, 56)
      }
}
