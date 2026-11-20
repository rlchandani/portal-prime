/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Rate limit for the fleet backup routes: at most [maxPerWindow] calls per route in any
 * [windowMs], and [minGapMs] between two calls to the same route. A backup is a manual operator
 * action that runs a few times a day, while each call costs real work on the Portal (an RSA key
 * pair, gzip and AES over every preference), so the limits are tight. Pure; [clock] is monotonic.
 */
internal class FleetBackupLimiter(
    private val maxPerWindow: Int = 30,
    private val windowMs: Long = 60 * 60_000L,
    private val minGapMs: Long = 2_000L,
    private val clock: () -> Long,
) {
  private val calls = HashMap<String, ArrayDeque<Long>>()

  /** Null when [route] may run now (and the call is counted), else the seconds to wait. */
  @Synchronized fun acquire(route: String): Long? {
    val now = clock()
    val recent = calls.getOrPut(route) { ArrayDeque() }
    while (recent.isNotEmpty() && now - recent.first() >= windowMs) recent.removeFirst()
    val waitMs = when {
      recent.isNotEmpty() && now - recent.last() < minGapMs -> minGapMs - (now - recent.last())
      recent.size >= maxPerWindow -> windowMs - (now - recent.first())
      else -> 0L
    }
    if (waitMs > 0) return (waitMs + 999) / 1000
    recent.addLast(now)
    return null
  }
}
