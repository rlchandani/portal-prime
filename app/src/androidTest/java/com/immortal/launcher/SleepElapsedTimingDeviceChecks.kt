/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Read-only native clock samples and public counters; never schedule alarms or change state. */
internal object SleepElapsedTimingDeviceChecks {
  const val CHECK_COUNT = 22

  fun run(): Bundle {
    val checks = JSONArray()
    val failures = JSONArray()
    var unexpectedExceptionCount = 0
    fun check(name: String, assertion: () -> Boolean) {
      var unexpected = false
      val passed = try {
        assertion()
      } catch (_: Throwable) {
        // Retain no clock values, exception messages, stacks or types.
        unexpected = true
        unexpectedExceptionCount++
        false
      }
      val row = JSONObject().put("name", name).put("passed", passed)
          .put("unexpectedException", unexpected)
      checks.put(row)
      if (!passed) failures.put(row)
    }

    check("clock.elapsed_realtime_bracket") {
      val before = SystemClock.elapsedRealtime()
      val actual = SleepElapsedTiming.clockTime()
      val after = SystemClock.elapsedRealtime()
      actual in before..after
    }
    check("idle.default_elapsed_realtime_bracket") {
      val before = SystemClock.elapsedRealtime()
      val actual = SleepElapsedTiming.idleDeadline(7)
      val after = SystemClock.elapsedRealtime()
      actual in (before + 420_000L)..(after + 420_000L)
    }
    check("wake.default_elapsed_now_recent") {
      SleepElapsedTiming.recentlyWoken(SystemClock.elapsedRealtime())
    }
    check("idle.elapsed_alarm_type") {
      SleepElapsedTiming.IDLE_ALARM_TYPE == AlarmManager.ELAPSED_REALTIME_WAKEUP
    }
    check("idle.alarm_wakes_device") {
      SleepElapsedTiming.IDLE_ALARM_TYPE == AlarmManager.RTC_WAKEUP ||
          SleepElapsedTiming.IDLE_ALARM_TYPE == AlarmManager.ELAPSED_REALTIME_WAKEUP
    }
    check("grace.four_seconds") { SleepElapsedTiming.USER_WAKE_GRACE_MS == 4_000L }
    check("grace.wake_instant_inclusive") {
      SleepElapsedTiming.recentlyWoken(10_000L, 10_000L)
    }
    check("grace.four_seconds_inclusive") {
      SleepElapsedTiming.recentlyWoken(10_000L, 14_000L)
    }
    check("grace.expired_next_millisecond") {
      !SleepElapsedTiming.recentlyWoken(10_000L, 14_001L)
    }
    check("wake.zero_absent") {
      !SleepElapsedTiming.recentlyWoken(0L, 0L) &&
          !SleepElapsedTiming.recentlyWoken(0L, 1_000L)
    }
    check("wake.negative_absent") { !SleepElapsedTiming.recentlyWoken(-1L, 0L) }
    check("wake.future_rejected") { !SleepElapsedTiming.recentlyWoken(10_001L, 10_000L) }
    check("wake.large_counter_recent") {
      SleepElapsedTiming.recentlyWoken(9_000_000_000L, 9_000_004_000L)
    }
    check("wake.large_counter_expired") {
      !SleepElapsedTiming.recentlyWoken(9_000_000_000L, 9_000_004_001L)
    }
    check("idle.one_minute_long_counter") {
      SleepElapsedTiming.idleDeadline(1, 9_000_000_000L) == 9_000_060_000L
    }
    check("idle.several_minutes_long_counter") {
      SleepElapsedTiming.idleDeadline(7, 9_000_000_000L) == 9_000_420_000L
    }
    check("idle.sixty_minutes_long_counter") {
      SleepElapsedTiming.idleDeadline(60, 9_000_000_000L) == 9_003_600_000L
    }
    check("overnight.recent_wake_leaves") {
      SleepScheduler.classifyOvernightRedream(true, false, false,
          SleepElapsedTiming.recentlyWoken(10_000L, 14_000L)) ==
          SleepScheduler.OvernightRedream.LEAVE
    }
    check("overnight.expired_wake_reblanks") {
      SleepScheduler.classifyOvernightRedream(true, false, false,
          SleepElapsedTiming.recentlyWoken(10_000L, 14_001L)) ==
          SleepScheduler.OvernightRedream.REBLANK
    }
    check("overnight.night_clock_precedes_recent_wake") {
      SleepScheduler.classifyOvernightRedream(true, false, true,
          SleepElapsedTiming.recentlyWoken(10_000L, 14_000L)) ==
          SleepScheduler.OvernightRedream.RELAUNCH
    }
    check("overnight.session_precedes_night_clock") {
      SleepScheduler.classifyOvernightRedream(true, true, true,
          SleepElapsedTiming.recentlyWoken(10_000L, 14_001L)) ==
          SleepScheduler.OvernightRedream.LEAVE
    }
    check("overnight.outside_window_relaunches") {
      SleepScheduler.classifyOvernightRedream(false, true, true,
          SleepElapsedTiming.recentlyWoken(10_000L, 14_000L)) ==
          SleepScheduler.OvernightRedream.RELAUNCH
    }

    val completed = checks.length() == CHECK_COUNT
    val passed = completed && failures.length() == 0 && unexpectedExceptionCount == 0
    val report = JSONObject().put("command", "sleepElapsedTiming")
        .put("completed", completed).put("passed", passed)
        .put("checkCount", checks.length()).put("passedCount", checks.length() - failures.length())
        .put("failureCount", failures.length()).put("unexpectedExceptionCount", unexpectedExceptionCount)
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("checks", checks).put("failures", failures)
    return Bundle().apply {
      putBoolean("sleepElapsedTimingCompleted", completed)
      putBoolean("sleepElapsedTimingPassed", passed)
      putInt("sleepElapsedTimingCheckCount", checks.length())
      putInt("sleepElapsedTimingPassedCount", checks.length() - failures.length())
      putInt("sleepElapsedTimingFailureCount", failures.length())
      putInt("sleepElapsedTimingUnexpectedExceptionCount", unexpectedExceptionCount)
      putString("sleepElapsedTimingJson", report.toString())
    }
  }
}
