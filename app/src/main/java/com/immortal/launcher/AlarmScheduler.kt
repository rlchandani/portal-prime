/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Arms the next alarm fire via AlarmManager; [AlarmReceiver] launches the ringing screen.
 *  Re-armed on boot and app start, and rescheduled after each fire. */
object AlarmScheduler {
  const val ACTION_FIRE = "com.immortal.launcher.ALARM_FIRE"
  internal const val ACTION_SNOOZE = "com.immortal.launcher.ALARM_SNOOZE"
  internal const val SNOOZE_RC = 0x5A1D
  private const val RC = 0x5A1C

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  private fun pi(c: Context, create: Boolean): PendingIntent? {
    val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
        PendingIntent.FLAG_IMMUTABLE
    val intent = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE)
    return PendingIntent.getBroadcast(c, RC, intent, flags)
  }

  /** Re-arm regular occurrences. Explicit edits cancel snooze; startup preserves it. */
  fun reschedule(context: Context, cancelSnooze: Boolean = true) {
    pi(context, create = false)?.let { alarms(context).cancel(it); it.cancel() }
    if (cancelSnooze) PendingIntent.getBroadcast(context, SNOOZE_RC,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_SNOOZE),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
          alarms(context).cancel(it); it.cancel()
        }
    val cfg = AlarmConfig.load(context)
    val at = AlarmConfig.nextTrigger(cfg) ?: return
    val p = pi(context, create = true) ?: return
    runCatching { alarms(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        .onFailure { runCatching { alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) } }
  }
}
