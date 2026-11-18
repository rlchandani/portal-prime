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

/** Dry run stands in for HA's echo, so a state change restarts the "since" clock like HA would. */
class ControlCenterDryRunEchoTest {
  private val closed = CcEntity("cover.garage", "closed", mapOf("device_class" to "garage"), lastChanged = 1_000L, userId = "u1")

  @Test
  fun stateChange_stampsLastChangedNow() {
    val echo = dryRunEcho(closed, CcOptimistic(state = "open"), now = 9_000L)
    assertEquals("open", echo.state)
    assertEquals(9_000L, echo.lastChanged)
    assertNull(echo.userId)
  }

  @Test
  fun attributeOnlyChange_keepsLastChanged() {
    val on = CcEntity("light.desk", "on", mapOf("brightness" to 10), lastChanged = 1_000L)
    val echo = dryRunEcho(on, CcOptimistic(brightnessPct = 50), now = 9_000L)
    assertEquals(1_000L, echo.lastChanged)
  }
}
