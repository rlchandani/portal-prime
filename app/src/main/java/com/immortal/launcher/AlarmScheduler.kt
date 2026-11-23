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
  /** The other alarms ([MoreAlarms]) use `MORE_RC + id`, clear of [RC] and [SNOOZE_RC]. */
  private const val MORE_RC = 0x5A30

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  private fun pi(c: Context, create: Boolean, id: Int = MoreAlarms.MAIN_ID): PendingIntent? {
    val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
        PendingIntent.FLAG_IMMUTABLE
    val intent = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE)
    if (id != MoreAlarms.MAIN_ID) intent.putExtra(AlarmRinging.EXTRA_ID, id)
    return PendingIntent.getBroadcast(c, requestCode(id), intent, flags)
  }

  internal fun requestCode(id: Int): Int = if (id == MoreAlarms.MAIN_ID) RC else MORE_RC + id

  /** Each alarm's snooze has its own slot, so snoozing one never drops another's. Main = [SNOOZE_RC]. */
  internal fun snoozeRequestCode(id: Int): Int = SNOOZE_RC + id.coerceIn(0, MoreAlarms.MAX)

  /** Re-arm regular occurrences. Explicit edits cancel the main alarm's snooze; startup preserves it. */
  fun reschedule(context: Context, cancelSnooze: Boolean = true) {
    if (cancelSnooze) PendingIntent.getBroadcast(context, SNOOZE_RC,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_SNOOZE),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
          alarms(context).cancel(it); it.cancel()
        }
    arm(context, MoreAlarms.MAIN_ID, AlarmConfig.load(context))
    rescheduleMore(context)
  }

  /** Re-arms only the other alarms (each in its own slot); the main alarm and any snooze are left alone. */
  fun rescheduleMore(context: Context) {
    val more = runCatching { MoreAlarms.load(context) }.getOrDefault(emptyList())
    for (id in 1..MoreAlarms.MAX) {
      pi(context, create = false, id = id)?.let { alarms(context).cancel(it); it.cancel() }
      more.firstOrNull { it.id == id }?.let { arm(context, id, it.config) }
    }
  }

  private fun arm(context: Context, id: Int, cfg: AlarmConfig.Config) {
    if (id == MoreAlarms.MAIN_ID) pi(context, create = false)?.let { alarms(context).cancel(it); it.cancel() }
    val at = AlarmConfig.nextTrigger(cfg) ?: return
    val p = pi(context, create = true, id = id) ?: return
    runCatching { alarms(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        .onFailure { runCatching { alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) } }
  }
}
