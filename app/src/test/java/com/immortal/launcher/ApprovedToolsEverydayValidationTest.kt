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

class ApprovedToolsEverydayValidationTest {
  @Test fun customTimerAcceptsPositiveDurationsAtBothBoundaries() {
    assertEquals(1_000L, ApprovedToolsEverydayValidation.timerDurationMillis("0", "0", "1"))
    assertEquals(86_399_000L, ApprovedToolsEverydayValidation.timerDurationMillis("23", "59", "59"))
    assertEquals(3_723_000L, ApprovedToolsEverydayValidation.timerDurationMillis("1", "2", "3"))
  }

  @Test fun customTimerRejectsZeroMissingAndMalformedComponents() {
    assertNull(ApprovedToolsEverydayValidation.timerDurationMillis("0", "0", "0"))
    listOf("", " ", "-1", "1.5", "1e2", "NaN", "99999999999999999999").forEach { invalid ->
      assertNull(ApprovedToolsEverydayValidation.timerDurationMillis(invalid, "5", "0"))
      assertNull(ApprovedToolsEverydayValidation.timerDurationMillis("0", invalid, "1"))
      assertNull(ApprovedToolsEverydayValidation.timerDurationMillis("0", "5", invalid))
    }
  }

  @Test fun customTimerRejectsComponentsRatherThanNormalizingThem() {
    assertNull(ApprovedToolsEverydayValidation.timerDurationMillis("24", "0", "0"))
    assertNull(ApprovedToolsEverydayValidation.timerDurationMillis("0", "60", "0"))
    assertNull(ApprovedToolsEverydayValidation.timerDurationMillis("0", "0", "60"))
  }

  @Test fun countdownRejectsImpossibleDatesWithoutRollingIntoAnotherMonth() {
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(4, 31, 2026))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 30, 2024))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 29, 2026))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 29, 2100))
    assertTrue(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 29, 2000))
    assertTrue(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 29, 2024))
    assertTrue(ApprovedToolsEverydayValidation.isRealCountdownDate(12, 31, 2100))
  }

  @Test fun countdownRejectsOutOfRangeDatesAndYears() {
    listOf(
        Triple(0, 1, 2026), Triple(13, 1, 2026), Triple(1, 0, 2026),
        Triple(1, 32, 2026), Triple(1, 1, 1999), Triple(1, 1, 2101),
        Triple(1, 1, -1),
    ).forEach { (month, day, year) ->
      assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(month, day, year))
    }
  }

  @Test fun yearlyDatesAcceptLeapDayButRejectDatesThatNeverOccur() {
    assertTrue(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 29, 0))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(2, 30, 0))
    assertFalse(ApprovedToolsEverydayValidation.isRealCountdownDate(4, 31, 0))
  }

  @Test fun countdownRequiresAUsableNameAndAValidDateBeforeSaving() {
    assertTrue(ApprovedToolsEverydayValidation.countdownError("  ", 1, 1, 2026) != null)
    assertTrue(ApprovedToolsEverydayValidation.countdownError("x".repeat(81), 1, 1, 2026) != null)
    assertTrue(ApprovedToolsEverydayValidation.countdownError("Trip", 4, 31, 2026) != null)
    assertNull(ApprovedToolsEverydayValidation.countdownError("  Birthday  ", 11, 12, 0))
  }

  @Test fun yearlyLeapDayCountsToItsNextRealOccurrence() {
    val today = GregorianCalendar(2026, Calendar.MARCH, 1)
    assertEquals(730, ApprovedToolsEverydayValidation.countdownDaysUntil(2, 29, 0, today))
    assertEquals(0, ApprovedToolsEverydayValidation.countdownDaysUntil(
        2, 29, 0, GregorianCalendar(2024, Calendar.FEBRUARY, 29)))
  }

  @Test fun countdownCountsCalendarDaysAcrossDaylightSavingChanges() {
    val today = GregorianCalendar(TimeZone.getTimeZone("America/New_York")).apply {
      clear()
      set(2026, Calendar.MARCH, 8, 12, 0, 0)
    }
    assertEquals(1, ApprovedToolsEverydayValidation.countdownDaysUntil(3, 9, 2026, today))
    assertEquals(-1, ApprovedToolsEverydayValidation.countdownDaysUntil(3, 7, 2026, today))
  }

  @Test fun converterAcceptsSignedDecimalsButRejectsNonfiniteInput() {
    assertEquals(-12.5, ApprovedToolsEverydayValidation.converterValue("-12.5")!!, 0.0)
    assertEquals(0.25, ApprovedToolsEverydayValidation.converterValue(" .25 ")!!, 0.0)
    listOf("", " ", "NaN", "Infinity", "-Infinity", "1e309", "one").forEach {
      assertNull(ApprovedToolsEverydayValidation.converterValue(it))
    }
  }

  @Test fun timerDisplayRoundsUpAndDoesNotShowNegativeTime() {
    assertEquals("00:01", ApprovedToolsEverydayValidation.timerClock(1L))
    assertEquals("01:00", ApprovedToolsEverydayValidation.timerClock(59_001L))
    assertEquals("1:00:00", ApprovedToolsEverydayValidation.timerClock(3_600_000L))
    assertEquals("00:00", ApprovedToolsEverydayValidation.timerClock(-1L))
  }
}
