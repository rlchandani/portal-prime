/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.net.Uri
import android.os.Bundle
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Pure native public fixtures; callbacks count only, never touch alarms, audio, UI or preferences. */
internal object GoldenHourDeviceChecks {
  fun run(): Bundle {
    val checks = JSONArray()
    var failureCount = 0
    fun expect(name: String, assertion: () -> Boolean) {
      // Only fixed assertion names and booleans are reported, including on exceptions.
      val passed = try { assertion() } catch (_: Throwable) { false }
      checks.put(JSONObject().put("name", name).put("passed", passed))
      if (!passed) failureCount++
    }

    // Tomorrow's sunrise is two minutes later, and sunset three minutes earlier.
    // Each expected instant is set explicitly in UTC, without production offset arithmetic.
    val ordinary = fixture(-25_200, "America/Los_Angeles", listOf(
        "2026-10-18T06:40" to "2026-10-18T18:10",
        "2026-10-19T06:42" to "2026-10-19T18:07",
    ))
    val firstDay = Weather.SunTimes(utc(2026, 10, 18, 13, 40),
        utc(2026, 10, 19, 1, 10), -25_200)
    val secondDay = Weather.SunTimes(utc(2026, 10, 19, 13, 42),
        utc(2026, 10, 20, 1, 7), -25_200)
    expect("parser_preserves_all_daily_absolute_instants_and_offset") {
      Weather.parseSunDays(ordinary) == listOf(firstDay, secondDay)
    }
    expect("noon_selects_today_sunset_and_changed_tomorrow_sunrise") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary),
          utc(2026, 10, 18, 19, 0)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 19, 13, 42), utc(2026, 10, 19, 1, 10))
    }
    expect("after_sunset_selects_both_changed_tomorrow_events") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary),
          utc(2026, 10, 19, 2, 0)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 19, 13, 42), utc(2026, 10, 20, 1, 7))
    }
    expect("sunrise_equality_excludes_just_fired_event") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary),
          utc(2026, 10, 18, 13, 40)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 19, 13, 42), utc(2026, 10, 19, 1, 10))
    }
    expect("sunset_equality_excludes_just_fired_event") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary),
          utc(2026, 10, 19, 1, 10)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 19, 13, 42), utc(2026, 10, 20, 1, 7))
    }
    expect("one_millisecond_before_sunrise_keeps_today_event") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary),
          utc(2026, 10, 18, 13, 40) - 1L) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 18, 13, 40), utc(2026, 10, 19, 1, 10))
    }
    expect("empty_provider_horizon_has_no_events") {
      val days = Weather.parseSunDays(fixture(0, "UTC", emptyList()))
      days.isEmpty() && ChimeScheduler.nextGoldenHourTimes(days,
          utc(2026, 10, 18, 0, 0)) == ChimeScheduler.GoldenHourTimes(null, null)
    }
    expect("entirely_past_horizon_does_not_guess_another_day") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary),
          utc(2026, 10, 20, 2, 0)) == ChimeScheduler.GoldenHourTimes(null, null)
    }
    val oneDay = fixture(-25_200, "America/Los_Angeles", listOf(
        "2026-10-18T06:40" to "2026-10-18T18:10",
    ))
    expect("partial_horizon_retains_only_future_sunset") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(oneDay),
          utc(2026, 10, 18, 19, 0)) ==
          ChimeScheduler.GoldenHourTimes(null, utc(2026, 10, 19, 1, 10))
    }
    expect("one_day_after_sunset_returns_two_nulls") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(oneDay),
          utc(2026, 10, 19, 2, 0)) == ChimeScheduler.GoldenHourTimes(null, null)
    }
    expect("partial_horizon_retains_only_future_sunrise") {
      // A synthetic partial record tests the selector's independent columns.
      val partial = Weather.SunTimes(utc(2026, 10, 19, 13, 42),
          utc(2026, 10, 19, 1, 10), -25_200)
      ChimeScheduler.nextGoldenHourTimes(listOf(partial), utc(2026, 10, 19, 2, 0)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 19, 13, 42), null)
    }

    // LA crosses DST on March 8. Open-Meteo supplies one fixed -08:00 offset
    // for this entire response; the named timezone must not override that offset.
    // Local sunrise changes 58 minutes, sunset 61 minutes (different day length).
    val dst = fixture(-28_800, "America/Los_Angeles", listOf(
        "2026-03-07T06:25" to "2026-03-07T18:01",
        "2026-03-08T07:23" to "2026-03-08T19:02",
    ))
    expect("dst_response_uses_root_fixed_offset_for_every_day") {
      Weather.parseSunDays(dst) == listOf(
          Weather.SunTimes(utc(2026, 3, 7, 14, 25), utc(2026, 3, 8, 2, 1), -28_800),
          Weather.SunTimes(utc(2026, 3, 8, 15, 23), utc(2026, 3, 9, 3, 2), -28_800))
    }
    expect("dst_and_changed_day_length_do_not_use_24_hour_guess") {
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(dst), utc(2026, 3, 8, 3, 0)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 3, 8, 15, 23), utc(2026, 3, 9, 3, 2))
    }
    expect("out_of_order_days_select_earliest_future_columns") {
      val reversed = fixture(-25_200, "America/Los_Angeles", listOf(
          "2026-10-19T06:42" to "2026-10-19T18:07",
          "2026-10-18T06:40" to "2026-10-18T18:10",
      ))
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(reversed),
          utc(2026, 10, 18, 19, 0)) ==
          ChimeScheduler.GoldenHourTimes(utc(2026, 10, 19, 13, 42), utc(2026, 10, 19, 1, 10))
    }
    expect("positive_fractional_offset_preserves_both_days") {
      val json = fixture(20_700, "Asia/Kathmandu", listOf(
          "2026-04-01T06:10" to "2026-04-01T20:20",
          "2026-04-02T06:09" to "2026-04-02T20:21",
      ))
      Weather.parseSunDays(json) == listOf(
          Weather.SunTimes(utc(2026, 4, 1, 0, 25), utc(2026, 4, 1, 14, 35), 20_700),
          Weather.SunTimes(utc(2026, 4, 2, 0, 24), utc(2026, 4, 2, 14, 36), 20_700))
    }
    expect("negative_fractional_offset_crosses_utc_midnight") {
      val json = fixture(-13_500, "GMT-03:45", listOf(
          "2026-04-01T06:10" to "2026-04-01T20:20",
          "2026-04-02T06:08" to "2026-04-02T20:22",
      ))
      Weather.parseSunDays(json) == listOf(
          Weather.SunTimes(utc(2026, 4, 1, 9, 55), utc(2026, 4, 2, 0, 5), -13_500),
          Weather.SunTimes(utc(2026, 4, 2, 9, 53), utc(2026, 4, 3, 0, 7), -13_500))
    }
    expect("one_day_ui_parser_still_returns_first_provider_day") {
      Weather.parseSunTimes(ordinary) == firstDay && Weather.parseSunTimes(oneDay) == firstDay
    }
    expect("default_solar_url_selects_one_day_once") {
      solarUrlMatches(Weather.sunTimesUrl(37.25, -122.5), "1")
    }
    expect("explicit_one_day_solar_url_selects_one_day_once") {
      solarUrlMatches(Weather.sunTimesUrl(37.25, -122.5, 1), "1")
    }
    expect("two_day_solar_url_selects_two_days_once") {
      solarUrlMatches(Weather.sunTimesUrl(37.25, -122.5, 2), "2")
    }

    expect("work_disabled_before_begin_allocates_no_ticket") {
      GoldenHourWork().begin { false } == null
    }
    expect("work_disabled_after_fixture_fetch_rejects_commit") {
      val work = GoldenHourWork()
      var enabled = true
      var actions = 0
      val token = requireNotNull(work.begin { enabled })
      // Real pure parser/selector stand in for a completed fetch; no network is invoked.
      ChimeScheduler.nextGoldenHourTimes(Weather.parseSunDays(ordinary), utc(2026, 10, 18, 19, 0))
      enabled = false
      val committed = work.commit(token, { enabled }) { actions++ }
      !committed && actions == 0
    }
    expect("work_cancel_then_reenable_rejects_old_ticket_and_accepts_new") {
      val work = GoldenHourWork()
      var enabled = true
      var cancellations = 0
      var actions = 0
      val old = requireNotNull(work.begin { enabled })
      enabled = false
      work.cancel { cancellations++ }
      val oldObsolete = !work.isCurrent(old)
      enabled = true
      val rejected = !work.commit(old, { enabled }) { actions++ }
      val fresh = requireNotNull(work.begin { enabled })
      val freshCurrent = work.isCurrent(fresh)
      val committed = work.commit(fresh, { enabled }) { actions++ }
      oldObsolete && rejected && fresh != old && freshCurrent && committed &&
          cancellations == 1 && actions == 1
    }
    expect("work_new_task_supersedes_old_ticket_before_commit") {
      val work = GoldenHourWork()
      var actions = 0
      val old = requireNotNull(work.begin { true })
      val fresh = requireNotNull(work.begin { true })
      val oldObsolete = !work.isCurrent(old)
      val freshCurrent = work.isCurrent(fresh)
      val rejected = !work.commit(old, { true }) { actions++ }
      val committed = work.commit(fresh, { true }) { actions++ }
      old != fresh && oldObsolete && freshCurrent && rejected && committed && actions == 1
    }
    expect("work_current_enabled_ticket_commits_counter_action_once") {
      val work = GoldenHourWork()
      var actions = 0
      val token = requireNotNull(work.begin { true })
      val current = work.isCurrent(token)
      val committed = work.commit(token, { true }) { actions++ }
      current && committed && actions == 1
    }

    expect("cache_success_reused_from_startup_to_receiver_with_one_load") {
      var now = 1_000L
      var loads = 0
      val cache = GoldenHourSolarCache(nowMillis = { now })
      val value = listOf(firstDay, secondDay)
      val startup = cache.get(1L) { loads++; value }
      now = 2_000L
      val receiver = cache.get(1L) { loads++; emptyList() }
      startup == value && receiver == value && loads == 1
    }
    expect("cache_null_failure_reused_without_second_query") {
      var now = 1_000L
      var loads = 0
      val cache = GoldenHourSolarCache(nowMillis = { now })
      val startup = cache.get(1L) { loads++; null }
      now = 30_000L // Still 29 seconds after completion.
      val receiver = cache.get(1L) { loads++; listOf(secondDay) }
      startup == null && receiver == null && loads == 1
    }
    expect("cache_exception_failure_reused_without_second_query") {
      var now = 1_000L
      var loads = 0
      val cache = GoldenHourSolarCache(nowMillis = { now })
      val startup = cache.get(1L) {
        loads++
        throw IllegalStateException("public synthetic solar failure")
      }
      now = 16_000L
      val receiver = cache.get(1L) { loads++; listOf(secondDay) }
      startup == null && receiver == null && loads == 1
    }
    expect("cache_new_location_revision_loads_and_reuses_new_value") {
      var loads = 0
      val cache = GoldenHourSolarCache(nowMillis = { 1_000L })
      val old = cache.get(1L) { loads++; listOf(firstDay) }
      val fresh = cache.get(2L) { loads++; listOf(secondDay) }
      val reused = cache.get(2L) { loads++; emptyList() }
      old == listOf(firstDay) && fresh == listOf(secondDay) &&
          reused == listOf(secondDay) && loads == 2
    }
    expect("cache_refreshes_after_31_seconds_using_injected_clock") {
      var now = 1_000L
      var loads = 0
      val cache = GoldenHourSolarCache(nowMillis = { now })
      val first = cache.get(1L) { loads++; listOf(firstDay) }
      now = 30_000L // A completed result remains fresh at 29 seconds.
      val stillFresh = cache.get(1L) { loads++; emptyList() }
      now = 32_000L // 31 seconds after the first load completed.
      val refreshed = cache.get(1L) { loads++; listOf(secondDay) }
      first == listOf(firstDay) && stillFresh == listOf(firstDay) &&
          refreshed == listOf(secondDay) && loads == 2
    }

    val passed = failureCount == 0
    val report = JSONObject().put("command", "goldenHour").put("passed", passed)
        .put("completed", passed).put("checkCount", checks.length())
        .put("passedCount", checks.length() - failureCount).put("failureCount", failureCount)
        .put("scope", "native solar parser, next-event selector, work generation guard and solar cache; no UI or live alarm execution claims")
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("checks", checks)
    return Bundle().apply {
      putBoolean("goldenHourCompleted", passed)
      putString("goldenHourJson", report.toString())
      putInt("goldenHourCheckCount", checks.length())
      putInt("goldenHourFailureCount", failureCount)
    }
  }

  private fun fixture(offsetSeconds: Int, timezone: String,
      days: List<Pair<String, String>>): String {
    val dates = JSONArray()
    val rises = JSONArray()
    val sets = JSONArray()
    for ((sunrise, sunset) in days) {
      dates.put(sunrise.substring(0, 10))
      rises.put(sunrise)
      sets.put(sunset)
    }
    return JSONObject().put("utc_offset_seconds", offsetSeconds).put("timezone", timezone)
        .put("daily", JSONObject().put("time", dates).put("sunrise", rises).put("sunset", sets))
        .toString()
  }

  // Inspect one constructed request only. Never resolve, connect or fetch this public URL.
  private fun solarUrlMatches(url: String, horizon: String): Boolean {
    val uri = Uri.parse(url)
    return uri.scheme == "https" && uri.host == "api.open-meteo.com" &&
        uri.path == "/v1/forecast" && uri.fragment == null &&
        uri.getQueryParameters("latitude") == listOf("37.25") &&
        uri.getQueryParameters("longitude") == listOf("-122.5") &&
        uri.getQueryParameters("daily") == listOf("sunrise,sunset") &&
        uri.getQueryParameters("timezone") == listOf("auto") &&
        uri.getQueryParameters("forecast_days") == listOf(horizon)
  }

  private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
      Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        clear()
        isLenient = false
        set(year, month - 1, day, hour, minute, 0)
      }.timeInMillis
}
