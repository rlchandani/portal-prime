/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicNightCapTest {
  @Test fun stateIsNullWhenOffAndActiveOnlyInQuietHours() {
    assertNull(MusicNightCapMath.state(enabled = false, quietNow = true, maxLevel = 6))
    assertEquals(MusicNightCap(true, 6), MusicNightCapMath.state(true, true, 6))
    assertEquals(MusicNightCap(false, 6), MusicNightCapMath.state(true, false, 6))
    assertEquals(MusicNightCap(false, 18), MusicNightCapMath.state(true, false, 40))
    assertEquals(MusicNightCap(false, 1), MusicNightCapMath.state(true, false, 0))
  }

  @Test fun capStepScalesToTheDevice() {
    assertEquals(6, MusicNightCapMath.capStep(6, 18))
    assertEquals(5, MusicNightCapMath.capStep(6, 15))
    assertEquals(15, MusicNightCapMath.capStep(18, 15))
    assertEquals(1, MusicNightCapMath.capStep(1, 5))
    assertEquals(0, MusicNightCapMath.capStep(6, 0))
  }

  @Test fun capsOnlyRaisesAboveTheMaxDuringQuietHours() {
    val night = MusicNightCap(active = true, maxLevel = 6)
    assertEquals(5, MusicNightCapMath.allowed(12, 15, night))
    assertEquals(3, MusicNightCapMath.allowed(3, 15, night))
    assertEquals(12, MusicNightCapMath.allowed(12, 15, night.copy(active = false)))
    assertEquals(12, MusicNightCapMath.allowed(12, 15, null))
  }
}
