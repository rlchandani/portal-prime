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

  /** A shown screen's cameras start once it is awake (a few frames in); a parked one's stop once it stops lingering; a prewarmed one never starts. */
  @Test fun aScreenIsLiveOnceAwakeAndUntilItStopsLingering() {
    assertEquals(true, ccSlotLive(active = true, awake = true, lingering = true))
    assertEquals(false, ccSlotLive(active = true, awake = false, lingering = true))
    assertEquals(false, ccSlotLive(active = true, awake = false, lingering = false))
    assertEquals(true, ccSlotLive(active = false, awake = false, lingering = true))
    assertEquals(false, ccSlotLive(active = false, awake = false, lingering = false))
  }

  /** Animations run at once on a shown screen and until a parked one stops lingering. */
  @Test fun aScreenMovesWhileShownOrLingering() {
    assertEquals(true, ccSlotMoving(active = true, lingering = false))
    assertEquals(true, ccSlotMoving(active = true, lingering = true))
    assertEquals(true, ccSlotMoving(active = false, lingering = true))
    assertEquals(false, ccSlotMoving(active = false, lingering = false))
  }

  /** Only a parked, recording screen is transparent; a shown one is opaque (a detached one draws nothing anyway). */
  @Test fun onlyARecordingSlotIsTransparent() {
    assertEquals(1f, ccSlotAlpha(CcSlotDraw.SHOWN))
    assertEquals(0f, ccSlotAlpha(CcSlotDraw.RECORDING))
    assertEquals(1f, ccSlotAlpha(CcSlotDraw.DETACHED))
  }

  /** A screen is in the accessibility tree exactly while it is the spoken screen: shown or parked, the switch frame changes nothing. */
  @Test fun aScreenIsInTheSemanticsTreeOnlyWhileSpoken() {
    assertEquals(true, ccSlotSemantics(spoken = true))
    assertEquals(false, ccSlotSemantics(spoken = false))
  }

  /** A parked screen holds no region textures (they'd re-render while hidden); a shown one caches once ready. */
  @Test fun regionsAreCachedOnlyWhileShownAndReady() {
    assertEquals(true, ccSlotCachesRegions(active = true, cacheReady = true))
    assertEquals(false, ccSlotCachesRegions(active = true, cacheReady = false))
    assertEquals(false, ccSlotCachesRegions(active = false, cacheReady = true))
    assertEquals(false, ccSlotCachesRegions(active = false, cacheReady = false))
  }

  @Test fun aCachedRegionIsAnOffscreenTextureAndAnUncachedOneDrawsStraight() {
    assertEquals(androidx.compose.ui.graphics.CompositingStrategy.Offscreen, ccRegionStrategy(cache = true))
    assertEquals(androidx.compose.ui.graphics.CompositingStrategy.Auto, ccRegionStrategy(cache = false))
  }
}
