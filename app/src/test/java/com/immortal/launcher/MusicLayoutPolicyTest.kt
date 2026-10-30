/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicLayoutPolicyTest {
  @Test fun coverLeavesRoomForMetadataAndControlsOnPortal() {
    val edge = MusicLayoutPolicy.coverEdge(660f, 780f, small = false, fontScale = 1f)
    assertTrue(edge in 400f..480f)
    assertTrue(edge + MusicLayoutPolicy.reservedHeight(false, 1f) <= 780f)
  }

  @Test fun shortLandscapeReducesArtworkBeforeControls() {
    val edge = MusicLayoutPolicy.coverEdge(440f, 390f, false, 1f)
    assertTrue(edge <= 390f - MusicLayoutPolicy.reservedHeight(false, 1f))
    assertTrue(edge > 0f)
  }

  @Test fun reservationCoversTheRenderedRowsRatherThanOnlyItsOwnArithmetic() {
    val full = (34f + 22f + 16f + 14f) * 1.3f + 48f + 80f + 5 * 12f + 20f
    val small = (26f + 18f + 15f + 14f) * 1.3f + 48f + 52f + 5 * 12f + 20f
    assertTrue(MusicLayoutPolicy.reservedHeight(false, 1f) >= full)
    assertTrue(MusicLayoutPolicy.reservedHeight(true, 1f) >= small)
  }

  @Test fun capabilityHelpUsesSpaceOtherwiseReservedForArtwork() {
    val ordinary = MusicLayoutPolicy.coverEdge(480f, 780f, false, 1f)
    val withHelp = MusicLayoutPolicy.coverEdge(480f, 780f, false, 1f, extraHeight = 68f)
    assertEquals(68f, ordinary - withHelp, .01f)
  }

  @Test fun largeFontsReceiveMoreSpaceInsteadOfEnlargingCover() {
    val ordinary = MusicLayoutPolicy.coverEdge(420f, 660f, false, 1f)
    val enlarged = MusicLayoutPolicy.coverEdge(420f, 660f, false, 1.8f)
    assertTrue(enlarged < ordinary)
    assertTrue(enlarged + MusicLayoutPolicy.reservedHeight(false, 1.8f) <= 660f)
  }

  @Test fun narrowAndPhotoPanesStayInsideTheirAvailableWidth() {
    for (width in listOf(120f, 280f, 390f, 600f)) {
      for (small in listOf(false, true)) {
        val edge = MusicLayoutPolicy.coverEdge(width, 600f, small, 1f)
        assertTrue(edge > 0f && edge <= width)
        if (small) assertTrue(edge <= 200f)
      }
    }
  }

  @Test fun unboundedAndInvalidConstraintsHaveBoundedFallbacks() {
    for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
      for (height in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
        val edge = MusicLayoutPolicy.coverEdge(width, height, false, Float.NaN)
        assertTrue(edge.isFinite() && edge in 24f..480f)
      }
    }
  }
}
