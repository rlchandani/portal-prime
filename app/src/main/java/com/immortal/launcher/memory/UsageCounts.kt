/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Silent usage recording for slice 2's "For you right now" chips (plan §3.6, shadow mode): how often each surface
 * (an app, Music, Calls, the Control Center, Settings, a Jarvis conversation) is opened in each local hour, per day,
 * on this Portal, per memory scope. Counts only; no per-event log, no text. Kept [RETENTION_DAYS] days, then gone.
 * Never leaves the Portal (it is sealed with the profiles and not synced). Immutable; pure.
 */
internal data class UsageCounts(val buckets: List<Bucket> = emptyList()) {
  /** Opens of [surface] by [scope] in local [hour] (0–23) of local epoch [day]. */
  data class Bucket(val scope: String, val surface: String, val day: Long, val hour: Int, val count: Int)

  /** One more open of [surface] at [nowMs], with anything older than the retention window dropped. */
  fun record(scope: MemoryScope, surface: String, nowMs: Long, zone: ZoneId): UsageCounts {
    if (!SURFACE.matches(surface)) return pruned(nowMs, zone)
    val at = Instant.ofEpochMilli(nowMs).atZone(zone)
    val day = at.toLocalDate().toEpochDay()
    val key = Bucket(scope.token, surface, day, at.hour, 0)
    val existing = buckets.firstOrNull { it.copy(count = 0) == key }
    val next = if (existing == null) buckets + key.copy(count = 1)
        else buckets.map { if (it === existing) it.copy(count = it.count + 1) else it }
    return UsageCounts(next).pruned(nowMs, zone)
  }

  /** Only the last [RETENTION_DAYS] local days, at most [MAX_BUCKETS] buckets (newest kept). */
  fun pruned(nowMs: Long, zone: ZoneId): UsageCounts {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
    val kept = buckets.filter { it.day > today - RETENTION_DAYS && it.day <= today && it.count > 0 }
        .sortedWith(compareByDescending<Bucket> { it.day }.thenByDescending { it.hour })
        .take(MAX_BUCKETS)
    return UsageCounts(kept)
  }

  /** Opens of [surface] by [scope] per hour of the week (0 = Monday 00:00 … 167 = Sunday 23:00). */
  fun hourOfWeek(scope: MemoryScope, surface: String): IntArray {
    val out = IntArray(HOURS_PER_WEEK)
    buckets.filter { it.scope == scope.token && it.surface == surface }.forEach { b ->
      val weekday = java.time.LocalDate.ofEpochDay(b.day).dayOfWeek.value - 1
      out[weekday * HOURS_PER_DAY + b.hour] += b.count
    }
    return out
  }

  /** Forget [scope]'s counts (Forget all for a person, or the household). */
  fun without(scope: MemoryScope): UsageCounts = UsageCounts(buckets.filter { it.scope != scope.token })

  fun encode(): String = JSONObject().put("v", VERSION).put("b", JSONArray().apply {
    buckets.forEach { put(JSONArray().put(it.scope).put(it.surface).put(it.day).put(it.hour).put(it.count)) }
  }).toString()

  companion object {
    const val RETENTION_DAYS = 14
    const val MAX_BUCKETS = 5_000
    const val VERSION = 1
    private const val HOURS_PER_DAY = 24
    private const val HOURS_PER_WEEK = 7 * HOURS_PER_DAY
    private val SURFACE = Regex("[a-z0-9_.:-]{1,80}")

    /** The counts in [text]; empty for anything unreadable (they are rebuildable). Bad buckets are dropped. */
    fun decode(text: String?): UsageCounts {
      val root = try {
        JSONObject(text ?: return UsageCounts())
      } catch (_: JSONException) {
        return UsageCounts()
      }
      if (root.optInt("v") != VERSION) return UsageCounts()
      val a = root.optJSONArray("b") ?: return UsageCounts()
      return UsageCounts((0 until a.length()).mapNotNull { i ->
        runCatching {
          val b = a.getJSONArray(i)
          Bucket(b.getString(0), b.getString(1), b.getLong(2), b.getInt(3), b.getInt(4))
              .takeIf { MemoryScope.fromToken(it.scope) != null && SURFACE.matches(it.surface) && it.hour in 0..23 }
        }.getOrNull()
      })
    }
  }
}
