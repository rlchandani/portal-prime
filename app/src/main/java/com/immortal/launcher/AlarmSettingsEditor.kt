/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

internal interface AlarmSettingsEditorBackend {
  fun read(): AlarmConfig.Config
  fun setEnabled(value: Boolean)
  fun setHour(value: Int)
  fun setMinute(value: Int)
  fun setTime(hour: Int, minute: Int)
  fun setDays(value: Set<Int>)
  fun reschedule()
}

/** Immediate field edits never replay a stale enabled flag, label or repeat selection. */
internal class AlarmSettingsEditor(private val backend: AlarmSettingsEditorBackend) {
  var snapshot: AlarmConfig.Config = backend.read()
    private set

  fun refresh(): AlarmConfig.Config = backend.read().also { snapshot = it }
  fun setEnabled(value: Boolean) = edit { backend.setEnabled(value) }
  fun stepHour(delta: Int) = edit {
    backend.setHour((backend.read().hour + delta + 24) % 24)
  }
  fun stepMinute(delta: Int) = edit {
    backend.setMinute((backend.read().minute + delta + 60) % 60)
  }
  fun setTime(hour: Int, minute: Int): AlarmConfig.Config {
    require(hour in 0..23) { "Hour must be between 0 and 23" }
    require(minute in 0..59) { "Minute must be between 0 and 59" }
    val latest = backend.read()
    if (latest.hour == hour && latest.minute == minute) {
      snapshot = latest
      return latest
    }
    return edit { backend.setTime(hour, minute) }
  }
  fun toggleDay(day: Int) = edit {
    val days = backend.read().days
    backend.setDays(if (day in days) days - day else days + day)
  }

  private fun edit(change: () -> Unit): AlarmConfig.Config {
    change()
    backend.reschedule()
    return refresh()
  }
}

internal fun alarmSettingsEditor(
    context: Context, onReschedule: () -> Unit = { AlarmScheduler.reschedule(context) },
) =
    AlarmSettingsEditor(object : AlarmSettingsEditorBackend {
      override fun read() = AlarmConfig.load(context)
      override fun setEnabled(value: Boolean) = AlarmConfig.setEnabled(context, value)
      override fun setHour(value: Int) = AlarmConfig.setHour(context, value)
      override fun setMinute(value: Int) = AlarmConfig.setMinute(context, value)
      override fun setTime(hour: Int, minute: Int) = AlarmConfig.setTime(context, hour, minute)
      override fun setDays(value: Set<Int>) = AlarmConfig.setDays(context, value)
      override fun reschedule() = onReschedule()
    })
