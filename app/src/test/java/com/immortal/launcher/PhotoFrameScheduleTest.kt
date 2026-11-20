/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The dwell and its deadline across viewer pauses and a suspended host, on a fake clock. */
class PhotoFrameScheduleTest {
  private val clock = FakePhotoFrameClock(uptime = 10_000L)
  private val schedule = PhotoFrameSchedule(clock)

  @Test fun theNightPhotoHoldsLongerWithATenSecondFloor() {
    assertEquals(17_000L, PhotoFrameSchedule.intervalMs(17, nightPhoto = false))
    assertEquals(48_000L, PhotoFrameSchedule.intervalMs(30, nightPhoto = true))
    assertEquals(10_000L, PhotoFrameSchedule.intervalMs(5, nightPhoto = true))
  }

  @Test fun aPauseKeepsWhatIsLeftAndResumesIt() {
    schedule.arm(20_000L, hostActive = true)
    assertEquals(30_000L, schedule.holdDeadline)
    clock.uptime += 5_000L
    schedule.pause(hostActive = true)
    assertEquals(15_000L, schedule.remainingHold)
    clock.uptime += 60_000L
    assertEquals(15_000L, schedule.resumeDelay())
  }

  @Test fun anExpiredHoldResumesAfterTheMinimum() {
    schedule.arm(1_000L, hostActive = true)
    clock.uptime += 5_000L
    schedule.pause(hostActive = true)
    assertEquals(PhotoFrameSchedule.MIN_HOLD_MS, schedule.remainingHold)
    assertEquals(PhotoFrameSchedule.MIN_HOLD_MS, schedule.leftAfterManifest())
  }

  @Test fun aSuspendedHostFreezesTheDwellAndMovesTheDeadline() {
    schedule.arm(20_000L, hostActive = true)
    clock.uptime += 4_000L
    schedule.suspend(clock.uptime)
    clock.uptime += 90_000L
    assertEquals("the dwell clock stands still while away", 14_000L, schedule.playbackUptime(hostActive = false))
    schedule.pause(hostActive = false)
    assertEquals(16_000L, schedule.remainingHold)
    schedule.resume(clock.uptime)
    assertEquals(120_000L, schedule.holdDeadline)
    assertEquals(16_000L, schedule.leftAfterManifest())
  }

  @Test fun resumingBeforeAnyHoldLeavesTheDeadlineAlone() {
    schedule.suspend(10_000L)
    schedule.resume(50_000L)
    assertEquals(0L, schedule.holdDeadline)
  }

  @Test fun videosAndAlbumsHaveTheirOwnClocks() {
    assertEquals(PhotoFrameSchedule.VIDEO_SAFETY_MS, PhotoFrameSchedule.videoHoldMs(17_000L))
    assertEquals(600_000L, PhotoFrameSchedule.videoHoldMs(600_000L))
    assertEquals(15 * 60_000L, PhotoFrameSchedule.albumRefreshMs(15))
  }
}
