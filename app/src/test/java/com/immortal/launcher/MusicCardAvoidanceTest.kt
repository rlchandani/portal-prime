/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCardAvoidanceTest {
  private fun card(x: Float, y: Float) = MusicFloatRect(x, y, 300f, 200f, areaWidth = 1000f, areaHeight = 800f)

  @Test fun noCardOrACardAboveTheToastBandLeavesToastsCentred() {
    assertEquals(MusicToastInsets.NONE, MusicCardAvoidance.toastInsets(null, band = 200f, gap = 20f))
    assertEquals(MusicToastInsets.NONE, MusicCardAvoidance.toastInsets(card(600f, 100f), band = 200f, gap = 20f))
  }

  @Test fun toastsCentreInTheWiderSpaceBesideACardInTheirBand() {
    // Bottom-right card: toasts go left of it.
    assertEquals(MusicToastInsets(0f, 1000f - 650f + 20f), MusicCardAvoidance.toastInsets(card(650f, 580f), 200f, 20f))
    // Bottom-left card: toasts go right of it.
    assertEquals(MusicToastInsets(50f + 300f + 20f, 0f), MusicCardAvoidance.toastInsets(card(50f, 580f), 200f, 20f))
  }

  @Test fun aCardSpanningTheMiniUprightLiftsToastsAboveItInsteadOfSqueezingThem() {
    // 800 dp Mini upright (px = dp here): the 752 dp card at x = 24 leaves 24 dp beside it.
    val mini = MusicFloatRect(24f, 900f, 752f, 256f, areaWidth = 800f, areaHeight = 1180f)
    assertEquals(MusicToastInsets(0f, 0f, bottom = 1180f - 900f + 24f),
        MusicCardAvoidance.toastInsets(mini, band = 220f, gap = 24f, minWidth = 480f))
    // The Portal+ keeps toasts beside the card when the side is wide enough.
    assertEquals(MusicToastInsets(0f, 1000f - 650f + 20f), MusicCardAvoidance.toastInsets(card(650f, 580f), 200f, 20f, minWidth = 480f))
  }

  @Test fun theHomeCardStaysInsideTheMiniUpright() {
    val side = MusicHomeCardDims.sideWidth(areaWidth = 800, inset = 24)
    assertEquals(MusicHomeCardDims.SIDE_W - 36, side)
    assertTrue(MusicHomeCardDims.widthOf(MusicHomeCardMode.CARD, side) <= 800 - 2 * 24)
    assertEquals("wide windows keep the shipped card", MusicHomeCardDims.SIDE_W, MusicHomeCardDims.sideWidth(1280, 24))
    assertEquals(MusicHomeCardDims.SIDE_W, MusicHomeCardDims.sideWidth(1920, 24))
  }

  @Test fun notificationsKeepTheirEdgeWithoutTheHomeCard() {
    assertTrue(MusicCardAvoidance.notificationAtBottom(true, null, card(0f, 580f)))
    assertTrue(MusicCardAvoidance.notificationAtBottom(true, MusicPlayerHostKind.MUSIC_SCREEN, card(0f, 580f)))
    assertFalse(MusicCardAvoidance.notificationAtBottom(false, MusicPlayerHostKind.HOME_CARD, null))
  }

  @Test fun notificationsTakeTheEdgeAwayFromTheCard() {
    assertFalse(MusicCardAvoidance.notificationAtBottom(true, MusicPlayerHostKind.HOME_CARD, card(0f, 580f)))
    assertTrue(MusicCardAvoidance.notificationAtBottom(false, MusicPlayerHostKind.HOME_CARD, card(0f, 20f)))
  }
}
