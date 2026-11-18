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
