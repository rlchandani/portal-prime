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
import org.junit.Test

/** The public holiday follows the Irish rule introduced in 2023; the feast remains February 1. */
class IrishBrigidHolidayTest {
  @Test fun fridayFebruaryFirstIsTheBankHoliday() =
      expect(2030, 2, 1, "St Brigid's Day (bank holiday)")

  @Test fun mondayAfterFridayFebruaryFirstIsNotAnotherBankHoliday() =
      expect(2030, 2, 4, "")

  @Test fun mondayFebruaryFirstRetainsTheBankHolidayLabel() =
      expect(2027, 2, 1, "St Brigid's Day (bank holiday)")

  @Test fun firstMondayBeforeIntroductionIsNotABankHoliday() =
      expect(2022, 2, 7, "")

  @Test fun inauguralHolidayIsFebruarySixth2023() =
      expect(2023, 2, 6, "St Brigid's Day (bank holiday)")

  @Test fun thursdayFebruaryFirstLeavesTheHolidayOnMonday() =
      expect(2024, 2, 5, "St Brigid's Day (bank holiday)")

  @Test fun saturdayFebruaryFirstLeavesTheHolidayOnMonday() =
      expect(2025, 2, 3, "St Brigid's Day (bank holiday)")

  @Test fun sundayFebruaryFirstLeavesTheHolidayOnMonday() =
      expect(2026, 2, 2, "St Brigid's Day (bank holiday)")

  @Test fun thursdayFebruaryFirstKeepsItsSaintsDay() =
      expect(2024, 2, 1, "St Brigid's Day")

  @Test fun saturdayFebruaryFirstKeepsItsSaintsDay() =
      expect(2025, 2, 1, "St Brigid's Day")

  @Test fun historicalFridayKeepsItsSaintsDay() =
      expect(2019, 2, 1, "St Brigid's Day")

  @Test fun otherHolidayRulesRemain() {
    expect(2026, 3, 17, "St Patrick's Day")
    expect(2026, 4, 6, "Easter Monday")
    expect(2026, 5, 4, "May Bank Holiday")
    expect(2026, 10, 26, "October Bank Holiday")
    expect(2026, 12, 25, "Christmas Day")
    expect(2026, 7, 14, "")
  }

  private fun expect(year: Int, month: Int, day: Int, expected: String) {
    val date = GregorianCalendar(TimeZone.getTimeZone("Europe/Dublin")).apply {
      clear()
      isLenient = false
      set(year, month - 1, day, 12, 34, 56)
    }
    val before = date.clone() as Calendar
    assertEquals("$year-$month-$day", expected, IrishHolidays.forToday(date))
    assertEquals("The supplied calendar must remain unchanged", before, date)
  }
}
