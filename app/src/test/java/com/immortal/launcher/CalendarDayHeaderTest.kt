/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import sun.misc.Unsafe

class CalendarDayHeaderTest {
  @Test fun tomorrowSurvivesTheTwentyFiveHourDay() = fixture("fall") { f ->
    assertEquals("Tomorrow", f.header(1))
  }

  @Test fun tomorrowSurvivesTheTwentyThreeHourDay() = fixture("spring") { f ->
    assertEquals("Tomorrow", f.header(1))
  }

  @Test fun todayRetainsPriorityBeforeTheFallTransition() = fixture("fall") { f ->
    assertEquals("Today", f.header(0))
  }

  @Test fun todayRetainsPriorityBeforeTheSpringTransition() = fixture("spring") { f ->
    assertEquals("Today", f.header(0))
  }

  @Test fun ordinaryTomorrowStillHasItsRelativeLabel() = fixture("ordinary") { f ->
    assertEquals("Tomorrow", f.header(1))
  }

  @Test fun laterDaysRetainTheirDateLabel() = fixture("ordinary") { f ->
    val label = f.header(3)
    assertTrue(label.isNotBlank())
    assertFalse(label == "Today" || label == "Tomorrow")
  }

  private fun fixture(profile: String, check: (Fixture) -> Unit) {
    val oldZone = TimeZone.getDefault()
    val oldLocale = Locale.getDefault()
    val oldDisplay = Locale.getDefault(Locale.Category.DISPLAY)
    val oldFormat = Locale.getDefault(Locale.Category.FORMAT)
    try {
      Locale.setDefault(Locale.US)
      val f = Fixture(profile)
      // Only Android's localized skeleton lookup is unavailable on the plain JVM.
      // The real header, day keys, calendars and DateFormatter remain in use.
      Mockito.mockStatic(DateFormat::class.java).use { patterns ->
        patterns.`when`<String> {
          DateFormat.getBestDateTimePattern(Locale.US, "EEEMMMd")
        }.thenReturn("EEE, MMM d")
        TimeZone.setDefault(f.zone)
        check(f)
        assertTrue("Real clock drift exceeded the fixture margin",
            System.currentTimeMillis() - f.reference < 120_000)
      }
    } finally {
      TimeZone.setDefault(oldZone)
      Locale.setDefault(oldLocale)
      Locale.setDefault(Locale.Category.DISPLAY, oldDisplay)
      Locale.setDefault(Locale.Category.FORMAT, oldFormat)
    }
  }

  private class Fixture(profile: String) {
    val reference = System.currentTimeMillis()
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = GregorianCalendar(utc, Locale.US).apply {
      timeInMillis = reference
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val zone: TimeZone = if (profile == "ordinary") utc else {
      val spring = profile == "spring"
      val start = (day.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, if (spring) 1 else -1)
      }
      val end = (day.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, if (spring) 2 else 0)
      }
      val hour = 3_600_000
      val target = day.timeInMillis + if (spring) 23 * hour + hour / 2 else hour / 2
      val offset = (target - reference - if (spring) 0 else hour).toInt()
      // SimpleTimeZone rejects a literal February 29 rule. In a leap year,
      // that date is the last occurrence of its weekday in February.
      fun leapDay(c: Calendar) =
          c.get(Calendar.MONTH) == Calendar.FEBRUARY && c.get(Calendar.DAY_OF_MONTH) == 29
      fun ruleDay(c: Calendar) = if (leapDay(c)) -1 else c.get(Calendar.DAY_OF_MONTH)
      fun ruleWeekday(c: Calendar) = if (leapDay(c)) c.get(Calendar.DAY_OF_WEEK) else 0
      SimpleTimeZone(offset, "HeaderFixture-$profile",
          start.get(Calendar.MONTH), ruleDay(start), ruleWeekday(start), 2 * hour,
          end.get(Calendar.MONTH), ruleDay(end), ruleWeekday(end), 2 * hour, hour)
    }
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
      isAccessible = true
      get(null) as Unsafe
    }
    private val controller =
        unsafe.allocateInstance(PhotoFrameController::class.java) as PhotoFrameController
    private val method = PhotoFrameController::class.java.getDeclaredMethod(
        "dayHeader", Long::class.javaPrimitiveType).apply { isAccessible = true }

    fun header(dayOffset: Int): String {
      // Construct the event's local date independently of elapsed-hour arithmetic.
      val event = GregorianCalendar(zone, Locale.US).apply {
        clear()
        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH),
            day.get(Calendar.DAY_OF_MONTH) + dayOffset, 12, 0, 0)
      }
      val localNow = GregorianCalendar(zone, Locale.US).apply { timeInMillis = reference }
      if (zone.id.endsWith("fall")) {
        assertEquals(0, localNow.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, localNow.get(Calendar.MINUTE))
      } else if (zone.id.endsWith("spring")) {
        assertEquals(23, localNow.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, localNow.get(Calendar.MINUTE))
      }
      return method.invoke(controller, event.timeInMillis) as String
    }
  }
}
