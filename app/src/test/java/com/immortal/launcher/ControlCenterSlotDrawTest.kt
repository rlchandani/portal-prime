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
    for (screen in CC_KEEP_ALIVE - CC_KEEP_RECORDED) {
      assertEquals(CcSlotDraw.DETACHED, ccSlotDraw(screen, active = false, settled = true))
    }
  }

  @Test fun theMapIsNeverDetached() {
    assertEquals(CcSlotDraw.RECORDING, ccSlotDraw(CcScreen.MAP, active = false, settled = true))
  }

  /** Android 10 deletes a detached node's display lists, so showing Alerts again re-recorded all of it (~20 ms). */
  @Test fun alertsStaysRecordedWhileParked() {
    assertEquals(CcSlotDraw.RECORDING, ccSlotDraw(CcScreen.ALERTS, active = false, settled = true))
  }

  @Test fun onlyTheMapAndAlertsStayRecorded() {
    assertEquals(setOf(CcScreen.MAP, CcScreen.ALERTS), CC_KEEP_RECORDED)
  }

  /** A shown screen is live at once; a parked one only after it stops lingering; a prewarmed one never. */
  @Test fun aScreenIsLiveWhileShownOrLingering() {
    assertEquals(true, ccSlotLive(active = true, lingering = false))
    assertEquals(true, ccSlotLive(active = true, lingering = true))
    assertEquals(true, ccSlotLive(active = false, lingering = true))
    assertEquals(false, ccSlotLive(active = false, lingering = false))
  }
}
