/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What the Portal is doing when an automatic install is considered. */
internal data class OtaIdleSignals(
    val musicPlaying: Boolean,
    val inCall: Boolean,
    val routineOnScreen: Boolean,
    /** Time since the last touch, or null when there was none this process. */
    val sinceTouchMs: Long?,
    /** Local minute of the day, 0 until 1439. */
    val minuteOfDay: Int,
    /** Inside the user's quiet hours (Sounds settings). */
    val quietHours: Boolean,
    /** How long this update has been available without installing. */
    val pendingForMs: Long,
)

/** Why an automatic install is waiting. */
internal enum class OtaWait(val wire: String) {
  CALL("call"),
  ROUTINE("routine"),
  MUSIC("music"),
  RECENT_TOUCH("recent_touch"),
  INSTALL_WINDOW("install_window"),
}

/**
 * Automatic installs never interrupt anyone: no call, routine, music or touch in the last
 * [OtaConstants.TOUCH_IDLE_MS], and preferably 02:00 to 05:00 or quiet hours. An update pending
 * longer than [OtaConstants.WINDOW_GRACE_MS] may install at any idle moment. "Update now" skips all of
 * this (never the integrity checks).
 */
internal object OtaIdlePolicy {
  /** Null means install now. */
  fun waitReason(s: OtaIdleSignals, manual: Boolean): OtaWait? = when {
    manual -> null
    s.inCall -> OtaWait.CALL
    s.routineOnScreen -> OtaWait.ROUTINE
    s.musicPlaying -> OtaWait.MUSIC
    s.sinceTouchMs != null && s.sinceTouchMs < OtaConstants.TOUCH_IDLE_MS -> OtaWait.RECENT_TOUCH
    inWindow(s.minuteOfDay) || s.quietHours || s.pendingForMs >= OtaConstants.WINDOW_GRACE_MS -> null
    else -> OtaWait.INSTALL_WINDOW
  }

  fun inWindow(minuteOfDay: Int): Boolean =
      minuteOfDay >= OtaConstants.WINDOW_START_MIN && minuteOfDay < OtaConstants.WINDOW_END_MIN
}

internal object OtaSchedule {
  /** Fetch at the first tick after start, then every [OtaConstants.CHECK_INTERVAL_MS] (sooner after a failure). */
  fun fetchDue(lastFetchElapsedMs: Long?, lastFetchFailed: Boolean, nowElapsedMs: Long): Boolean {
    if (lastFetchElapsedMs == null) return true
    val interval = if (lastFetchFailed) OtaConstants.FAILED_CHECK_RETRY_MS else OtaConstants.CHECK_INTERVAL_MS
    return nowElapsedMs - lastFetchElapsedMs >= interval
  }
}
