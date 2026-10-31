/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-owned calendar cadence; retained hosts explicitly run it again when they become visible. */
internal class CalendarMinuteRefresh(
    private val clock: () -> Long,
    private val cancel: (Runnable) -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val refresh: () -> Unit,
) : Runnable {
  private var lastMinute: Long? = null

  override fun run() {
    cancel(this)
    val now = clock()
    val minute = Math.floorDiv(now, 60_000L)
    if (minute != lastMinute) {
      lastMinute = minute
      refresh()
    }
    schedule(this, DarkRoomPolicy.nextMinuteDelay(now))
  }
}
