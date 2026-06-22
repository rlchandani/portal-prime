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
import android.os.Build
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A single, shared countdown timer persisted across the app's surfaces. The home-screen Timers
 * widget starts/pauses it; both that widget and the screensaver ([FaceRenderer]) read it back so a
 * timer started on the home screen keeps counting down — and stays visible — once the photo frame
 * takes over. Persistence (SharedPreferences) is the cross-component channel (home and screensaver
 * are different components and rarely co-resident); [addListener] gives same-process surfaces a live
 * nudge so a running timer's controls update immediately.
 *
 * Expiry is driven by an exact [AlarmManager] alarm (not just a UI loop), so the timer rings even if
 * neither the home widget nor the screensaver is on screen. The alarm broadcasts to
 * [TimerAlarmReceiver], which flips [State.ringing] on and starts [TimerAlarm].
 */
object TimerStore {

  private const val PREFS = "immortal_timer"
  private const val KEY_RUNNING = "running"
  private const val KEY_ENDS_AT = "ends_at"
  private const val KEY_REMAINING = "remaining_ms"
  private const val KEY_RINGING = "ringing"
  private const val ALARM_REQUEST = 0x71_4E_72 // "tNr"

  /**
   * The timer's persisted state. When [running], the countdown is anchored to [endsAt] (a wall
   * clock epoch-ms) so it advances even while nothing is observing it; when paused, [remainingMs]
   * holds what was left so it can be resumed exactly. [ringing] is set once it fires and stays on
   * until silenced (by swipe, the widget Stop, or the 60s auto-silence in [TimerAlarm]).
   */
  data class State(
      val running: Boolean = false,
      val endsAt: Long = 0L,
      val remainingMs: Long = 0L,
      val ringing: Boolean = false,
  ) {
    /** Milliseconds left at [now] — derived from [endsAt] while running, else the frozen value. */
    fun remaining(now: Long): Long =
        if (running) (endsAt - now).coerceAtLeast(0L) else remainingMs.coerceAtLeast(0L)

    /** Whether there's still a timer worth showing (running or paused with time left). */
    fun active(now: Long): Boolean = remaining(now) > 0L
  }

  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  private fun notifyListeners() {
    listeners.forEach { runCatching { it() } }
  }

  fun load(context: Context): State {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return State(
        running = p.getBoolean(KEY_RUNNING, false),
        endsAt = p.getLong(KEY_ENDS_AT, 0L),
        remainingMs = p.getLong(KEY_REMAINING, 0L),
        ringing = p.getBoolean(KEY_RINGING, false),
    )
  }

  private fun save(context: Context, state: State) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_RUNNING, state.running)
        .putLong(KEY_ENDS_AT, state.endsAt)
        .putLong(KEY_REMAINING, state.remainingMs)
        .putBoolean(KEY_RINGING, state.ringing)
        .apply()
    notifyListeners()
  }

  /** Start (or restart) the timer for [durationMs] from [now]. */
  fun start(context: Context, durationMs: Long, now: Long = System.currentTimeMillis()) {
    val endsAt = now + durationMs
    save(context, State(running = true, endsAt = endsAt, remainingMs = durationMs, ringing = false))
    scheduleAlarm(context, endsAt)
  }

  /** Freeze a running timer at its current remaining time. */
  fun pause(context: Context, now: Long = System.currentTimeMillis()) {
    val s = load(context)
    if (!s.running) return
    cancelAlarm(context)
    save(context, State(running = false, endsAt = 0L, remainingMs = s.remaining(now)))
  }

  /** Re-anchor a paused timer to keep counting from where it stopped. */
  fun resume(context: Context, now: Long = System.currentTimeMillis()) {
    val s = load(context)
    if (s.running || s.remainingMs <= 0L) return
    val endsAt = now + s.remainingMs
    save(context, State(running = true, endsAt = endsAt, remainingMs = s.remainingMs))
    scheduleAlarm(context, endsAt)
  }

  /** Stop and forget the timer (also cancels a pending alarm). */
  fun clear(context: Context) {
    cancelAlarm(context)
    save(context, State())
  }

  /** Called by [TimerAlarmReceiver] when the alarm fires: the timer is now ringing. */
  fun markRinging(context: Context) {
    save(context, State(running = false, endsAt = 0L, remainingMs = 0L, ringing = true))
  }

  /** Silence the ringing timer and reset to idle. */
  fun stopRinging(context: Context) {
    cancelAlarm(context)
    save(context, State())
  }

  // --- exact-alarm scheduling -------------------------------------------------
  private fun alarmPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context.applicationContext, TimerAlarmReceiver::class.java)
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(context.applicationContext, ALARM_REQUEST, intent, flags)
  }

  private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val pi = alarmPendingIntent(context)
    am.cancel(pi)
    runCatching {
      // Portals are Android 9/10 (< S), so exact alarms need no special permission.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
          am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
      else am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }
  }

  private fun cancelAlarm(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    am.cancel(alarmPendingIntent(context))
  }
}
