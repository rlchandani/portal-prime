/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: the lenient fallback for invalid legacy dates truncated the millisecond span, so
 * across a spring-forward DST change (a 23 h day) it read one day short.
 */
class CountdownConfigDstTest {
  private val ny = TimeZone.getTimeZone("America/New_York")

  private fun noonOn(month: Int, day: Int) =
      Calendar.getInstance(ny).apply { clear(); set(2026, month, day, 12, 0) }

  @Test fun legacyLenientDateIsNotADayShortAcrossSpringForward() {
    // "31 April" rolls to 1 May: 61 calendar days after 1 March.
    assertEquals(61, CountdownConfig.Event(1, "Trip", "x", 4, 31, 0).daysUntil(noonOn(Calendar.MARCH, 1)))
  }

  @Test fun legacyLenientDateCountsWholeDaysAcrossFallBack() {
    // "31 November" rolls to 1 December: 31 days after 31 October, across the 25 h day.
    assertEquals(31, CountdownConfig.Event(2, "Trip", "x", 11, 31, 0).daysUntil(noonOn(Calendar.OCTOBER, 31)))
  }
}
