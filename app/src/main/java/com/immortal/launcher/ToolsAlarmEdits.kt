/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/** Tools and Settings share field-scoped writes, scheduling and current-value readback. */
internal object ToolsAlarmEdits {
  fun setEnabled(context: Context, on: Boolean,
      reschedule: () -> Unit = { AlarmScheduler.reschedule(context) }): AlarmConfig.Config =
      alarmSettingsEditor(context, reschedule).setEnabled(on)

  fun setTime(context: Context, hour: Int, minute: Int,
      reschedule: () -> Unit = { AlarmScheduler.reschedule(context) }): AlarmConfig.Config =
      alarmSettingsEditor(context, reschedule).setTime(hour, minute)

  fun toggleDay(context: Context, day: Int,
      reschedule: () -> Unit = { AlarmScheduler.reschedule(context) }): AlarmConfig.Config =
      alarmSettingsEditor(context, reschedule).toggleDay(day)
}
