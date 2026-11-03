/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Literal wall-clock histories at the real pure crash-loop decision boundary. */
class CrashGuardClockTest {
  @Test
  fun backwardClockCorrectionResetsANormalStreak() {
    assertEquals(
        true to 1,
        CrashGuard.decide(
            nowMs = 1_700_000_000_000L,
            lastCrashMs = 1_700_000_001_000L,
            prevCount = 1))
  }

  @Test
  fun backwardClockCorrectionReenablesRelaunchAfterTheRapidCap() {
    assertEquals(
        true to 1,
        CrashGuard.decide(
            nowMs = 1_700_000_000_000L,
            lastCrashMs = 1_700_000_060_000L,
            prevCount = 3))
  }

  @Test
  fun equalTimestampStillContinuesTheRapidStreakPastTheCap() {
    assertEquals(
        false to 4,
        CrashGuard.decide(
            nowMs = 1_700_000_000_000L,
            lastCrashMs = 1_700_000_000_000L,
            prevCount = 3))
  }

  @Test
  fun forwardCrashInsideTheWindowRetainsTheRapidStreak() {
    assertEquals(
        true to 3,
        CrashGuard.decide(
            nowMs = 1_700_000_009_999L,
            lastCrashMs = 1_700_000_000_000L,
            prevCount = 2))
  }

  @Test
  fun exactWindowBoundaryResetsTheRapidStreak() {
    assertEquals(
        true to 1,
        CrashGuard.decide(
            nowMs = 1_700_000_010_000L,
            lastCrashMs = 1_700_000_000_000L,
            prevCount = 3))
  }

  @Test
  fun wellSpacedForwardCrashReenablesASuppressedStreak() {
    assertEquals(
        true to 1,
        CrashGuard.decide(
            nowMs = 1_700_000_060_000L,
            lastCrashMs = 1_700_000_000_000L,
            prevCount = 4))
  }
}
