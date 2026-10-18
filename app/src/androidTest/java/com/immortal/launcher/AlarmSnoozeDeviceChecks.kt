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

/** Public counter fixtures only; no alarms, preferences, network, audio or UI state writes. */
internal object AlarmSnoozeDeviceChecks {
  const val CHECK_COUNT = 8

  fun run(): Bundle {
    val checks = JSONArray()
    val failures = JSONArray()
    var unexpectedExceptionCount = 0
    fun expect(name: String, assertion: () -> Boolean) {
      var unexpected = false
      val passed = try {
        assertion()
      } catch (_: Throwable) {
        // Report no exception messages, types, stacks or private values.
        unexpected = true
        unexpectedExceptionCount++
        false
      }
      val row = JSONObject().put("name", name).put("passed", passed)
          .put("unexpectedException", unexpected)
      checks.put(row)
      if (!passed) failures.put(row)
    }

    expect("default_elapsed_realtime_deadline_bracket") {
      val calls = mutableListOf<Pair<Int, Long>>()
      var fallbackCalls = 0
      val before = SystemClock.elapsedRealtime()
      AlarmSnooze.schedule({ type, at -> calls += type to at }, { _, _ -> fallbackCalls++ })
      val after = SystemClock.elapsedRealtime()
      calls.size == 1 && fallbackCalls == 0 &&
          calls.single().first == AlarmManager.ELAPSED_REALTIME_WAKEUP &&
          calls.single().second in (before + 540_000L)..(after + 540_000L)
    }
    expect("default_requests_elapsed_realtime_wakeup") {
      val types = mutableListOf<Int>()
      var fallbackCalls = 0
      AlarmSnooze.schedule({ type, _ -> types += type }, { _, _ -> fallbackCalls++ })
      types == listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP) && fallbackCalls == 0
    }
    expect("explicit_public_uptime_deadline") {
      val calls = mutableListOf<Pair<Int, Long>>()
      AlarmSnooze.schedule({ type, at -> calls += type to at }, { _, _ -> }, 10_000L)
      calls == listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP to 550_000L)
    }
    expect("exact_success_skips_fallback") {
      var exactCalls = 0
      var fallbackCalls = 0
      AlarmSnooze.schedule({ _, _ -> exactCalls++ }, { _, _ -> fallbackCalls++ }, 10_000L)
      exactCalls == 1 && fallbackCalls == 0
    }
    expect("exact_failure_fallback_order_and_identical_deadline") {
      val order = mutableListOf<String>()
      val calls = mutableListOf<Pair<Int, Long>>()
      AlarmSnooze.schedule({ type, at ->
        order += "exact"
        calls += type to at
        throw SecurityException()
      }, { type, at ->
        order += "fallback"
        calls += type to at
      }, 10_000L)
      order == listOf("exact", "fallback") &&
          calls == listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP to 550_000L,
              AlarmManager.ELAPSED_REALTIME_WAKEUP to 550_000L)
    }
    expect("both_callback_failures_contained_without_retries") {
      val order = mutableListOf<String>()
      AlarmSnooze.schedule({ _, _ ->
        order += "exact"
        throw SecurityException()
      }, { _, _ ->
        order += "fallback"
        throw IllegalStateException()
      }, 10_000L)
      order == listOf("exact", "fallback")
    }
    expect("repeated_calls_independent_after_failure_and_repeated_uptime") {
      val order = mutableListOf<String>()
      val deadlines = mutableListOf<Long>()
      AlarmSnooze.schedule({ _, at ->
        order += "exact"
        deadlines += at
        throw SecurityException()
      }, { _, at ->
        order += "fallback"
        deadlines += at
        throw IllegalStateException()
      }, 1_000L)
      for (uptime in listOf(2_000L, 1_000L)) {
        AlarmSnooze.schedule({ _, at -> order += "exact"; deadlines += at },
            { _, _ -> order += "fallback" }, uptime)
      }
      order == listOf("exact", "fallback", "exact", "exact") &&
          deadlines == listOf(541_000L, 541_000L, 542_000L, 541_000L)
    }
    expect("public_uptime_beyond_int_retains_long_deadline") {
      val calls = mutableListOf<Pair<Int, Long>>()
      AlarmSnooze.schedule({ type, at -> calls += type to at }, { _, _ -> }, 5_000_000_000L)
      calls == listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP to 5_000_540_000L)
    }

    val completed = checks.length() == CHECK_COUNT
    val passed = completed && failures.length() == 0
    val report = JSONObject().put("completed", completed).put("passed", passed)
        .put("checkCount", checks.length()).put("passedCount", checks.length() - failures.length())
        .put("failureCount", failures.length()).put("unexpectedExceptionCount", unexpectedExceptionCount)
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("checks", checks).put("failures", failures)
    return Bundle().apply {
      putString("command", "alarmSnooze")
      putBoolean("alarmSnoozeCompleted", completed)
      putBoolean("alarmSnoozePassed", passed)
      putString("alarmSnoozeJson", report.toString())
    }
  }
}
