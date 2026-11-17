/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wash holds its drift while a list scrolls and resumes without a jump. */
class MusicWashClockTest {
  private val quiet = MusicWashClock.QUIET_MS

  @Test fun driftsFromTheFirstFrame() {
    val clock = MusicWashClock()
    assertEquals(0f, clock.secondsAt(10_000L)!!, 0f)
    assertEquals(1.5f, clock.secondsAt(11_500L)!!, 1e-4f)
  }

  @Test fun holdsWhileScrollingAndForTheQuietTimeAfter() {
    val clock = MusicWashClock()
    clock.secondsAt(0L)
    clock.onScroll(1_000L)
    assertNull(clock.secondsAt(1_000L))
    clock.onScroll(1_050L) // a fling reports a delta every frame
    assertNull(clock.secondsAt(1_050L + quiet - 1))
  }

  @Test fun resumesWhereItStoppedSkippingTheHeldTime() {
    val clock = MusicWashClock()
    clock.secondsAt(0L)
    assertEquals(1f, clock.secondsAt(1_000L)!!, 1e-4f)
    clock.onScroll(1_000L)
    assertNull(clock.secondsAt(1_050L))
    assertNull(clock.secondsAt(1_200L))
    // Held from 1 050 until the list is quiet at 1 000 + quiet: the drift picks up from ~1 s.
    val resumed = clock.secondsAt(1_000L + quiet)!!
    assertEquals(1f + (1_050L - 1_000L) / 1_000f, resumed, 1e-4f)
    assertEquals(resumed + 0.05f, clock.secondsAt(1_000L + quiet + 50L)!!, 1e-4f)
  }

  @Test fun nestedScrollDeltasHoldTheDriftAndConsumeNothing() {
    var now = 5_000L
    val clock = MusicWashClock(nowMs = { now })
    clock.secondsAt(now)
    val left = clock.connection.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)
    assertEquals(Offset.Zero, left)
    assertNull(clock.secondsAt(now + 16L))
    now += quiet
    assertEquals(0.016f, clock.secondsAt(now)!!, 1e-4f)
  }
}
