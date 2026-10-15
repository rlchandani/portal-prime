/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Several named kitchen timers running at once (pasta, oven, tea). Each is a chip
 * on the home screen; when one elapses it rings through the existing chime system
 * ([ChimePlayer]). Persisted as JSON in SharedPrefs and re-armed via [TimerScheduler]
 * so a running timer survives the launcher being killed or the device rebooting.
 *
 * The object monitor serializes every read/modify/write and its scheduling side effect
 * across Tools, legacy callers, receivers and startup. A caller's own UI lock is insufficient.
 */
object TimerConfig {

  private const val PREFS = "immortal_timers"
  private const val KEY = "timers"

  /** One running (or just-elapsed) timer. [endAtMillis] is wall-clock RTC. */
  data class Timer(
      val id: String,
      val label: String,
      val durationMs: Long,
      val endAtMillis: Long,
  ) {
    val remainingMs: Long get() = endAtMillis - System.currentTimeMillis()
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized fun load(context: Context): List<Timer> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Timer(
            id = o.getString("id"),
            label = o.optString("label", "Timer"),
            durationMs = o.optLong("dur", 0L),
            endAtMillis = o.getLong("end"),
        )
      }
    }.getOrDefault(emptyList())
  }

  private fun save(context: Context, timers: List<Timer>) {
    val arr = JSONArray()
    timers.forEach { t ->
      arr.put(JSONObject().apply {
        put("id", t.id); put("label", t.label); put("dur", t.durationMs); put("end", t.endAtMillis)
      })
    }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  /** Create + persist + schedule a new timer running [durationMs] from now. */
  @Synchronized fun add(context: Context, label: String, durationMs: Long): Timer {
    val t = Timer(
        id = System.currentTimeMillis().toString(36) + "-" + (0..9999).random(),
        label = label.ifBlank { "Timer" },
        durationMs = durationMs,
        endAtMillis = System.currentTimeMillis() + durationMs,
    )
    save(context, load(context) + t)
    TimerScheduler.schedule(context, t)
    return t
  }

  @Synchronized fun remove(context: Context, id: String) {
    val existing = load(context)
    existing.firstOrNull { it.id == id }?.let { TimerScheduler.cancel(context, it) }
    save(context, existing.filterNot { it.id == id })
  }

  /** Claim an existing due timer once, before scheduling audio. A completed cancellation wins. */
  @Synchronized internal fun consumeDue(
      context: Context, id: String?, nowMillis: Long = System.currentTimeMillis(),
  ): Timer? {
    if (id.isNullOrBlank()) return null
    val timers = load(context)
    val due = timers.firstOrNull { it.id == id && it.endAtMillis <= nowMillis } ?: return null
    save(context, timers.filterNot { it.id == id })
    return due
  }

  /** Drop a fired/expired timer without touching its (already-spent) alarm. */
  @Synchronized fun forget(context: Context, id: String) {
    save(context, load(context).filterNot { it.id == id })
  }

  /** Re-arm alarms for every still-pending timer (boot / app start). Expired ones
   *  are dropped. */
  @Synchronized fun rearmAll(context: Context) {
    val now = System.currentTimeMillis()
    val (live, dead) = load(context).partition { it.endAtMillis > now }
    if (dead.isNotEmpty()) save(context, live)
    live.forEach { TimerScheduler.schedule(context, it) }
  }
}
