/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Feast labels belong to civil dates, including days on the other side of a clock change. */
class FeastDaysCivilOffsetTest {
  @Test
  fun palmSundaySurvivesBucharestSpringClockChange() = withDefaultZone("Europe/Bucharest") {
    expect(2037, 3, 29, "Florii (Palm Sunday)")
  }

  @Test
  fun saturdayBeforePalmSundayDoesNotBorrowSundaysLabel() = withDefaultZone("Europe/Bucharest") {
    expect(2037, 3, 28, "")
  }

  @Test
  fun historicalPalmSundayUsesTheSameCivilDateRule() = withDefaultZone("Europe/Bucharest") {
    expect(2010, 3, 28, "Florii (Palm Sunday)")
    expect(2010, 3, 27, "")
  }

  @Test
  fun existingMinus48MappingSurvivesSpringClockChange() = withDefaultZone("Europe/Bucharest") {
    // Preserve the existing date-to-label map; this test does not redefine the observance.
    expect(2026, 2, 23, "Lăsatul secului")
  }

  @Test
  fun adjacentDateDoesNotBorrowTheExistingMinus48Label() = withDefaultZone("Europe/Bucharest") {
    expect(2026, 2, 22, "")
  }

  @Test
  fun utcInputKeepsItsPalmSundayWhenDefaultZoneDiffers() = withDefaultZone("Europe/Bucharest") {
    expect(2037, 3, 29, "Florii (Palm Sunday)", inputZone = "UTC")
  }

  @Test
  fun utcInputKeepsItsAscensionWhenDefaultZoneIsBehindUtc() = withDefaultZone("America/New_York") {
    expect(2026, 5, 21, "Înălțarea Domnului", inputZone = "UTC")
  }

  @Test
  fun ordinaryUtcDatesRetainEveryExistingMovableLabel() = withDefaultZone("UTC") {
    expect(2026, 2, 23, "Lăsatul secului")
    expect(2026, 4, 5, "Florii (Palm Sunday)")
    expect(2026, 4, 10, "Vinerea Mare (Good Friday)")
    expect(2026, 4, 12, "Învierea Domnului (Paște)")
    expect(2026, 4, 13, "A doua zi de Paște")
    expect(2026, 5, 21, "Înălțarea Domnului")
    expect(2026, 5, 31, "Rusaliile (Pentecost)")
  }

  @Test
  fun fixedFeastsAndUnmarkedDatesKeepTheirFallback() {
    for (zone in listOf("UTC", "Europe/Bucharest", "America/New_York")) {
      withDefaultZone(zone) {
        expect(2026, 3, 25, "Buna Vestire")
        expect(2026, 12, 25, "Nașterea Domnului (Crăciun)")
        expect(2026, 7, 14, "")
      }
    }
  }

  @Test
  fun leapYearOffsetsAndDifferentTimesOfDayRetainLabels() = withDefaultZone("UTC") {
    expect(2024, 3, 18, "Lăsatul secului", hour = 0)
    expect(2024, 4, 28, "Florii (Palm Sunday)", hour = 23)
    expect(2024, 5, 5, "Învierea Domnului (Paște)", hour = 12)
    expect(2024, 6, 13, "Înălțarea Domnului", hour = 23)
    expect(2024, 6, 23, "Rusaliile (Pentecost)", hour = 0)
  }

  private fun expect(
      year: Int,
      month: Int,
      day: Int,
      label: String,
      inputZone: String = TimeZone.getDefault().id,
      hour: Int = 12,
  ) {
    val now = GregorianCalendar(TimeZone.getTimeZone(inputZone), Locale.US).apply {
      clear()
      isLenient = false
      set(year, month - 1, day, hour, 34, 56)
      set(Calendar.MILLISECOND, 789)
    }
    val before = now.clone() as Calendar
    val actual = FeastDays.forToday(now)
    assertEquals("The supplied Calendar must remain unchanged", before, now)
    assertEquals("$year-$month-$day in $inputZone, default ${TimeZone.getDefault().id}", label, actual)
  }

  private fun withDefaultZone(zone: String, block: () -> Unit) {
    val originalZone = TimeZone.getDefault()
    val originalLocale = Locale.getDefault()
    val originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    try {
      Locale.setDefault(Locale.US)
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      block()
    } finally {
      TimeZone.setDefault(originalZone)
      Locale.setDefault(originalLocale)
      Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
      Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
    }
  }
}
