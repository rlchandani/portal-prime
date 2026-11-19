/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.DigitalClockFaceSpec.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Split glance, Night photo and the redesigned digital clock screensaver: pure rules. */
class NewScreensaverModesTest {
  @Test fun splitGlanceIsANativePreferredStyleWithMotion() {
    assertTrue(ScreensaverConfig.isNativeStage(ScreensaverConfig.MODE_SPLIT_GLANCE))
    assertTrue(ScreensaverConfig.supportsPhotoMotion(ScreensaverConfig.MODE_SPLIT_GLANCE))
    assertEquals("Split glance", ScreensaverConfig.modeLabel(ScreensaverConfig.MODE_SPLIT_GLANCE))
    assertFalse(ScreensaverConfig.usesEngine(ScreensaverConfig.MODE_SPLIT_GLANCE))
  }

  @Test fun splitGlanceLayoutSitsSideBySideInLandscapeAndStacksInPortrait() {
    val (card, panel) = SplitGlancePanel.layout(2160, 1440, 1.125f)
    assertEquals((2160 * .56f).toInt(), card.width)
    assertTrue(panel.right <= card.left)
    assertTrue(panel.width > 0)
    val (pCard, pPanel) = SplitGlancePanel.layout(800, 1220, 1f)
    assertTrue(pPanel.top >= pCard.bottom)
    assertEquals(pCard.width, pPanel.width)
    val (lCard, lPanel) = SplitGlancePanel.layout(1280, 740, 1f) // Mini landscape
    assertTrue(lPanel.right <= lCard.left && lPanel.width > 300)
  }

  @Test fun splitGlanceShowsTheEventInProgressOrTheNextOne() {
    val now = 1_000_000L
    val past = CalendarFeed.Event(0L, 500_000L, "Done", false)
    val live = CalendarFeed.Event(900_000L, 1_200_000L, "Now", false)
    val later = CalendarFeed.Event(2_000_000L, 2_500_000L, "Later", false)
    val allDay = CalendarFeed.Event(0L, 9_000_000L, "Holiday", true)
    assertEquals("Now", SplitGlancePanel.nextEvent(listOf(later, past, live, allDay), now)?.title)
    assertEquals("Later", SplitGlancePanel.nextEvent(listOf(later, past), now)?.title)
    assertNull(SplitGlancePanel.nextEvent(listOf(past, allDay), now))
  }

  @Test fun nightPhotoHoldsPhotosLonger() {
    assertEquals(48_000L, NightPhotoOverlay.intervalMs(30_000L))
    assertEquals(10_000L, NightPhotoOverlay.intervalMs(5_000L))
    assertTrue(ScreensaverConfig.NIGHT_TONE_PHOTO in ScreensaverConfig.NIGHT_TONES)
  }

  @Test fun digitalFacesMapEveryStoredStyle() {
    assertEquals(Face.CLASSIC, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_CLASSIC))
    assertEquals(Face.BOLD, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_BOLD))
    assertEquals(Face.NEON, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_NEON))
    assertEquals(Face.LED, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_SEGMENT))
    assertEquals(Face.FLIP, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_FLIP))
    assertEquals(Face.ANALOG, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_ANALOG))
    assertEquals(Face.CLASSIC, DigitalClockFaceSpec.face("unknown"))
    DigitalClockConfig.FACES.forEach { assertTrue(it.id, DigitalClockFaceSpec.face(it.style).name.isNotEmpty()) }
  }

  @Test fun sevenSegmentTableMatchesTheDigitShapes() {
    assertEquals(7, DigitalClockFaceSpec.segments('8').length)
    assertEquals("bc", DigitalClockFaceSpec.segments('1'))
    assertEquals("", DigitalClockFaceSpec.segments(' '))
    for (c in '0'..'9') DigitalClockFaceSpec.segments(c).forEach {
      assertTrue(DigitalClockFaceSpec.SEGMENT_PATHS.containsKey(it))
    }
  }

  @Test fun sizesFitAndNeverEnlarge() {
    assertEquals(250f, DigitalClockFaceSpec.timeSizeDp(DigitalClockConfig.SIZE_LARGE), 0f)
    assertTrue(DigitalClockFaceSpec.timeSizeDp(DigitalClockConfig.SIZE_SMALL) <
        DigitalClockFaceSpec.timeSizeDp(DigitalClockConfig.SIZE_XL))
    assertEquals(100f, DigitalClockFaceSpec.fit(200f, 1000f, 500f), 1e-3f)
    assertEquals(200f, DigitalClockFaceSpec.fit(200f, 100f, 500f), 0f)
  }

  @Test fun flipCardsKeepTheMockProportionsAndFitNarrowPanels() {
    val wide = DigitalClockFaceSpec.flip(576f, 2400f)
    assertEquals(576f * .74f, wide.cardW, 1e-3f)
    assertTrue(wide.total <= 2400f)
    val mini = DigitalClockFaceSpec.flip(512f, 720f) // portrait Mini: 40% of 1280, 90% of 800
    assertEquals(720f, mini.total, .5f)
    assertTrue(mini.cardH < 512f)
    assertEquals(0f, DigitalClockFaceSpec.flipProgress(3, 100L), 0f)
    assertEquals(1f, DigitalClockFaceSpec.flipProgress(0, 10_000L), 0f)
  }

  @Test fun analogHandsSweepContinuously() {
    val a = DigitalClockFaceSpec.handAngles(3, 0, 0, 0)
    assertEquals(90f, a[0], 1e-3f)
    assertEquals(0f, a[1], 1e-3f)
    val b = DigitalClockFaceSpec.handAngles(15, 30, 15, 500)
    assertEquals(105f + 15.5f / 120f, b[0], 1e-2f)
    assertEquals(15.5f * 6f, b[2], 1e-3f)
  }

  @Test fun backdropsHonourTheBackgroundSetting() {
    assertEquals(0xFF1A0500.toInt(), DigitalClockFaceSpec.backdrop(Face.CLASSIC, DigitalClockConfig.BG_RED).inner)
    assertTrue(DigitalClockFaceSpec.backdrop(Face.NEON, DigitalClockConfig.BG_BLACK).radial)
    assertFalse(DigitalClockFaceSpec.backdrop(Face.LED, DigitalClockConfig.BG_BLACK).radial)
  }
}
