/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewClockTest {
    private val zone = ZoneId.of("UTC")
    private val afternoon = ZonedDateTime.of(2026, 11, 16, 15, 7, 42, 0, zone).toInstant().toEpochMilli()

    @Test fun followsTheTimeFormat() {
        assertEquals("15:07", PreviewClock.time(afternoon, use24Hour = true, zone = zone))
        assertEquals("3:07", PreviewClock.time(afternoon, use24Hour = false, zone = zone))
        assertEquals(15, PreviewClock.hour(afternoon, zone))
    }

    @Test fun ticksOnTheNextMinute() {
        assertEquals(18_000L, PreviewClock.msUntilNextMinute(afternoon))
        val onBoundary = afternoon - 42_000
        assertEquals(60_000L, PreviewClock.msUntilNextMinute(onBoundary))
        assertTrue(PreviewClock.msUntilNextMinute(onBoundary - 1) == 1L)
    }

    @Test fun touchTestExplainsWhenTouchSoundsAreOff() {
        assertTrue(touchSoundTestMessage(enabled = false, tapped = true).startsWith("Touch sounds are off"))
        assertTrue(touchSoundTestMessage(enabled = true, tapped = false).startsWith("Tap the button"))
        assertTrue(touchSoundTestMessage(enabled = true, tapped = true).contains("click"))
    }
}
