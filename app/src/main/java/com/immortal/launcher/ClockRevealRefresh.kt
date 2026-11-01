/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Refresh a retained time snapshot at reveal and minute boundaries, not every fade frame. */
internal class ClockRevealRefresh(private val publish: (Long) -> Unit) {
  private var lastMinute: Long? = null

  fun update(wallClockMs: Long) {
    val minute = Math.floorDiv(wallClockMs, 60_000L)
    if (lastMinute == minute) return
    lastMinute = minute
    publish(wallClockMs)
  }

  fun reset() { lastMinute = null }
}
