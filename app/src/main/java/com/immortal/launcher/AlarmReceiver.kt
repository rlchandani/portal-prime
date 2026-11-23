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
    if (intent == null) return
    val snooze = intent.action == AlarmScheduler.ACTION_SNOOZE
    if (!snooze && intent.action != AlarmScheduler.ACTION_FIRE) return
    val id = intent.getIntExtra(AlarmRinging.EXTRA_ID, MoreAlarms.MAIN_ID)
    val main = id == MoreAlarms.MAIN_ID
    val stored = if (main) AlarmConfig.load(context) else MoreAlarms.find(context, id)?.config
    // A snoozed ring of an alarm deleted meanwhile still rings once, under its own label.
    val cfg = stored ?: if (snooze) AlarmConfig.Config(false, 0, 0, "Alarm", emptySet()) else return
    if (!AlarmDeliveryPolicy.shouldRing(cfg.enabled, snooze)) return
    val label = intent.getStringExtra(AlarmRingActivity.EXTRA_LABEL)?.takeIf { snooze } ?: cfg.label
    AlarmSound.start(context)
    AlarmRinging.started(id, label)
    showAlert(context, id, label)
    if (!AlarmDeliveryPolicy.shouldAdvanceSchedule(snooze)) return
    if (cfg.days.isEmpty()) {
      // One-shot alarm (no repeat days): turn it off after firing so it doesn't re-arm for the
      // same time tomorrow. Disabling makes nextTrigger return null, so no new alarm is scheduled.
      if (main) AlarmConfig.setEnabled(context, false)
      else MoreAlarms.edit(context) { all -> all.map { if (it.id == id) it.copy(config = it.config.copy(enabled = false)) else it } }
    }
    // Re-arm the next matching weekday (a no-op for a now-disabled one-shot).
    if (main) AlarmScheduler.reschedule(context, cancelSnooze = false) else AlarmScheduler.rescheduleMore(context)
  }

  /**
   * Bring up the ringing screen. A direct `startActivity` from a receiver is blocked by the
   * background-activity-launch rules on API 29+, so we use a **full-screen-intent
   * notification** — the sanctioned alarm-clock path — which the system promotes to a
   * full-screen activity even from the background / lock screen. We also try a direct
   * start (harmless on API 28 where it's allowed and is a no-op duplicate otherwise).
   */
  private fun showAlert(context: Context, id: Int, label: String) {
    val activity =
        Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmRingActivity.EXTRA_LABEL, label)
            .putExtra(AlarmRinging.EXTRA_ID, id)
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
            .setContentTitle(label)
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
