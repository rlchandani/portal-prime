/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CcFirstDrawTest {
  @Test fun holdsDrawsAndTheBodyUntilReleased() {
    val gate = CcFirstDraw(maxHeldFrames = 8)
    assertFalse(gate.bodyComposed)
    assertFalse(gate.allowDraw())
    assertFalse(gate.allowDraw())
    gate.release()
    assertTrue(gate.bodyComposed)
    assertTrue(gate.allowDraw())
    assertTrue(gate.allowDraw())
  }

  @Test fun drawsAnywayAfterTheHoldLimit() {
    val gate = CcFirstDraw(maxHeldFrames = 3)
    repeat(3) { assertFalse(gate.allowDraw()) }
    assertTrue(gate.allowDraw())
    // A timed-out hold shows the whole screen, not the shell alone.
    assertTrue(gate.bodyComposed)
    assertTrue(gate.allowDraw())
  }

  @Test fun theDefaultLimitCoversTheTwoStagedFrames() {
    assertTrue(CcFirstDraw.MAX_HELD_FRAMES >= 3)
  }
}
