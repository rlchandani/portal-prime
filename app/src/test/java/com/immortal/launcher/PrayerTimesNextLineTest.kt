/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual prayer/header methods with generated fresh location and strict preference tripwires. */
class PrayerTimesNextLineTest {
  @Test fun tomorrowFajrRecomputesSpringDstDateAndOffset() = fixture {
    it.assertTomorrow(date(2026, 3, 7))
  }

  @Test fun tomorrowFajrRecomputesFallDstDateAndOffset() = fixture {
    it.assertTomorrow(date(2026, 10, 31))
  }

  @Test fun tomorrowFajrRecomputesAnOrdinarySeasonalDay() = fixture {
    it.assertTomorrow(date(2026, 4, 10))
  }

  @Test fun tomorrowFajrAdvancesPastTheEndOfApril() = fixture {
    it.assertTomorrow(date(2026, 4, 30))
  }

  @Test fun tomorrowFajrAdvancesPastTheEndOfFebruary() = fixture {
    it.assertTomorrow(date(2026, 2, 28))
  }

  @Test fun tomorrowFajrAdvancesAcrossTheYearBoundary() = fixture(longitude = "-73.92") {
    // This generated New York location also has distinguishable minute values at year rollover.
    it.assertTomorrow(date(2026, 12, 31))
  }

  @Test fun tomorrowSelectionDoesNotMutateTheSuppliedCalendar() = fixture {
    val now = date(2026, 3, 7)
    val original = now.clone() as Calendar
    assertTrue(PrayerTimes.nextLine(it.context, now).endsWith("(tomorrow)"))
    assertEquals(original, now)
    assertEquals(original.timeZone, now.timeZone)
  }

  @Test fun beforeFajrSelectsTheCurrentDatesFajr() = fixture {
    val now = date(2026, 4, 10, 0, 1)
    val fajr = PrayerTimes.forToday(it.context, now)!!.getValue("Fajr")
    assertEquals("🕌 Fajr $fajr", PrayerTimes.nextLine(it.context, now))
  }

  @Test fun everyExactPrayerMinuteStillSelectsThatCurrentPrayer() = fixture {
    val day = date(2026, 4, 10, 12, 0)
    val times = PrayerTimes.forToday(it.context, day)!!
    for (name in listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")) {
      val now = day.clone() as Calendar
      val parts = times.getValue(name).split(":").map(String::toInt)
      now.set(Calendar.HOUR_OF_DAY, parts[0])
      now.set(Calendar.MINUTE, parts[1])
      assertEquals("🕌 $name ${times.getValue(name)}", PrayerTimes.nextLine(it.context, now))
    }
  }

  @Test fun oneMinuteAfterFajrSelectsTheCurrentDatesDhuhr() = fixture {
    val now = date(2026, 4, 10, 12, 0)
    val times = PrayerTimes.forToday(it.context, now)!!
    val parts = times.getValue("Fajr").split(":").map(String::toInt)
    now.set(Calendar.HOUR_OF_DAY, parts[0])
    now.set(Calendar.MINUTE, parts[1])
    now.add(Calendar.MINUTE, 1)
    assertEquals("🕌 Dhuhr ${times.getValue("Dhuhr")}", PrayerTimes.nextLine(it.context, now))
  }

  @Test fun enabledCalendarPackDisplaysRecomputedTomorrowHeader() = fixture {
    val now = date(2026, 3, 7)
    val expected = it.tomorrowHeader(now)
    assertEquals(listOf(expected), CalendarPacks.headerLines(it.context, now))
  }

  @Test fun disabledCalendarPacksDoNotResolveLocation() = fixture(prayerEnabled = false) {
    assertTrue(CalendarPacks.headerLines(it.context, date(2026, 3, 7)).isEmpty())
    assertEquals(0, it.weatherReads)
  }

  private fun date(year: Int, month: Int, day: Int, hour: Int = 23, minute: Int = 59): Calendar =
      GregorianCalendar(TimeZone.getDefault(), Locale.US).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }

  private fun fixture(longitude: String = "-74.006", prayerEnabled: Boolean = true,
      operation: (Fixture) -> Unit) {
    val zone = TimeZone.getDefault()
    val locale = Locale.getDefault()
    val format = Locale.getDefault(Locale.Category.FORMAT)
    val display = Locale.getDefault(Locale.Category.DISPLAY)
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
      Locale.setDefault(Locale.US)
      val fixture = Fixture(longitude, prayerEnabled)
      Mockito.mockStatic(Log::class.java).use {
        try { operation(fixture) }
        finally {
          assertTrue("Forbidden fixture operations: ${fixture.forbidden}", fixture.forbidden.isEmpty())
          assertEquals("Location reads must use complete fresh cached fixes", 0, fixture.weatherReads % 3)
          if (prayerEnabled) assertTrue("Expected generated cached location reads", fixture.weatherReads >= 3)
        }
      }
    } finally {
      TimeZone.setDefault(zone)
      Locale.setDefault(locale)
      Locale.setDefault(Locale.Category.FORMAT, format)
      Locale.setDefault(Locale.Category.DISPLAY, display)
    }
  }

  private class Fixture(longitude: String, prayerEnabled: Boolean) {
    val forbidden = mutableListOf<String>()
    var weatherReads = 0
    private val resolvedAt = System.currentTimeMillis()
    private fun refuse(message: String): Nothing {
      forbidden.add(message)
      throw AssertionError(message)
    }
    private val weather = Mockito.mock(SharedPreferences::class.java) { call ->
      when (call.method.name) {
        "getLong" -> {
          if (call.getArgument<String>(0) != "resolved_at") refuse("Unknown weather long key")
          weatherReads++
          resolvedAt
        }
        "getString" -> {
          weatherReads++
          when (call.getArgument<String>(0)) {
            "lat" -> "40.7128"
            "lon" -> longitude
            else -> refuse("Unknown weather string key")
          }
        }
        "toString" -> "GeneratedPrayerWeather"
        else -> refuse("Forbidden weather operation: ${call.method.name}")
      }
    }
    private val packs = Mockito.mock(SharedPreferences::class.java) { call ->
      when (call.method.name) {
        "getBoolean" -> when (call.getArgument<String>(0)) {
          CalendarPacks.ROMANIAN, CalendarPacks.IRISH -> false
          CalendarPacks.PRAYER -> prayerEnabled
          else -> refuse("Unknown calendar-pack key")
        }
        "toString" -> "GeneratedPrayerPacks"
        else -> refuse("Forbidden calendar-pack operation: ${call.method.name}")
      }
    }
    val context = Mockito.mock(Context::class.java) { call ->
      when (call.method.name) {
        "getSharedPreferences" -> {
          if (call.getArgument<Int>(1) != Context.MODE_PRIVATE) refuse("Unexpected preference mode")
          when (call.getArgument<String>(0)) {
            "immortal_weather" -> weather
            "immortal_packs" -> packs
            else -> refuse("Unknown preference store")
          }
        }
        "toString" -> "GeneratedPrayerContext"
        else -> refuse("Forbidden Context operation: ${call.method.name}")
      }
    }

    fun tomorrowHeader(now: Calendar): String {
      val next = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
      val todayFajr = PrayerTimes.forToday(context, now)!!.getValue("Fajr")
      val nextFajr = PrayerTimes.forToday(context, next)!!.getValue("Fajr")
      assertNotEquals("Fixture must distinguish today's and tomorrow's Fajr", todayFajr, nextFajr)
      return "🕌 Fajr $nextFajr (tomorrow)"
    }

    fun assertTomorrow(now: Calendar) {
      assertEquals(tomorrowHeader(now), PrayerTimes.nextLine(context, now))
    }
  }
}
