/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log

/** One AlarmManager alarm for the next routine event (a start or an auto-close); no polling. */
internal object RoutineScheduler {
  const val ACTION_FIRE = "com.immortal.launcher.ROUTINE_FIRE"
  private const val TAG = "ImmortalRoutines"
  private const val RC = 0x5A2E
  private const val CHANNEL = "immortal_routines"
  const val NOTIF_ID = 42131

  private fun pi(c: Context, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(c, RC,
      Intent(c, RoutineAlarmReceiver::class.java).setAction(ACTION_FIRE),
      (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_IMMUTABLE)

  /** Re-arms the single alarm for [atUtcMs], or cancels it when null. */
  fun arm(c: Context, atUtcMs: Long?) {
    val alarms = c.getSystemService(AlarmManager::class.java) ?: return
    pi(c, create = false)?.let { alarms.cancel(it); it.cancel() }
    if (atUtcMs == null) return
    val p = pi(c, create = true) ?: return
    runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atUtcMs, p) }
        .recoverCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atUtcMs, p) }
        .onFailure { Log.w(TAG, "routine alarm not armed", it) }
  }

  /** Never interrupt a call (README): an active call or the Meta Calls handoff delays the start. */
  fun busy(c: Context): Boolean {
    val mode = runCatching { c.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
    return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || DreamPolicy.inStockHandoff
  }

  /**
   * Brings up the takeover. A receiver can't start an Activity from the background on API 29, so
   * this posts a full-screen-intent notification (the alarm-clock path [AlarmReceiver] uses) and
   * also tries a direct start, which works when Home is in front.
   */
  fun launch(c: Context) {
    ScreenControl.wake(c) // also ends a running screensaver dream
    val activity = Intent(c, RoutineTakeoverActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pi = PendingIntent.getActivity(c, 0, activity, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    runCatching {
      val nm = c.getSystemService(NotificationManager::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Routines", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Shows a picture routine at its start time" })
      }
      nm.notify(NOTIF_ID, Notification.Builder(c, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle("Routine").setContentText("Time for the routine").setCategory(Notification.CATEGORY_ALARM)
          .setAutoCancel(true).setContentIntent(pi).setFullScreenIntent(pi, true).build())
    }.onFailure { Log.w(TAG, "routine notification failed", it) }
    runCatching { c.startActivity(activity) }.onFailure { Log.i(TAG, "direct takeover start blocked; notification will open it") }
  }

  fun clearNotification(c: Context) {
    runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
  }
}

/** The routine alarm: starts/closes due routines and arms the next event. Not exported. */
class RoutineAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != RoutineScheduler.ACTION_FIRE) return
    RoutineEngine.init(context)
    RoutineEngine.step()
  }
}
