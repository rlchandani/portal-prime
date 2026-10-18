/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Serializes cancellation and installation while rejecting superseded solar requests. */
internal class GoldenHourWork {
  private val lock = Any()
  private var generation = 0L

  fun begin(enabled: () -> Boolean): Long? = synchronized(lock) {
    if (!enabled()) null else ++generation
  }

  fun isCurrent(token: Long): Boolean = synchronized(lock) { token == generation }

  fun cancel(action: () -> Unit) = synchronized(lock) {
    generation++
    action()
  }

  fun commit(token: Long, enabled: () -> Boolean, action: () -> Unit): Boolean = synchronized(lock) {
    if (token != generation || !enabled()) false else {
      action()
      true
    }
  }
}

/** Briefly share the whole solar lookup, including geolocation and unavailable results.
 * Cold-start app and alarm-receiver work must not perform two consecutive network lookups. */
internal class GoldenHourSolarCache(
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  private data class Result(val days: List<Weather.SunTimes>?)
  private val values = SingleFlightValueCache<Long, Result>(30_000L, 1, nowMillis)

  fun get(revision: Long, loader: () -> List<Weather.SunTimes>?): List<Weather.SunTimes>? =
      values.get(revision) { Result(runCatching(loader).getOrNull()) }.days

  fun clear() = values.clear()
}
