/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicVolumeTest {
  private val m = MusicVolumeMath

  @Test
  fun stepForFraction_snapsToNearestStep() {
    assertEquals(0, m.stepForFraction(0f, 15))
    assertEquals(15, m.stepForFraction(1f, 15))
    assertEquals(6, m.stepForFraction(0.4f, 15)) // 6.0
    assertEquals(7, m.stepForFraction(0.45f, 15)) // 6.75 -> 7
    assertEquals(6, m.stepForFraction(0.42f, 15)) // 6.3 -> 6
  }

  @Test
  fun stepForFraction_clampsOutOfRangeAndNaN() {
    assertEquals(0, m.stepForFraction(-0.3f, 15))
    assertEquals(15, m.stepForFraction(2f, 15))
    assertEquals(0, m.stepForFraction(Float.NaN, 15))
  }

  @Test
  fun stepForFraction_zeroMaxIsZero() {
    assertEquals(0, m.stepForFraction(0.7f, 0))
  }

  @Test
  fun fractionForStep_mapsAndClamps() {
    assertEquals(0.4f, m.fractionForStep(6, 15), 1e-6f)
    assertEquals(1f, m.fractionForStep(99, 15), 1e-6f)
    assertEquals(0f, m.fractionForStep(-2, 15), 1e-6f)
    assertEquals(0f, m.fractionForStep(3, 0), 1e-6f)
  }

  @Test
  fun roundTrip_everyStepIsStable() {
    for (max in listOf(7, 15, 25)) {
      for (step in 0..max) {
        assertEquals(step, m.stepForFraction(m.fractionForStep(step, max), max))
      }
    }
  }

  @Test
  fun clampStep_withinRange() {
    assertEquals(0, m.clampStep(-1, 15))
    assertEquals(15, m.clampStep(16, 15))
    assertEquals(9, m.clampStep(9, 15))
  }
}
