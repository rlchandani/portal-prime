/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Fires at the alarm time: starts the chime, launches the full-screen ringing screen, then
 *  arms the next occurrence. */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val snooze = intent?.action == AlarmScheduler.ACTION_SNOOZE
    if (!snooze && intent?.action != AlarmScheduler.ACTION_FIRE) return
    val cfg = AlarmConfig.load(context)
    if (!AlarmDeliveryPolicy.shouldRing(cfg.enabled, snooze)) return
    AlarmSound.start(context)
    showAlert(context, cfg)
    if (!AlarmDeliveryPolicy.shouldAdvanceSchedule(snooze)) return
    if (cfg.days.isEmpty()) {
      // One-shot alarm (no repeat days): turn it off after firing so it doesn't re-arm for the
      // same time tomorrow. Disabling makes nextTrigger return null, so no new alarm is scheduled.
      AlarmConfig.setEnabled(context, false)
    }
    // Re-arm the next matching weekday (a no-op for a now-disabled one-shot).
    AlarmScheduler.reschedule(context, cancelSnooze = false)
  }

  /**
   * Bring up the ringing screen. A direct `startActivity` from a receiver is blocked by the
   * background-activity-launch rules on API 29+, so we use a **full-screen-intent
   * notification** — the sanctioned alarm-clock path — which the system promotes to a
   * full-screen activity even from the background / lock screen. We also try a direct
   * start (harmless on API 28 where it's allowed and is a no-op duplicate otherwise).
   */
  private fun showAlert(context: Context, cfg: AlarmConfig.Config) {
    val activity =
        Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmRingActivity.EXTRA_LABEL, cfg.label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val pi =
        PendingIntent.getActivity(
            context, 0, activity,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Wakes the screen for the alarm"
          }
      nm.createNotificationChannel(channel)
    }
    val notif =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(cfg.label)
            .setContentText("Alarm")
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .build()
    runCatching { nm.notify(NOTIF_ID, notif) }
    // Best-effort direct launch too (allowed on API 28 / when the launcher is foreground).
    runCatching { context.startActivity(activity) }
  }

  companion object {
    private const val CHANNEL = "immortal_alarm"
    private const val NOTIF_ID = 42102

    internal fun clearNotification(context: Context) {
      context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
  }
}
