/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Public native parser fixtures only: no UI, network, clock, locale, zone or preference mutations. */
internal object WeatherTimeDeviceChecks {
  private data class Scenario(
      val name: String,
      val offsetSeconds: Int,
      val timezone: String,
      val sunriseUtc: Long,
      val sunsetUtc: Long,
      val nowUtc: Long,
      val nowMinute: Int,
      val forecastNowUtc: Long,
  )

  fun run(): Bundle {
    val deviceZone = TimeZone.getDefault()
    val deviceLocale = Locale.getDefault()
    val checks = JSONArray()
    val failures = JSONArray()
    val fixtures = JSONArray()
    fun expect(name: String, expected: Any, actual: Any) {
      val passed = expected == actual
      val detail = JSONObject().put("name", name).put("passed", passed)
          .put("expected", expected).put("actual", actual)
      checks.put(detail)
      if (!passed) failures.put(detail)
    }
    fun stage(name: String, block: () -> Unit) {
      try {
        block()
      } catch (error: Throwable) {
        val detail = JSONObject().put("name", name).put("passed", false)
            .put("expected", "fixture completes without exception")
            .put("actual", JSONObject().put("errorType", error.javaClass.name)
                .put("message", error.message ?: ""))
        checks.put(detail)
        failures.put(detail)
      }
    }
    val scenarios = listOf(
        Scenario("negative_quarter", -13_500, "GMT-03:45",
            utc(2026, 6, 7, 9, 55), utc(2026, 6, 8, 0, 5),
            utc(2026, 6, 8, 0, 0), 20 * 60 + 15, utc(2026, 6, 7, 17, 15)),
        Scenario("positive_quarter", 20_700, "Asia/Kathmandu",
            utc(2026, 6, 7, 0, 25), utc(2026, 6, 7, 14, 35),
            utc(2026, 6, 6, 23, 59), 5 * 60 + 44, utc(2026, 6, 7, 7, 45)),
        Scenario("positive_next_day", 45_900, "Pacific/Chatham",
            utc(2026, 6, 6, 17, 25), utc(2026, 6, 7, 7, 35),
            utc(2026, 6, 7, 0, 0), 12 * 60 + 45, utc(2026, 6, 7, 0, 45)),
    )
    var mismatchedZones = 0
    for (scenario in scenarios) {
      val sunJson = sunFixture(scenario.offsetSeconds, scenario.timezone)
      val hourlyJson = forecastFixture(scenario.offsetSeconds, scenario.timezone)
      val mismatch = deviceZone.getOffset(scenario.sunriseUtc) / 1000 != scenario.offsetSeconds
      if (mismatch) mismatchedZones++
      fixtures.put(JSONObject().put("name", scenario.name)
          .put("sunInput", sunJson).put("forecastInput", hourlyJson)
          .put("referenceNowUtcMillis", scenario.nowUtc)
          .put("forecastNowUtcMillis", scenario.forecastNowUtc)
          .put("providerOffsetDiffersFromDeviceAtSunrise", mismatch))
      stage("${scenario.name}.sun") {
        val sun = Weather.parseSunTimes(sunJson.toString())
        expect("${scenario.name}.offset", scenario.offsetSeconds, sun.utcOffsetSeconds)
        expect("${scenario.name}.sunrise_absolute_utc", scenario.sunriseUtc, sun.sunriseMillis)
        expect("${scenario.name}.sunset_absolute_utc", scenario.sunsetUtc, sun.sunsetMillis)
        for (use24 in listOf(true, false)) {
          val labels = sun.formatted(use24)
          expect("${scenario.name}.sunrise_label_$use24", timeLabel(6, 10, use24), labels.first)
          expect("${scenario.name}.sunset_label_$use24", timeLabel(20, 20, use24), labels.second)
        }
        expect("${scenario.name}.sunrise_minute", 6 * 60 + 10, sun.minuteOfDay(sun.sunriseMillis))
        expect("${scenario.name}.sunset_minute", 20 * 60 + 20, sun.minuteOfDay(sun.sunsetMillis))
        expect("${scenario.name}.now_minute_across_utc_midnight", scenario.nowMinute,
            sun.minuteOfDay(scenario.nowUtc))
        expect("${scenario.name}.daylight_same_offset",
            scenario.nowMinute in (6 * 60 + 10)..(20 * 60 + 20),
            sun.minuteOfDay(scenario.nowUtc) in
                sun.minuteOfDay(sun.sunriseMillis)..sun.minuteOfDay(sun.sunsetMillis))
      }
      stage("${scenario.name}.hours") {
        val forecast = Weather.parseForecast(hourlyJson.toString(), scenario.forecastNowUtc,
            use24Hour = true)
        val hours = forecast.hours
        expect("${scenario.name}.hours_cap", 12, hours.size)
        expect("${scenario.name}.current_hour_label", "Now", hours.first().label)
        expect("${scenario.name}.current_hour_temperature", 103, hours.first().temp)
        expect("${scenario.name}.current_hour_code", 3, hours.first().code)
        for (index in hours.indices) {
          val inputIndex = index + 3 // 13:30 local drops 10, 11, 12; first kept is 13:00.
          val expectedLabel = if (index == 0) "Now" else hourLabel((10 + inputIndex) % 24)
          expect("${scenario.name}.hour_${index}_label", expectedLabel, hours[index].label)
          expect("${scenario.name}.hour_${index}_temperature", 100 + inputIndex, hours[index].temp)
          expect("${scenario.name}.hour_${index}_code", inputIndex % 4, hours[index].code)
        }
        expect("${scenario.name}.next_midnight_24h_label", hourLabel(0), hours.last().label)
        for ((pass, use24) in listOf(false, true, false).withIndex()) {
          val labels = forecast.hourLabels(use24)
          for (index in labels.indices) {
            val hour = (13 + index) % 24
            val expected = if (index == 0) "Now" else {
              val fmt = SimpleDateFormat(if (use24) "HH" else "h a", deviceLocale)
                  .apply { timeZone = TimeZone.getTimeZone("UTC") }
              fmt.format(Date(utc(2026, 6, 7, hour, 0)))
            }
            expect("${scenario.name}.cached_label_${pass}_$index", expected, labels[index])
          }
        }
        val past = Weather.parseForecast(hourlyJson.toString(), utc(2030, 1, 1, 0, 0),
            use24Hour = true).hours
        expect("${scenario.name}.all_past_hours_empty", 0, past.size)
      }
    }
    stage("device_zone_mismatch_covered") {
      expect("at_least_one_provider_offset_differs_from_device", true, mismatchedZones > 0)
    }
    stage("missing_offset_hourly_fallback") {
      val fixture = forecastFixture(null, "public missing-offset legacy fixture")
      val now = Calendar.getInstance(deviceZone, Locale.US).apply {
        clear()
        set(2026, Calendar.JUNE, 7, 13, 30, 0)
      }.timeInMillis
      fixtures.put(JSONObject().put("name", "missing_offset_hourly_fallback")
          .put("forecastInput", fixture).put("forecastNowUtcMillis", now))
      val hours = Weather.parseForecast(fixture.toString(), now, use24Hour = true).hours
      expect("missing_offset.current_hour_label", "Now", hours.first().label)
      expect("missing_offset.current_hour_temperature", 103, hours.first().temp)
      expect("missing_offset.next_hour_label", hourLabel(14), hours[1].label)
    }
    expect("default_timezone_id_unchanged", deviceZone.id, TimeZone.getDefault().id)
    expect("default_timezone_raw_offset_unchanged", deviceZone.rawOffset, TimeZone.getDefault().rawOffset)
    expect("default_locale_unchanged", deviceLocale.toLanguageTag(), Locale.getDefault().toLanguageTag())
    val completed = failures.length() == 0
    val report = JSONObject().put("command", "weatherTime").put("completed", completed)
        .put("scope", "native Weather parsers, labels and minute helper; no UI or alarm execution claims")
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("deviceTimezoneId", deviceZone.id).put("deviceRawOffsetSeconds", deviceZone.rawOffset / 1000)
        .put("locale", deviceLocale.toLanguageTag()).put("scenarioCount", scenarios.size)
        .put("providerDeviceMismatchCount", mismatchedZones).put("checkCount", checks.length())
        .put("passedCount", checks.length() - failures.length()).put("failureCount", failures.length())
        .put("fixtures", fixtures).put("checks", checks).put("failures", failures)
    return Bundle().apply {
      putBoolean("weatherTimeCompleted", completed)
      putString("weatherTimeJson", report.toString())
      putInt("weatherTimeCheckCount", checks.length())
      putInt("weatherTimeFailureCount", failures.length())
    }
  }

  private fun sunFixture(offset: Int, timezone: String): JSONObject = JSONObject()
      .put("utc_offset_seconds", offset).put("timezone", timezone)
      .put("daily", JSONObject().put("time", JSONArray().put("2026-06-07"))
          .put("sunrise", JSONArray().put("2026-06-07T06:10"))
          .put("sunset", JSONArray().put("2026-06-07T20:20")))

  private fun forecastFixture(offset: Int?, timezone: String): JSONObject {
    val times = JSONArray()
    val codes = JSONArray()
    val temps = JSONArray()
    for (index in 0 until 18) {
      val hour = 10 + index
      val day = if (hour < 24) "2026-06-07" else "2026-06-08"
      times.put("${day}T${String.format(Locale.US, "%02d", hour % 24)}:00")
      codes.put(index % 4)
      temps.put(100 + index)
    }
    return JSONObject().apply {
      if (offset != null) put("utc_offset_seconds", offset)
      put("timezone", timezone)
      put("daily", JSONObject().put("time", JSONArray().put("2026-06-07"))
          .put("weather_code", JSONArray().put(0))
          .put("temperature_2m_max", JSONArray().put(110))
          .put("temperature_2m_min", JSONArray().put(90)))
      put("hourly", JSONObject().put("time", times).put("weather_code", codes)
          .put("temperature_2m", temps))
    }
  }

  // Expected absolute instants use explicit UTC fields, never the production parser or offset helper.
  private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
      Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        clear()
        isLenient = false
        set(year, month - 1, day, hour, minute, 0)
      }.timeInMillis

  // Format independent UTC wall-clock fields in the existing locale; never change the global locale.
  private fun timeLabel(hour: Int, minute: Int, use24: Boolean): String =
      SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }.format(Date(utc(2000, 1, 1, hour, minute)))

  private fun hourLabel(hour: Int): String =
      SimpleDateFormat("HH", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }.format(Date(utc(2000, 1, 1, hour, 0)))
}
