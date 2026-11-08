/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class YtPlayerSizeTest {
  @Test fun notLaidOut_isNotAViolation() {
    assertEquals(YtSizeVerdict.NOT_LAID_OUT, YtPlayerSize.verdict(0, 0, 2f))
    assertEquals(YtSizeVerdict.NOT_LAID_OUT, YtPlayerSize.verdict(500, 0, 2f))
  }

  @Test fun measuresInCssPixels() {
    assertEquals(YtSizeVerdict.OK, YtPlayerSize.verdict(400, 400, 2f))
    assertEquals(YtSizeVerdict.TOO_SMALL, YtPlayerSize.verdict(399, 400, 2f))
    assertEquals(YtSizeVerdict.TOO_SMALL, YtPlayerSize.verdict(1_000, 300, 2f))
    assertEquals(YtSizeVerdict.OK, YtPlayerSize.verdict(200, 200, 1f))
  }

  @Test fun badDensity_fallsBackToRawPixels() {
    assertEquals(YtSizeVerdict.OK, YtPlayerSize.verdict(200, 200, 0f))
  }
}
