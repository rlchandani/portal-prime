/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** microWakeWord's rule: the mean of the last five probabilities must clear 0.97. */
class WakeScoreTest {
  private fun scoreOf(vararg q8: Int) = q8.fold(WakeScore()) { s, q -> s.next(q) }

  @Test fun nothingTriggersBeforeTheWindowIsFull() {
    val four = scoreOf(255, 255, 255, 255)
    assertEquals(0f, four.mean)
    assertFalse(four.triggered)
    assertTrue(four.next(255).triggered)
  }

  @Test fun theMeanMustClearTheCutoff() {
    // 0.97 × 256 = 248.3: five frames of 248 stay below it, 249 clears it.
    assertFalse(scoreOf(248, 248, 248, 248, 248).triggered)
    assertTrue(scoreOf(249, 249, 249, 249, 249).triggered)
  }

  @Test fun oneConfidentFrameAmongDoubtfulOnesIsNotAWake() {
    assertFalse(scoreOf(30, 30, 255, 30, 30).triggered)
  }

  @Test fun onlyTheNewestFiveCount() {
    val s = scoreOf(0, 0, 0, 255, 255, 255, 255, 255)
    assertEquals(listOf(255, 255, 255, 255, 255), s.recent)
    assertTrue(s.triggered)
  }

  @Test fun outOfRangeOutputsAreClamped() {
    assertEquals(listOf(0, 255), scoreOf(-5, 999).recent)
  }

  @Test fun scoringNeverMutatesThePreviousScore() {
    val before = scoreOf(1, 2)
    before.next(3)
    assertEquals(listOf(1, 2), before.recent)
  }
}
