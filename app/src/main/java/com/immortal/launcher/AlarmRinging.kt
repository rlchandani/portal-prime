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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The alarm ringing right now, if any, and the two ways to end it: [snooze] and [dismiss]. The ringing screen's
 * buttons and Jarvis ("snooze", "stop the alarm") both go through here, so a spoken snooze also closes the screen
 * (it watches [ringing]). A ring counts as over once [AlarmSound]'s two-minute auto-silence has passed.
 */
internal object AlarmRinging {
  /** [id] is the alarm's slot ([MoreAlarms.MAIN_ID] for the main alarm). */
  data class Ring(val id: Int, val label: String, val sinceMs: Long)

  /** AlarmSound's auto-silence plus a little slack. */
  const val RING_MS = 125_000L
  const val EXTRA_ID = "alarm_id"
  private val state = MutableStateFlow<Ring?>(null)
  val ringing: StateFlow<Ring?> = state.asStateFlow()

  /** The ring still under way at [now]; null once it auto-silenced. Pure. */
  fun active(ring: Ring?, now: Long): Ring? = ring?.takeIf { now - it.sinceMs in 0..RING_MS }

  fun current(now: Long = System.currentTimeMillis()): Ring? = active(state.value, now)

  fun started(id: Int, label: String, now: Long = System.currentTimeMillis()) { state.value = Ring(id, label, now) }

  /**
   * Stops the ring and rings again in [minutes]; null when nothing was ringing. The ringing screen passes its own
   * alarm as [fallback] so its button always works, even for a ring this process no longer remembers.
   */
  fun snooze(context: Context, minutes: Int = AlarmSnooze.MINUTES, fallback: Ring? = null,
      now: Long = System.currentTimeMillis()): Ring? {
    val ring = take(now) ?: fallback ?: return null
    silence(context)
    val app = context.applicationContext
    val intent = Intent(app, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_SNOOZE)
        .putExtra(EXTRA_ID, ring.id).putExtra(AlarmRingActivity.EXTRA_LABEL, ring.label)
    val pi = PendingIntent.getBroadcast(app, AlarmScheduler.snoozeRequestCode(ring.id), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val am = app.getSystemService(AlarmManager::class.java)
    AlarmSnooze.schedule(
        exact = { type, at -> am.setExactAndAllowWhileIdle(type, at, pi) },
        fallback = { type, at -> am.setAndAllowWhileIdle(type, at, pi) },
        minutes = minutes,
    )
    return ring
  }

  /**
   * Stops the ring for good (the next occurrence stays armed); null when nothing was ringing. The ringing screen
   * passes [force] so Dismiss always silences it.
   */
  fun dismiss(context: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Ring? {
    val ring = take(now)
    if (ring == null && !force) return null
    silence(context)
    BriefingLauncher.afterWake(context)
    return ring
  }

  private fun silence(context: Context) {
    AlarmSound.stop(context)
    AlarmReceiver.clearNotification(context)
  }

  /** Clears the ring, returning it only if it was still under way (a stale one is just forgotten). */
  private fun take(now: Long): Ring? {
    while (true) {
      val ring = state.value
      if (state.compareAndSet(ring, null)) return active(ring, now)
    }
  }
}
