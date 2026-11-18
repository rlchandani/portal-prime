/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The prewarm only composes while nobody has touched the screen for a while ([ccQuietWait]). */
class ControlCenterPrewarmQuietTest {
  @Test fun goesAtOnceWhenUntouchedOrLongQuiet() {
    assertEquals(0L, ccQuietWait(now = 10_000L, lastInputAt = 0L))
    assertEquals(0L, ccQuietWait(now = 10_000L, lastInputAt = 7_000L))
  }

  @Test fun waitsOutTheRestOfTheQuietPeriod() {
    assertEquals(2_000L, ccQuietWait(now = 10_000L, lastInputAt = 9_000L))
    assertEquals(3_000L, ccQuietWait(now = 10_000L, lastInputAt = 10_000L))
  }

  @Test fun neverPollsFasterThanItsFloor() {
    assertEquals(250L, ccQuietWait(now = 10_000L, lastInputAt = 7_100L))
  }
}
