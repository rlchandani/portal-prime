/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * When reminders are due and how a due one announces itself in THIS room. Pure.
 * - Quiet hours (the chime's window): never a sound, the banner only.
 * - A household reminder chimes in every room.
 * - A person's reminder chimes only where that person is the one recognised on Home; elsewhere
 *   it is a quiet banner. Its title shows only if it isn't private, or the owner is here alone.
 */
internal object ReminderDue {
  /** A reminder missed by more than this (Portal off, asleep) is not announced late. */
  const val LATE_LIMIT_MS = 6L * 60 * 60_000

  data class Decision(val sound: Boolean, val showTitle: Boolean)

  /** [ownerHere]: the owner's personal Home is up on this Portal; [hidePrivate]: [WhosThereUi.hidePrivate]. */
  fun decide(r: Reminder, quietHours: Boolean, ownerHere: Boolean, hidePrivate: Boolean): Decision {
    if (r.household) return Decision(sound = !quietHours, showTitle = true)
    return Decision(sound = !quietHours && ownerHere, showTitle = !r.private || (ownerHere && !hidePrivate))
  }

  /** Identifies one due moment, so an edited due time rings again but a restart doesn't. */
  fun key(r: Reminder): String = "${r.id}@${r.dueMs ?: 0L}"

  /** Open reminders due now (not long overdue) that haven't been announced here yet, earliest first. */
  fun dueNow(s: ReminderState, nowMs: Long, announced: Set<String>): List<Reminder> =
      s.live.filter { r ->
        val due = r.dueMs ?: return@filter false
        r.open && due <= nowMs && nowMs - due <= LATE_LIMIT_MS && key(r) !in announced
      }.sortedBy { it.dueMs }

  /** The next future due time, for the alarm, or null. */
  fun nextDue(s: ReminderState, nowMs: Long): Long? = s.live.mapNotNull { r -> r.dueMs?.takeIf { r.open && it > nowMs } }.minOrNull()

  /**
   * The mock's small tag: "6 pm" / "18:00" today, "Tomorrow", "3 days", or a date; "Overdue" for
   * an earlier day; null without a due time.
   */
  fun tag(dueMs: Long?, nowMs: Long, zone: TimeZone, use24: Boolean, locale: Locale = Locale.getDefault()): String? {
    val due = dueMs ?: return null
    val days = dayOf(due, zone) - dayOf(nowMs, zone)
    return when {
      days < 0 -> "Overdue"
      days == 0L -> clock(due, zone, use24, locale)
      days == 1L -> "Tomorrow"
      days < 7 -> "$days days"
      else -> java.text.SimpleDateFormat("d MMM", locale).apply { timeZone = zone }.format(java.util.Date(due))
    }
  }

  /** "6 pm", "6:30 pm" or "18:30". */
  fun clock(ms: Long, zone: TimeZone, use24: Boolean, locale: Locale = Locale.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
    val h = c.get(Calendar.HOUR_OF_DAY)
    val m = c.get(Calendar.MINUTE)
    if (use24) return String.format(Locale.ROOT, "%d:%02d", h, m)
    val h12 = if (h % 12 == 0) 12 else h % 12
    val half = if (h < 12) "am" else "pm"
    return if (m == 0) "$h12 $half" else String.format(locale, "%d:%02d %s", h12, m, half)
  }

  /** The next time [hour]:[minute] happens at or after [nowMs] (today, else tomorrow). */
  fun nextAt(hour: Int, minute: Int, nowMs: Long, zone: TimeZone): Long {
    val c = Calendar.getInstance(zone).apply {
      timeInMillis = nowMs
      set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
  }

  /** [hour]:[minute] on the local calendar day [yyyy]-[mm]-[dd], or null for an impossible date. */
  fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long? {
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
    val c = Calendar.getInstance(zone).apply { isLenient = false; clear(); set(year, month - 1, day, hour, minute, 0) }
    return runCatching { c.timeInMillis }.getOrNull()
  }

  /** The sheet's day choices: "No time", "Today", "Tomorrow", then the next five weekday names. */
  fun dayOptions(nowMs: Long, zone: TimeZone, locale: Locale = Locale.getDefault()): List<Pair<String, Int?>> {
    val fmt = java.text.SimpleDateFormat("EEEE", locale).apply { timeZone = zone }
    return listOf<Pair<String, Int?>>("No time" to null, "Today" to 0, "Tomorrow" to 1) +
        (2..6).map { n -> fmt.format(java.util.Date(plusDays(nowMs, n, zone))) to n }
  }

  /**
   * The due time for [dayOffset] days from today at [clock] (9:00 when only a day is picked),
   * or null for "No time". Null too when it would already be in the past ([past] tells which).
   */
  fun dueFrom(dayOffset: Int?, clock: ReminderTimeText.Clock?, nowMs: Long, zone: TimeZone): Long? {
    if (dayOffset == null) return null
    val c = Calendar.getInstance(zone).apply {
      timeInMillis = plusDays(nowMs, dayOffset, zone)
      set(Calendar.HOUR_OF_DAY, clock?.hour ?: DEFAULT_HOUR); set(Calendar.MINUTE, clock?.minute ?: 0)
      set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis.takeIf { it > nowMs }
  }

  /** Which day option a saved due time falls on (for editing), or null when none fits. */
  fun dayOffsetOf(dueMs: Long?, nowMs: Long, zone: TimeZone): Int? =
      dueMs?.let { (dayOf(it, zone) - dayOf(nowMs, zone)).toInt() }?.takeIf { it in 0..6 }

  private fun plusDays(nowMs: Long, n: Int, zone: TimeZone): Long =
      Calendar.getInstance(zone).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis

  private const val DEFAULT_HOUR = 9

  /** Local epoch day. */
  private fun dayOf(ms: Long, zone: TimeZone): Long = Math.floorDiv(ms + zone.getOffset(ms), DAY_MS)

  private const val DAY_MS = 86_400_000L
}
