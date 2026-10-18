/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Open-Meteo forecast parsing + URL building (no device/network needed).
 *
 * Timezone and locale are pinned so the day/hour labels and the "drop past hours"
 * cutoff are deterministic: the response carries local times (`timezone=auto`), which
 * the parser reads using the response offset when present, and the labels are locale-formatted.
 */
class WeatherTest {

  private lateinit var savedTz: TimeZone
  private lateinit var savedLocale: Locale

  @Before
  fun pinZoneAndLocale() {
    savedTz = TimeZone.getDefault()
    savedLocale = Locale.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
  }

  @After
  fun restore() {
    TimeZone.setDefault(savedTz)
    Locale.setDefault(savedLocale)
  }

  /** Epoch millis for a UTC wall-clock time like "2026-06-07T13:30". */
  private fun utc(stamp: String): Long =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
          .apply { timeZone = TimeZone.getTimeZone("UTC") }
          .parse(stamp)!!
          .time

  // 7 daily entries; 18 hourly entries spanning 10:00 today → 03:00 tomorrow.
  private val sample =
      """
      {
        "daily": {
          "time": ["2026-06-07","2026-06-08","2026-06-09","2026-06-10","2026-06-11","2026-06-12","2026-06-13"],
          "weather_code": [0,1,2,3,45,61,71],
          "temperature_2m_max": [70.4,71.6,72.0,73.0,74.0,75.0,76.0],
          "temperature_2m_min": [50.6,51.0,52.0,53.0,54.0,55.0,56.0]
        },
        "hourly": {
          "time": [
            "2026-06-07T10:00","2026-06-07T11:00","2026-06-07T12:00","2026-06-07T13:00",
            "2026-06-07T14:00","2026-06-07T15:00","2026-06-07T16:00","2026-06-07T17:00",
            "2026-06-07T18:00","2026-06-07T19:00","2026-06-07T20:00","2026-06-07T21:00",
            "2026-06-07T22:00","2026-06-07T23:00","2026-06-08T00:00","2026-06-08T01:00",
            "2026-06-08T02:00","2026-06-08T03:00"
          ],
          "weather_code": [0,0,0,61,0,0,0,0,0,0,0,0,0,0,3,0,0,0],
          "temperature_2m": [50.0,51.0,52.0,60.4,61.6,63.0,64.0,65.0,66.0,67.0,68.0,69.0,70.0,71.0,72.0,73.0,74.0,75.0]
        }
      }
      """.trimIndent()

  @Test
  fun `daily labels today then weekday, temps rounded`() {
    val days = Weather.parseForecast(sample, utc("2026-06-07T13:30")).days
    assertEquals(7, days.size)
    assertEquals("Today", days[0].label)
    assertEquals(0, days[0].code)
    assertEquals(70, days[0].hi) // 70.4 -> 70
    assertEquals(51, days[0].lo) // 50.6 -> 51

    // Index > 0 is the weekday of that date (not "Today", never blank).
    val expectedWeekday =
        SimpleDateFormat("EEE", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-08")!!)
    assertEquals(expectedWeekday, days[1].label)
    assertNotEquals("Today", days[1].label)
  }

  @Test
  fun `hourly drops past hours, labels Now then clock, caps at 12`() {
    // now = 13:30 -> cutoff 12:30, so 10/11/12:00 are dropped; 13:00 is the first kept.
    val hours = Weather.parseForecast(sample, utc("2026-06-07T13:30")).hours
    assertEquals(12, hours.size) // 15 candidates from 13:00, capped to 12

    assertEquals("Now", hours[0].label)
    assertEquals(60, hours[0].temp) // 13:00, 60.4 -> 60
    assertEquals(61, hours[0].code) // weather_code flows through

    assertEquals("2 PM", hours[1].label) // 14:00
    assertEquals(62, hours[1].temp) // 61.6 -> 62

    // 12th kept entry is 00:00 the next day.
    assertEquals("12 AM", hours.last().label)
    assertEquals(3, hours.last().code)
  }

  @Test
  fun `hourly labels use 24-hour clock when requested`() {
    val hours = Weather.parseForecast(sample, utc("2026-06-07T13:30"), use24Hour = true).hours
    assertEquals("Now", hours[0].label) // first kept hour is always "Now"
    assertEquals("14", hours[1].label) // 14:00 -> bare 24h hour
    assertEquals("00", hours.last().label) // midnight -> "00", not "12 AM"
  }

  @Test
  fun `hourly is empty when every hour is in the past`() {
    val hours = Weather.parseForecast(sample, utc("2030-01-01T00:00")).hours
    assertTrue(hours.isEmpty())
  }

  @Test
  fun `hourly current selection follows weather offset rather than device zone`() {
    val pacific = sample.replace("\"daily\": {", "\"utc_offset_seconds\": -25200, \"daily\": {")
    val hours = Weather.parseForecast(pacific, utc("2026-06-07T20:30")).hours
    assertEquals(60, hours.first().temp) // 13:00 Pacific, not 20:00 UTC.
    assertEquals("2 PM", hours[1].label)
  }

  private fun solar(offset: Int, sunrise: String, sunset: String) =
      """{"utc_offset_seconds":$offset,"daily":{"sunrise":["$sunrise"],"sunset":["$sunset"]}}"""

  @Test
  fun `solar instants use location offset and labels retain the location clock`() {
    val sun = Weather.parseSunTimes(solar(-25200, "2026-06-07T06:30", "2026-06-07T20:30"))
    assertEquals(utc("2026-06-07T13:30"), sun.sunriseMillis)
    assertEquals(utc("2026-06-08T03:30"), sun.sunsetMillis)
    assertEquals("6:30 AM" to "8:30 PM", sun.formatted(false))
    assertEquals("06:30" to "20:30", sun.formatted(true))
    assertEquals(810, sun.minuteOfDay(utc("2026-06-07T20:30")))
    assertEquals(1230, sun.minuteOfDay(sun.sunsetMillis))
  }

  @Test
  fun `quarter hour offset crosses UTC date without changing solar labels`() {
    val sun = Weather.parseSunTimes(solar(20700, "2026-06-07T05:30", "2026-06-07T19:30"))
    assertEquals(utc("2026-06-06T23:45"), sun.sunriseMillis)
    assertEquals(utc("2026-06-07T13:45"), sun.sunsetMillis)
    assertEquals("05:30" to "19:30", sun.formatted(true))
    assertEquals(15, sun.minuteOfDay(utc("2026-06-06T18:30")))
  }

  @Test
  fun `parsed solar times remain stable when device timezone changes`() {
    val sun = Weather.parseSunTimes(solar(-25200, "2026-06-07T06:30", "2026-06-07T20:30"))
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    assertEquals("06:30" to "20:30", sun.formatted(true))
    assertEquals(810, sun.minuteOfDay(utc("2026-06-07T20:30")))
  }

  @Test
  fun `hourly offset is fixed across response DST boundaries`() {
    val pacific = sample.replace("\"daily\": {", "\"utc_offset_seconds\": -28800, \"timezone\": \"America/Los_Angeles\", \"daily\": {")
    // In June the IANA zone is -7; this response explicitly states -8.
    val hours = Weather.parseForecast(pacific, utc("2026-06-07T21:30"), true).hours
    assertEquals(60, hours.first().temp)
    assertEquals("14", hours[1].label)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `out of range provider offset is rejected`() {
    Weather.parseSunTimes(solar(1000000, "2026-06-07T06:30", "2026-06-07T20:30"))
  }

  @Test
  fun `forecast url carries coordinates, span and the chosen unit`() {
    val f = Weather.forecastUrl(1.5, -2.25, fahrenheit = true)
    assertTrue(f.contains("latitude=1.5"))
    assertTrue(f.contains("longitude=-2.25"))
    assertTrue(f.contains("forecast_days=7"))
    assertTrue(f.contains("timezone=auto"))
    assertTrue(f.contains("temperature_unit=fahrenheit"))

    val c = Weather.forecastUrl(1.5, -2.25, fahrenheit = false)
    assertTrue(c.contains("temperature_unit=celsius"))
  }
  @Test fun `solar horizon carries both actual days using one response offset`() {
    val json = """{"utc_offset_seconds":-25200,"daily":{"sunrise":["2026-06-07T06:30","2026-06-08T06:32"],"sunset":["2026-06-07T20:30","2026-06-08T20:29"]}}"""
    val days = Weather.parseSunDays(json)
    assertEquals(2, days.size)
    assertEquals(utc("2026-06-08T13:32"), days[1].sunriseMillis)
    assertEquals(utc("2026-06-09T03:29"), days[1].sunsetMillis)
    assertEquals(days.first(), Weather.parseSunTimes(json))
  }

  @Test fun `empty solar horizon has no predicted events`() {
    assertTrue(Weather.parseSunDays("""{"utc_offset_seconds":0,"daily":{"sunrise":[],"sunset":[]}}""").isEmpty())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `incomplete solar pair is rejected`() {
    Weather.parseSunDays("""{"utc_offset_seconds":0,"daily":{"sunrise":["2026-06-07T06:30"],"sunset":[]}}""")
  }

  @Test fun `solar horizon request expands days without another query or changing display fetch`() {
    assertTrue(Weather.sunTimesUrl(1.5, -2.25).endsWith("forecast_days=1"))
    assertTrue(Weather.sunTimesUrl(1.5, -2.25, 2).endsWith("forecast_days=2"))
    assertEquals(Weather.sunTimesUrl(1.5, -2.25), Weather.sunTimesUrl(1.5, -2.25, 2).replace("forecast_days=2", "forecast_days=1"))
  }

}
