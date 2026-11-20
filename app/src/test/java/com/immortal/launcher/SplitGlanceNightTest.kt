/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.PhotoFrameNight.Ambient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock-fidelity SS-7: Split glance in a dark room keeps the split layout, dimmed (the approved
 * mock's night state), instead of the full-screen quiet clock or the full-bleed night photo.
 */
class SplitGlanceNightTest {
  @Test fun aDarkRoomDimsSplitGlanceForEveryNightColour() {
    ScreensaverConfig.NIGHT_TONES.forEach { tone ->
      assertEquals(tone, Ambient.SPLIT_DIM, PhotoFrameNight.ambient(true, false, false, tone, nativePhotoMode = true, splitGlance = true))
      assertEquals(tone, Ambient.SPLIT_DIM, PhotoFrameNight.ambient(false, true, false, tone, nativePhotoMode = true, splitGlance = true))
    }
  }

  @Test fun otherModesAreUnchanged() {
    assertEquals(Ambient.QUIET_CLOCK, PhotoFrameNight.ambient(true, false, false, "warm", nativePhotoMode = true))
    assertEquals(Ambient.NIGHT_PHOTO, PhotoFrameNight.ambient(true, false, false, ScreensaverConfig.NIGHT_TONE_PHOTO, nativePhotoMode = true))
  }

  @Test fun splitGlanceWithoutTheNativeStageKeepsTheQuietClock() {
    assertEquals(Ambient.QUIET_CLOCK, PhotoFrameNight.ambient(true, false, false, "warm", nativePhotoMode = false, splitGlance = true))
  }

  @Test fun nothingChangesWhenStoppedOrAlreadyThere() {
    assertEquals(Ambient.NONE, PhotoFrameNight.ambient(true, false, true, "warm", true, splitGlance = true))
    assertEquals(Ambient.NONE, PhotoFrameNight.ambient(true, true, false, "warm", true, splitGlance = true))
  }

  @Test fun theStageDrawsTheSplitCardAtNight() {
    val s = ScreensaverConfig.Settings(mode = ScreensaverConfig.MODE_SPLIT_GLANCE)
    assertEquals(SlideshowStage.Feature.SPLIT_NIGHT, PhotoFrameStagePolicy.presentation(s, false, true, splitNight = true).feature)
    assertEquals(SlideshowStage.Feature.SPLIT, PhotoFrameStagePolicy.presentation(s, false, true).feature)
    assertEquals(SlideshowStage.Feature.NIGHT_PHOTO, PhotoFrameStagePolicy.presentation(s, true, true, splitNight = true).feature)
    assertTrue(SlideshowStage.Feature.SPLIT_NIGHT.split)
    assertTrue(SlideshowStage.Feature.SPLIT.split)
    assertFalse(SlideshowStage.Feature.NIGHT_PHOTO.split)
  }

  @Test fun theNightInkIsTheMocksInkUnderItsVeil() {
    // rgba(243,208,172) x the night multiply (.58 dim, .34 warm): R .58, G ~.52, B ~.48.
    val ink = SplitGlancePanel.nightInk(0xFFF3D0AC.toInt())
    assertEquals(0xFF, ink ushr 24)
    assertEquals(141.0, (ink shr 16 and 255).toDouble(), 2.0)
    assertEquals(108.0, (ink shr 8 and 255).toDouble(), 2.0)
    assertEquals(82.0, (ink and 255).toDouble(), 2.0)
    assertEquals("alpha is kept", 0x7E, SplitGlancePanel.nightInk(0x7EF3D0AC.toInt()) ushr 24)
    assertEquals("day is untouched", 0xFFFBF7F1.toInt(), SplitGlancePanel.shade(0xFFFBF7F1.toInt(), 0f))
    assertEquals(SplitGlancePanel.nightInk(0xFFFBF7F1.toInt()), SplitGlancePanel.shade(0xFFFBF7F1.toInt(), 1f))
  }
}
