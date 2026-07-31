/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * A tiny time-to-live cache for the raw response of a network fetch, backed by SharedPreferences.
 * Generalizes the "cache the last good value + a timestamp, serve it while fresh, and fall back to
 * the stale copy when the network fails" pattern that [Weather]'s geolocation cache pioneered.
 *
 * Every network-backed widget (crypto, news, stocks, options) uses this so it:
 *   1. renders the last value **instantly** on open (no blank card while the fetch runs),
 *   2. survives rate limits / offline by serving the stale copy, and
 *   3. can show an "as of HH:MM" freshness stamp via [ageMs].
 *
 * Values are opaque strings — callers cache the raw JSON/text and parse it themselves, so this
 * stays serialization-agnostic (matches the org.json-at-the-call-site convention). Immutable in
 * spirit: reads never mutate; [put] replaces rather than edits in place.
 */
object HttpCache {

  private const val PREFS = "immortal_http_cache"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The cached value for [key], or null if never stored. Does not consider freshness. */
  fun get(context: Context, key: String): String? = prefs(context).getString(valueKey(key), null)

  /** Milliseconds since [key] was last stored, or null if never stored. Use for "as of" stamps. */
  fun ageMs(context: Context, key: String, now: Long = System.currentTimeMillis()): Long? {
    val stamp = prefs(context).getLong(timeKey(key), 0L)
    return if (stamp <= 0L) null else (now - stamp).coerceAtLeast(0L)
  }

  /** The epoch-millis timestamp of the last [put] for [key], or null if never stored. */
  fun updatedAt(context: Context, key: String): Long? =
      prefs(context).getLong(timeKey(key), 0L).takeIf { it > 0L }

  /** Store [value] for [key], stamping it with the current time. Replaces any prior value. */
  fun put(context: Context, key: String, value: String, now: Long = System.currentTimeMillis()) {
    prefs(context).edit().putString(valueKey(key), value).putLong(timeKey(key), now).apply()
  }

  /**
   * The core "fetch with fallback" helper. Returns the cached value if it's still within [ttlMs];
   * otherwise runs [fetch] (a blocking network call — invoke from a background dispatcher), caches
   * and returns a non-null result, and on a null/failed fetch falls back to the stale cached value
   * (which may itself be null if we've never succeeded). Never throws — [fetch] failures degrade
   * to the last good value or null.
   *
   * @param ttlMs how long a cached value is considered fresh enough to skip the network entirely.
   */
  fun getOrFetch(
      context: Context,
      key: String,
      ttlMs: Long,
      now: Long = System.currentTimeMillis(),
      fetch: () -> String?,
  ): String? {
    val age = ageMs(context, key, now)
    val cached = get(context, key)
    if (cached != null && age != null && age < ttlMs) return cached
    val fresh = runCatching { fetch() }.getOrNull()
    return if (fresh != null) {
      put(context, key, fresh, now)
      fresh
    } else {
      cached // stale-but-present beats nothing when the network is down
    }
  }

  private fun valueKey(key: String) = "v:$key"

  private fun timeKey(key: String) = "t:$key"
}
