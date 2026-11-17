/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepCountdownTest {
    private val now = 1_000_000L

    @Test fun idleShowsTheLengthAStartWouldSet() {
        assertFalse(SleepCountdown.isRunning(0L, now))
        assertEquals("30:00", SleepCountdown.display(0L, now, 30))
        assertEquals("2:00:00", SleepCountdown.display(0L, now, 120))
    }

    @Test fun runningCountsDownWithSecondsRoundedUp() {
        val ends = now + SleepCountdown.durationMs(30)
        assertTrue(SleepCountdown.isRunning(ends, now))
        assertEquals("30:00", SleepCountdown.display(ends, now, 30))
        assertEquals("29:59", SleepCountdown.display(ends, now + 1_000, 30))
        assertEquals("00:01", SleepCountdown.label(1))
        assertEquals("01:05", SleepCountdown.label(65_000))
    }

    @Test fun anExpiredTimerIsIdleAgain() {
        val ends = now + 5_000
        assertFalse(SleepCountdown.isRunning(ends, ends))
        assertEquals(0L, SleepCountdown.remainingMs(ends, ends + 10_000))
        assertEquals("15:00", SleepCountdown.display(ends, ends + 1, 15))
    }

    @Test fun ticksLandOnSecondBoundariesAndNeverSpin() {
        assertEquals(1_000L, SleepCountdown.nextTickMs(5_000))
        assertEquals(250L, SleepCountdown.nextTickMs(4_250))
        assertEquals(1L, SleepCountdown.nextTickMs(4_001))
        assertEquals(1_000L, SleepCountdown.nextTickMs(0))
        assertTrue((1L..100_000L step 997).all { SleepCountdown.nextTickMs(it) in 1L..1_000L })
    }
}
