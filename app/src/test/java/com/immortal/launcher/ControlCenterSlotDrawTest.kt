/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Parked screens leave the render tree once their draw lists are recorded ([ccSlotDraw]). */
class ControlCenterSlotDrawTest {
  @Test fun theShownScreenAlwaysDraws() {
    for (screen in CC_KEEP_ALIVE) {
      assertEquals(CcSlotDraw.SHOWN, ccSlotDraw(screen, active = true, settled = false))
      assertEquals(CcSlotDraw.SHOWN, ccSlotDraw(screen, active = true, settled = true))
    }
  }

  @Test fun aJustParkedScreenRecordsTransparentFirst() {
    assertEquals(CcSlotDraw.RECORDING, ccSlotDraw(CcScreen.CAMERAS, active = false, settled = false))
    assertEquals(CcSlotDraw.RECORDING, ccSlotDraw(CcScreen.SECURITY, active = false, settled = false))
  }

  @Test fun aSettledParkedScreenLeavesTheTree() {
    for (screen in CC_KEEP_ALIVE - CcScreen.MAP) {
      assertEquals(CcSlotDraw.DETACHED, ccSlotDraw(screen, active = false, settled = true))
    }
  }

  @Test fun theMapIsNeverDetached() {
    assertEquals(CcSlotDraw.RECORDING, ccSlotDraw(CcScreen.MAP, active = false, settled = true))
  }
}
