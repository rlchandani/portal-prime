/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** Public native edit fixtures: no backing device Context, services, UI or disk preferences. */
internal object ToolsAlarmEditsDeviceChecks {
  private const val EXPECTED_CHECKS = 26

  fun run(): Bundle {
    val assertions = JSONArray()
    val failureDetails = JSONArray()
    var failures = 0
    var unexpectedExceptionCount = 0
    fun expect(name: String, scenario: () -> Boolean) {
      var exceptionType: String? = null
      val passed = try { scenario() } catch (error: Throwable) {
        exceptionType = error.javaClass.simpleName
        unexpectedExceptionCount++
        false
      }
      val detail = JSONObject().put("name", name).put("passed", passed)
      if (exceptionType != null) detail.put("exceptionType", exceptionType)
      assertions.put(detail)
      if (!passed) {
        failures++
        failureDetails.put(detail)
      }
    }

    expect("time_edit_preserves_external_disable_label_and_days_at_edit_boundary") {
      val f = Fixture()
      val latest = f.initial.copy(enabled = false, label = "Public external label",
          days = setOf(Calendar.FRIDAY))
      f.prefs.beforeEdit = { f.external(latest) }
      val actual = f.time(8, 17)
      f.edited(actual, latest.copy(hour = 8, minute = 17), setOf("hour", "minute"))
    }
    expect("enable_edit_preserves_external_time_label_and_days_at_edit_boundary") {
      val f = Fixture()
      val latest = f.initial.copy(enabled = false, hour = 23, minute = 57,
          label = "Public external label", days = setOf(Calendar.SATURDAY))
      f.prefs.beforeEdit = { f.external(latest) }
      val actual = f.enable(true)
      f.edited(actual, latest.copy(enabled = true), setOf("enabled"))
    }
    expect("disable_edit_preserves_external_time_label_and_days_at_edit_boundary") {
      val f = Fixture()
      val latest = f.initial.copy(hour = 4, minute = 35,
          label = "Public external label", days = emptySet())
      f.prefs.beforeEdit = { f.external(latest) }
      val actual = f.enable(false)
      f.edited(actual, latest.copy(enabled = false), setOf("enabled"))
    }
    expect("day_edit_preserves_external_disable_time_and_label_at_edit_boundary") {
      val f = Fixture()
      val latest = f.initial.copy(enabled = false, hour = 4, minute = 35,
          label = "Public external label")
      f.prefs.beforeEdit = { f.external(latest) }
      val actual = f.day(Calendar.TUESDAY)
      f.edited(actual, latest.copy(days = latest.days + Calendar.TUESDAY), setOf("days"))
    }
    expect("day_add_uses_fresh_external_selection_between_operations") {
      val f = Fixture()
      f.time(8, 17)
      val latest = f.initial.copy(enabled = false, hour = 9, minute = 43,
          label = "Public fresh label", days = setOf(Calendar.WEDNESDAY))
      f.external(latest)
      val actual = f.day(Calendar.TUESDAY)
      f.edited(actual, latest.copy(days = latest.days + Calendar.TUESDAY), setOf("days"))
    }
    expect("day_remove_uses_fresh_external_selection_between_operations") {
      val f = Fixture()
      f.day(Calendar.TUESDAY)
      val latest = f.initial.copy(enabled = false, hour = 9, minute = 43,
          label = "Public fresh label", days = setOf(Calendar.TUESDAY, Calendar.FRIDAY))
      f.external(latest)
      val actual = f.day(Calendar.TUESDAY)
      f.edited(actual, latest.copy(days = setOf(Calendar.FRIDAY)), setOf("days"))
    }
    expect("removing_last_repeat_day_retains_disabled_one_shot_config") {
      val f = Fixture()
      val latest = f.initial.copy(enabled = false, days = setOf(Calendar.SUNDAY))
      f.external(latest)
      val actual = f.day(Calendar.SUNDAY)
      f.edited(actual, latest.copy(days = emptySet()), setOf("days"))
    }
    expect("unchanged_time_returns_current_config_without_write_or_reschedule") {
      val f = Fixture()
      val latest = f.initial.copy(enabled = false, hour = 8, minute = 17,
          label = "Public fresh label", days = emptySet())
      f.external(latest)
      val actual = f.time(8, 17)
      actual == latest && AlarmConfig.load(f.context) == latest &&
          f.prefs.editAttempts == 0 && f.prefs.transactions.isEmpty() && f.schedules == 0
    }

    for (operation in listOf("enable", "time", "day")) {
      expect("${operation}_readback_sees_external_changes_during_scheduler") {
        val f = Fixture()
        val latest = f.initial.copy(enabled = false, hour = 22, minute = 41,
            label = "Public scheduler label", days = setOf(Calendar.SATURDAY))
        f.duringSchedule = { f.external(latest, resetTrace = false) }
        val actual = when (operation) {
          "enable" -> f.enable(false)
          "time" -> f.time(8, 17)
          else -> f.day(Calendar.TUESDAY)
        }
        actual == latest && AlarmConfig.load(f.context) == latest && f.schedules == 1 &&
            f.prefs.getString("public_sentinel", null) == "retained"
      }
    }
    for (day in Calendar.SUNDAY..Calendar.SATURDAY) {
      expect("weekday_${day}_can_be_added_and_removed_without_other_field_writes") {
        val f = Fixture()
        val latest = f.initial.copy(enabled = false, days = emptySet())
        f.external(latest)
        val added = f.day(day)
        val addPassed = f.edited(added, latest.copy(days = setOf(day)), setOf("days"))
        f.resetTrace()
        val removed = f.day(day)
        addPassed && f.edited(removed, latest, setOf("days"))
      }
    }
    for ((hour, minute) in listOf(0 to 0, 23 to 59)) {
      expect("valid_time_${hour}_${minute}_writes_pair_once") {
        val f = Fixture()
        val actual = f.time(hour, minute)
        f.edited(actual, f.initial.copy(hour = hour, minute = minute), setOf("hour", "minute"))
      }
    }
    for ((hour, minute) in listOf(-1 to 17, 24 to 17, 8 to -1, 8 to 60, -1 to 60, 24 to -1)) {
      expect("invalid_time_${hour}_${minute}_rejects_before_any_write_or_schedule") {
        val f = Fixture()
        val before = f.prefs.all
        val rejected = try {
          f.time(hour, minute)
          false
        } catch (_: IllegalArgumentException) { true }
        rejected && f.prefs.all == before && f.prefs.editAttempts == 0 &&
            f.prefs.transactions.isEmpty() && f.schedules == 0
      }
    }

    val count = assertions.length()
    val completed = count == EXPECTED_CHECKS
    val passed = completed && failures == 0
    val report = JSONObject().put("completed", completed).put("passed", passed)
        .put("checkCount", count).put("passedCount", count - failures)
        .put("failureCount", failures).put("caseCount", count).put("uiCases", 0)
        .put("unexpectedExceptionCount", unexpectedExceptionCount)
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("checks", assertions).put("failures", failureDetails)
    return Bundle().apply {
      putBoolean("toolsAlarmEditsCompleted", completed)
      putBoolean("toolsAlarmEditsPassed", passed)
      putInt("toolsAlarmEditsChecks", count)
      putInt("toolsAlarmEditsCount", count)
      putInt("toolsAlarmEditsCheckCount", count)
      putInt("toolsAlarmEditsPassedCount", count - failures)
      putInt("toolsAlarmEditsFailureCount", failures)
      putInt("toolsAlarmEditsCaseCount", count)
      putInt("toolsAlarmEditsUiCases", 0)
      putString("toolsAlarmEditsAssertions", assertions.toString())
      putString("toolsAlarmEditsJson", report.toString())
    }
  }

  private class Fixture {
    val initial = AlarmConfig.Config(true, 7, 30, "Public alarm", setOf(Calendar.MONDAY))
    val prefs = MemoryPreferences()
    val context = MemoryContext(prefs)
    var schedules = 0
    var duringSchedule: (() -> Unit)? = null
    private val reschedule: () -> Unit = { schedules++; duringSchedule?.invoke() }

    init {
      prefs.edit().putString("public_sentinel", "retained").apply()
      external(initial)
    }
    fun enable(on: Boolean) = ToolsAlarmEdits.setEnabled(context, on, reschedule)
    fun time(hour: Int, minute: Int) = ToolsAlarmEdits.setTime(context, hour, minute, reschedule)
    fun day(day: Int) = ToolsAlarmEdits.toggleDay(context, day, reschedule)
    fun external(config: AlarmConfig.Config, resetTrace: Boolean = true) {
      AlarmConfig.setEnabled(context, config.enabled)
      AlarmConfig.setTime(context, config.hour, config.minute)
      AlarmConfig.setLabel(context, config.label)
      AlarmConfig.setDays(context, config.days)
      if (resetTrace) resetTrace()
    }
    fun resetTrace() {
      prefs.editAttempts = 0
      prefs.transactions.clear()
      schedules = 0
    }
    fun edited(actual: AlarmConfig.Config, expected: AlarmConfig.Config, keys: Set<String>) =
        actual == expected && AlarmConfig.load(context) == expected && schedules == 1 &&
            prefs.editAttempts == 1 && prefs.transactions == listOf(keys) &&
            prefs.getString("public_sentinel", null) == "retained"
  }

  private class MemoryContext(private val prefs: MemoryPreferences) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
      check(name == "immortal_alarm" && mode == Context.MODE_PRIVATE)
      return prefs
    }
    override fun getSystemService(name: String): Any? =
        error("Public alarm edit fixtures prohibit device services")
  }

  /** Android value types and edit transactions, stored only in this invocation's heap. */
  private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String?, Any?>()
    var beforeEdit: (() -> Unit)? = null
    var editAttempts = 0
    val transactions = mutableListOf<Set<String>>()
    private fun value(key: String?, fallback: Any?): Any? =
        if (values.containsKey(key)) values[key] else fallback
    override fun getAll(): MutableMap<String, *> = values.entries.associate {
      checkNotNull(it.key) to if (it.value is Set<*>) (it.value as Set<*>).toSet() else it.value
    }.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = value(key, defValue) as String?
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (value(key, defValues) as Set<String>?)?.toMutableSet()
    override fun getInt(key: String?, defValue: Int): Int = value(key, defValue) as Int
    override fun getLong(key: String?, defValue: Long): Long = value(key, defValue) as Long
    override fun getFloat(key: String?, defValue: Float): Float = value(key, defValue) as Float
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key, defValue) as Boolean
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor {
      val injection = beforeEdit
      beforeEdit = null
      injection?.invoke()
      editAttempts++
      return MemoryEditor()
    }

    private inner class MemoryEditor : SharedPreferences.Editor {
      private val changes = linkedMapOf<String?, Any?>()
      private var clearFirst = false
      override fun putString(key: String?, value: String?): SharedPreferences.Editor =
          apply { changes[key] = value }
      override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
          apply { changes[key] = values?.toSet() }
      override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
      override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
      override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
      override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
      override fun remove(key: String?): SharedPreferences.Editor = apply { changes[key] = null }
      override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }
      override fun commit(): Boolean {
        if (clearFirst) values.clear()
        changes.forEach { (key, value) ->
          if (value == null) values.remove(key) else values[key] = value
        }
        transactions += changes.keys.map { checkNotNull(it) }.toSet()
        return true
      }
      override fun apply() { commit() }
    }
  }
}
