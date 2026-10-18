/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Public fixed-time fixtures and package metadata only; no broadcasts or device state writes. */
internal object ClockChangeAlarmsDeviceChecks {
  // 18 dispatcher + 64 schedule + 9 manifest assertions. Every assertion always emits one row.
  const val CHECK_COUNT = 91
  private const val RECEIVER = "com.immortal.launcher.ClockChangeReceiver"
  private val actions = listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)

  private data class Fixture(
      val name: String,
      val from: Calendar,
      val hour: Int,
      val minute: Int,
      val expected: Long?,
      val days: Set<Int> = emptySet(),
      val enabled: Boolean = true,
  )

  fun run(context: Context): Bundle {
    val checks = JSONArray()
    val failures = JSONArray()
    var unexpectedExceptionCount = 0
    fun check(name: String, assertion: () -> Boolean) {
      var unexpected = false
      val passed = try {
        assertion()
      } catch (_: Throwable) {
        // Never retain exception messages, stacks, types or private values in this report.
        unexpected = true
        unexpectedExceptionCount++
        false
      }
      val row = JSONObject().put("name", name).put("passed", passed)
          .put("unexpectedException", unexpected)
      checks.put(row)
      if (!passed) failures.put(row)
    }

    for ((index, action) in actions.withIndex()) {
      check("dispatcher.$index.alarm_false_then_wake_light") {
        val calls = mutableListOf<String>()
        ClockChangeAlarms.rearm(action, { calls += "alarm:$it" }, { calls += "wakeLight" })
        calls == listOf("alarm:false", "wakeLight")
      }
      check("dispatcher.$index.revision_once") {
        val before = ClockChangeAlarms.revision
        ClockChangeAlarms.rearm(action, {}, {})
        ClockChangeAlarms.revision == before + 1
      }
      check("dispatcher.$index.revision_before_callbacks") {
        val before = ClockChangeAlarms.revision
        val seen = mutableListOf<Int>()
        ClockChangeAlarms.rearm(action, { seen += ClockChangeAlarms.revision },
            { seen += ClockChangeAlarms.revision })
        seen == listOf(before + 1, before + 1)
      }
      for ((failureIndex, pair) in listOf(true to false, false to true, true to true).withIndex()) {
        check("dispatcher.$index.independent_failure_$failureIndex") {
          val before = ClockChangeAlarms.revision
          val calls = mutableListOf<String>()
          ClockChangeAlarms.rearm(action,
              alarm = {
                calls += "alarm:$it"
                if (pair.first) throw IOException()
              },
              wakeLight = {
                calls += "wakeLight"
                if (pair.second) throw IllegalArgumentException()
              })
          calls == listOf("alarm:false", "wakeLight") && ClockChangeAlarms.revision == before + 1
        }
      }
    }
    for ((index, action) in listOf(null, "", Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_TICK,
        Intent.ACTION_DATE_CHANGED, "android.intent.action.time_set").withIndex()) {
      check("dispatcher.ignored_$index") {
        val before = ClockChangeAlarms.revision
        var calls = 0
        ClockChangeAlarms.rearm(action, { calls++ }, { calls++ })
        calls == 0 && ClockChangeAlarms.revision == before
      }
    }

    for (fixture in fixtures()) {
      check("schedule.${fixture.name}.alarm") {
        val before = fixture.from.clone() as Calendar
        val config = AlarmConfig.Config(fixture.enabled, fixture.hour, fixture.minute,
            "Public clock-change fixture", fixture.days)
        val actual = AlarmConfig.nextTrigger(config, fixture.from)
        actual == fixture.expected && (actual == null || actual > before.timeInMillis) &&
            before == fixture.from
      }
      check("schedule.${fixture.name}.wake_light") {
        val before = fixture.from.clone() as Calendar
        val config = SunriseConfig.Config(fixture.enabled, fixture.hour, fixture.minute,
            20, false, fixture.days)
        val actual = SunriseConfig.nextTrigger(config, fixture.from)
        actual == fixture.expected && (actual == null || actual > before.timeInMillis) &&
            before == fixture.from
      }
    }

    for ((index, action) in actions.withIndex()) {
      check("manifest.$index.intended_receiver_and_two_actions") {
        val matches = receivers(context, action)
        matches.size == 1 && matches.single().filter?.let {
          it.countActions() == 2 && actions.all(it::hasAction)
        } == true
      }
      check("manifest.$index.enabled") {
        receivers(context, action).singleOrNull()?.activityInfo?.let {
          it.enabled && it.applicationInfo.enabled
        } == true
      }
      check("manifest.$index.nonexported") {
        receivers(context, action).singleOrNull()?.activityInfo?.exported == false
      }
    }
    for ((index, action) in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_TICK,
        Intent.ACTION_DATE_CHANGED).withIndex()) {
      check("manifest.ignored_$index") { receivers(context, action).isEmpty() }
    }

    // A logical RED result is completed=true, passed=false, unexpectedExceptionCount=0.
    val completed = checks.length() == CHECK_COUNT
    val passed = completed && failures.length() == 0 && unexpectedExceptionCount == 0
    val report = JSONObject().put("command", "clockChangeAlarms")
        .put("completed", completed).put("passed", passed)
        .put("checkCount", checks.length()).put("passedCount", checks.length() - failures.length())
        .put("failureCount", failures.length()).put("unexpectedExceptionCount", unexpectedExceptionCount)
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("checks", checks).put("failures", failures)
    return Bundle().apply {
      putBoolean("clockChangeAlarmsCompleted", completed)
      putBoolean("clockChangeAlarmsPassed", passed)
      putInt("clockChangeAlarmsCheckCount", checks.length())
      putInt("clockChangeAlarmsFailureCount", failures.length())
      putInt("clockChangeAlarmsUnexpectedExceptionCount", unexpectedExceptionCount)
      putString("clockChangeAlarmsJson", report.toString())
    }
  }

  @Suppress("DEPRECATION")
  private fun receivers(context: Context, action: String): List<ResolveInfo> =
      context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName),
          PackageManager.GET_RESOLVED_FILTER or PackageManager.MATCH_DISABLED_COMPONENTS)
          .filter { it.activityInfo?.name == RECEIVER && it.activityInfo?.packageName == context.packageName }

  private fun fixtures(): List<Fixture> {
    val fixtures = mutableListOf<Fixture>()
    for ((index, zone) in listOf("America/Los_Angeles", "America/New_York", "Europe/Berlin",
        "Australia/Lord_Howe").withIndex()) {
      fun local(month: Int, day: Int, hour: Int, minute: Int = 0) =
          date(zone, 2026, month, day, hour, minute)
      fun add(name: String, from: Calendar, hour: Int, minute: Int, expected: Calendar,
          days: Set<Int> = emptySet()) {
        fixtures += Fixture("zone_$index.$name", from, hour, minute, expected.timeInMillis, days)
      }
      add("ahead", local(1, 12, 6), 7, 0, local(1, 12, 7))
      add("exact_is_not_future", local(1, 12, 7), 7, 0, local(1, 13, 7))
      add("past_is_not_replayed", local(1, 12, 8), 7, 0, local(1, 13, 7))
      add("next_week_same_weekday", local(1, 12, 8), 7, 0, local(1, 19, 7), setOf(Calendar.MONDAY))
      when (index) {
        0, 1 -> add("after_spring_gap", local(3, 8, 4), 2, 30, local(3, 9, 2, 30))
        2 -> add("after_spring_gap", local(3, 29, 4), 2, 15, local(3, 30, 2, 15))
        3 -> add("after_half_hour_spring_gap", local(10, 4, 3), 2, 15, local(10, 5, 2, 15))
      }
      // All four zone fixtures start at exactly the same public instant. Expectations use UTC,
      // independently of the local date helper and production trigger calculation.
      val sameInstant = Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.ROOT).apply {
        timeInMillis = date("UTC", 2026, 6, 1, 12, 0).timeInMillis
      }
      val expectedUtc = when (index) {
        0 -> date("UTC", 2026, 6, 1, 14, 0)
        1 -> date("UTC", 2026, 6, 2, 11, 0)
        2 -> date("UTC", 2026, 6, 2, 5, 0)
        else -> date("UTC", 2026, 6, 1, 20, 30)
      }
      add("same_instant_new_zone", sameInstant, 7, 0, expectedUtc)
      fixtures += Fixture("zone_$index.disabled", local(1, 12, 6), 7, 0, null, enabled = false)
      when (index) {
        0, 1 -> add("after_fall_overlap", local(11, 1, 3), 1, 30, local(11, 2, 1, 30))
        2 -> add("after_fall_overlap", local(10, 25, 4), 2, 15, local(10, 26, 2, 15))
        3 -> add("after_half_hour_fall_overlap", local(4, 5, 3), 1, 45, local(4, 6, 1, 45))
      }
    }
    return fixtures
  }

  private fun date(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
      Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.ROOT).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
        timeInMillis // Resolve fields now so comparisons do not mutate the input calendar.
      }
}
