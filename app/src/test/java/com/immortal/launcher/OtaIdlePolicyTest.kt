/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaIdlePolicyTest {
  private val minute = 60_000L
  /** 03:00, nobody around, nothing playing: the ideal moment. */
  private val idleNight = OtaIdleSignals(musicPlaying = false, inCall = false, routineOnScreen = false,
      sinceTouchMs = null, minuteOfDay = 3 * 60, quietHours = false, pendingForMs = 0)

  private fun wait(s: OtaIdleSignals, manual: Boolean = false) = OtaIdlePolicy.waitReason(s, manual)

  @Test fun idleNightInstalls() = assertNull(wait(idleNight))

  @Test fun activityBlocksAutoInstall() {
    assertEquals(OtaWait.MUSIC, wait(idleNight.copy(musicPlaying = true)))
    assertEquals(OtaWait.CALL, wait(idleNight.copy(inCall = true)))
    assertEquals(OtaWait.ROUTINE, wait(idleNight.copy(routineOnScreen = true)))
    assertEquals(OtaWait.RECENT_TOUCH, wait(idleNight.copy(sinceTouchMs = 9 * minute)))
    assertNull(wait(idleNight.copy(sinceTouchMs = 10 * minute)))
  }

  @Test fun manualUpdateBypassesIdle() {
    val busy = idleNight.copy(musicPlaying = true, inCall = true, routineOnScreen = true,
        sinceTouchMs = 0, minuteOfDay = 12 * 60)
    assertNull(wait(busy, manual = true))
  }

  @Test fun installWindowIsTwoToFiveOrQuietHours() {
    assertNull(wait(idleNight.copy(minuteOfDay = 2 * 60)))
    assertNull(wait(idleNight.copy(minuteOfDay = 5 * 60 - 1)))
    assertEquals(OtaWait.INSTALL_WINDOW, wait(idleNight.copy(minuteOfDay = 5 * 60)))
    assertEquals(OtaWait.INSTALL_WINDOW, wait(idleNight.copy(minuteOfDay = 2 * 60 - 1)))
    assertNull(wait(idleNight.copy(minuteOfDay = 23 * 60, quietHours = true)))
  }

  @Test fun aLongPendingUpdateNoLongerWaitsForTheWindow() {
    val noon = idleNight.copy(minuteOfDay = 12 * 60)
    assertEquals(OtaWait.INSTALL_WINDOW, wait(noon.copy(pendingForMs = OtaConstants.WINDOW_GRACE_MS - 1)))
    assertNull(wait(noon.copy(pendingForMs = OtaConstants.WINDOW_GRACE_MS)))
    assertEquals(OtaWait.MUSIC, wait(noon.copy(pendingForMs = OtaConstants.WINDOW_GRACE_MS, musicPlaying = true)))
  }

  @Test fun fetchIsDueAtStartThenEverySixHours() {
    assertTrue(OtaSchedule.fetchDue(lastFetchElapsedMs = null, lastFetchFailed = false, nowElapsedMs = 5))
    assertFalse(OtaSchedule.fetchDue(0, false, OtaConstants.CHECK_INTERVAL_MS - 1))
    assertTrue(OtaSchedule.fetchDue(0, false, OtaConstants.CHECK_INTERVAL_MS))
    assertFalse(OtaSchedule.fetchDue(0, true, OtaConstants.FAILED_CHECK_RETRY_MS - 1))
    assertTrue(OtaSchedule.fetchDue(0, true, OtaConstants.FAILED_CHECK_RETRY_MS))
  }
}
