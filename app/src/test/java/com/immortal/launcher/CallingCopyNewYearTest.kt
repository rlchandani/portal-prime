/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: history days were keyed YEAR * 400 + DAY_OF_YEAR, so 31 Dec and 1 Jan were 36 keys
 * apart and last night's calls showed under "Earlier" every New Year's Day.
 */
class CallingCopyNewYearTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val hour = 60 * 60_000L
  private val dec31 = 1_798_675_200_000L // 2026-12-31 00:00 UTC
  private val jan1 = 1_798_761_600_000L // 2027-01-01 00:00 UTC

  @Test fun aCallOnNewYearsEveIsYesterdayOnNewYearsDay() {
    assertEquals("Yesterday", CallingCopy.historyDay(dec31 + 22 * hour, jan1 + 9 * hour, utc))
  }

  @Test fun twoDaysBackAcrossTheYearIsEarlier() {
    assertEquals("Earlier", CallingCopy.historyDay(dec31 - 2 * hour, jan1 + 9 * hour, utc))
  }

  @Test fun dayBoundaryFollowsTheLocalZone() {
    val tokyo = TimeZone.getTimeZone("Asia/Tokyo") // UTC+9: 2026-12-31 16:00 UTC is already 1 Jan
    assertEquals("Today", CallingCopy.historyDay(dec31 + 16 * hour, jan1 + 9 * hour, tokyo))
    assertEquals("Yesterday", CallingCopy.historyDay(dec31 + 14 * hour, jan1 + 9 * hour, tokyo))
  }
}
