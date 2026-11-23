/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What people did with the "For you right now" chips (plan §3.6, slice 2): a chip shown, opened, put off with ✕,
 * "Show less often" or "Don't suggest this". Surfaces and times only, no text; sealed with the usage counts, never
 * synced, wiped by Forget all. Each kind is kept only as long as it changes a score ([Kind.keepMs]). Immutable; pure.
 */
internal data class ForYouFeedback(val events: List<Event> = emptyList()) {
  enum class Kind(val code: String, val keepMs: Long) {
    /** The chip was on screen (at most once per surface per clock hour). */
    SHOWN("s", WEEK_MS),
    /** The chip was opened: that hour's impression doesn't tire it. */
    OPENED("o", WEEK_MS),
    /** ✕: gone for [SNOOZE_MS], and tires it like an ignored impression. */
    NOT_NOW("n", WEEK_MS),
    LESS_OFTEN("l", 90 * DAY_MS),
    /** "Don't suggest this": never again, until "allow again". */
    NEVER("x", Long.MAX_VALUE),
  }

  data class Event(val scope: String, val surface: String, val kind: Kind, val atMs: Long)

  /** One more [kind] for [surface]; an impression already counted this clock hour is not counted again. */
  fun with(scope: MemoryScope, surface: String, kind: Kind, nowMs: Long): ForYouFeedback {
    if (!SURFACE.matches(surface)) return this
    val duplicate = events.any {
      it.scope == scope.token && it.surface == surface && it.kind == kind &&
          (kind == Kind.NEVER || (kind == Kind.SHOWN && it.atMs / HOUR_MS == nowMs / HOUR_MS))
    }
    if (duplicate) return this
    return ForYouFeedback((events + Event(scope.token, surface, kind, nowMs)).takeLast(MAX_EVENTS))
  }

  /** Undoes "Don't suggest this" for [surface]. */
  fun allowAgain(scope: MemoryScope, surface: String): ForYouFeedback =
      ForYouFeedback(events.filterNot { it.scope == scope.token && it.surface == surface && it.kind == Kind.NEVER })

  fun suppressed(scope: MemoryScope, surface: String): Boolean =
      events.any { it.scope == scope.token && it.surface == surface && it.kind == Kind.NEVER }

  /** True while a ✕ from the last [SNOOZE_MS] keeps [surface] off the row. */
  fun snoozed(scope: MemoryScope, surface: String, nowMs: Long): Boolean = events.any {
    it.scope == scope.token && it.surface == surface && it.kind == Kind.NOT_NOW && nowMs - it.atMs in 0 until SNOOZE_MS
  }

  /**
   * The multiplier on [surface]'s score: [FATIGUE] per impression in the last week that wasn't opened in its hour and
   * per ✕, times [LESS_OFTEN] per "Show less often" in the last 90 days.
   */
  fun factor(scope: MemoryScope, surface: String, nowMs: Long): Double {
    val mine = events.filter { it.scope == scope.token && it.surface == surface && nowMs - it.atMs in 0 until it.kind.keepMs }
    val openedHours = mine.filter { it.kind == Kind.OPENED }.map { it.atMs / HOUR_MS }.toSet()
    val tiring = mine.count { (it.kind == Kind.SHOWN && it.atMs / HOUR_MS !in openedHours) || it.kind == Kind.NOT_NOW }
    val less = mine.count { it.kind == Kind.LESS_OFTEN }
    return Math.pow(FATIGUE, tiring.toDouble()) * Math.pow(LESS_OFTEN, less.toDouble())
  }

  /** Only what still changes a score. */
  fun pruned(nowMs: Long): ForYouFeedback =
      ForYouFeedback(events.filter { it.kind == Kind.NEVER || nowMs - it.atMs in 0 until it.kind.keepMs })

  /** Forget all for [scope]. */
  fun without(scope: MemoryScope): ForYouFeedback = ForYouFeedback(events.filter { it.scope != scope.token })

  fun encode(): String = JSONObject().put("v", VERSION).put("e", JSONArray().apply {
    events.forEach { put(JSONArray().put(it.scope).put(it.surface).put(it.kind.code).put(it.atMs)) }
  }).toString()

  companion object {
    const val VERSION = 1
    const val MAX_EVENTS = 2_000
    const val FATIGUE = 0.8
    const val LESS_OFTEN = 0.5
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS
    const val WEEK_MS = 7 * DAY_MS
    /** ✕ keeps a chip away past its own ±1 h habit window. */
    const val SNOOZE_MS = 2 * HOUR_MS
    private val SURFACE = Regex("[a-z0-9_.:-]{1,80}")

    /** The log in [text]; empty for anything unreadable (it only tunes suggestions). Bad entries are dropped. */
    fun decode(text: String?): ForYouFeedback {
      val root = try {
        JSONObject(text ?: return ForYouFeedback())
      } catch (_: JSONException) {
        return ForYouFeedback()
      }
      if (root.optInt("v") != VERSION) return ForYouFeedback()
      val a = root.optJSONArray("e") ?: return ForYouFeedback()
      return ForYouFeedback((0 until a.length()).mapNotNull { i ->
        runCatching {
          val e = a.getJSONArray(i)
          val kind = Kind.entries.firstOrNull { it.code == e.getString(2) } ?: return@runCatching null
          Event(e.getString(0), e.getString(1), kind, e.getLong(3))
              .takeIf { MemoryScope.fromToken(it.scope) != null && SURFACE.matches(it.surface) }
        }.getOrNull()
      }.takeLast(MAX_EVENTS))
    }
  }
}
