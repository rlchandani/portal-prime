/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.Calendar

/**
 * A single wake-up alarm. At the set time on the chosen days a full-screen ringing screen
 * appears with a chime, over the lock screen, until dismissed or snoozed. Persisted in
 * SharedPrefs and re-armed by [ImmortalApp] / [BootReceiver].
 */
object AlarmConfig {

  private const val PREFS = "immortal_alarm"

  data class Config(
      val enabled: Boolean,
      val hour: Int,
      val minute: Int,
      val label: String,
      /** Sun-Sat; if empty the alarm is treated as one-shot (next occurrence). */
      val days: Set<Int>, // Calendar.SUNDAY..Calendar.SATURDAY
  )

  fun load(context: Context): Config {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val days = p.getStringSet("days", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    return Config(
        enabled = p.getBoolean("enabled", false),
        hour = p.getInt("hour", 7),
        minute = p.getInt("minute", 0),
        label = p.getString("label", "Alarm") ?: "Alarm",
        days = days,
    )
  }

  fun save(context: Context, c: Config) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean("enabled", c.enabled)
        .putInt("hour", c.hour)
        .putInt("minute", c.minute)
        .putString("label", c.label)
        .putStringSet("days", c.days.map { it.toString() }.toSet())
        .apply()
  }

  // Per-field setters for the registry: clamping, side-effect-free, (Context, value) signature.
  // The `days` Set<Int> has no scalar setter — it's managed by a bespoke day-picker
  // (the registry models scalars, not sets).

  fun setEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("enabled", on).apply()

  fun setHour(c: Context, h: Int) =
      prefs(c).edit().putInt("hour", h.coerceIn(0, 23)).apply()

  fun setMinute(c: Context, m: Int) =
      prefs(c).edit().putInt("minute", m.coerceIn(0, 59)).apply()

  /** Save the validated time pair together without replaying other alarm fields. */
  fun setTime(c: Context, hour: Int, minute: Int) {
    require(hour in 0..23) { "Hour must be between 0 and 23" }
    require(minute in 0..59) { "Minute must be between 0 and 59" }
    prefs(c).edit().putInt("hour", hour).putInt("minute", minute).apply()
  }

  fun setLabel(c: Context, label: String) =
      prefs(c).edit().putString("label", label).apply()

  fun setDays(c: Context, days: Set<Int>) =
      prefs(c).edit().putStringSet("days", days.map { it.toString() }.toSet()).apply()

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Observe only this editor's saved fields; disposal retains and unregisters its listener. */
  internal fun observeSnapshot(context: Context, changed: () -> Unit): () -> Unit =
      observeSettingsPreferenceChanges(prefs(context),
          setOf("enabled", "hour", "minute", "label", "days"), changed)

  /** Next fire time (epoch millis) at/after now for [c], or null if disabled. Pure +
   * testable: pass [from] to compute deterministically. */
  fun nextTrigger(c: Config, from: Calendar = Calendar.getInstance()): Long? {
    if (!c.enabled) return null
    val cal = from.clone() as Calendar
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.set(Calendar.HOUR_OF_DAY, c.hour)
    cal.set(Calendar.MINUTE, c.minute)
    // Search up to 8 days out for the next matching day-of-week (or one-shot).
    for (i in 0..7) {
      val candidate = cal.clone() as Calendar
      candidate.add(Calendar.DAY_OF_YEAR, i)
      if (candidate.timeInMillis <= from.timeInMillis) continue
      val dow = candidate.get(Calendar.DAY_OF_WEEK)
      if (c.days.isEmpty() || dow in c.days) return candidate.timeInMillis
    }
    return null
  }
}
