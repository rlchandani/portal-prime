/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class PaperWidgetContrastTest {
  @Test fun blackClockUsesLightPlate() {
    assertEquals(PaperWidgetContrast.LIGHT, PaperWidgetContrast.backing(0xFF000000.toInt()))
    assertTrue(PaperWidgetContrast.contrast(0xFF000000.toInt(), PaperWidgetContrast.LIGHT) > 15.0)
  }

  @Test fun darkGreenClockUsesLightPlate() {
    assertEquals(PaperWidgetContrast.LIGHT, PaperWidgetContrast.backing(0xFF343A30.toInt()))
    assertTrue(PaperWidgetContrast.contrast(0xFF343A30.toInt(), PaperWidgetContrast.LIGHT) > 9.0)
  }

  @Test fun whiteClockRetainsExistingDarkPlate() {
    assertEquals(PaperWidgetContrast.DARK, PaperWidgetContrast.backing(0xFFFFFFFF.toInt()))
    assertTrue(PaperWidgetContrast.contrast(0xFFFFFFFF.toInt(), PaperWidgetContrast.DARK) > 10.0)
  }

  @Test fun whiteCaptionAndDarkClockChooseIndependently() {
    val clock = PaperWidgetContrast.backing(0xFF000000.toInt())
    val caption = PaperWidgetContrast.backing(0xCCFFFFFF.toInt())
    assertEquals(PaperWidgetContrast.LIGHT, clock)
    assertEquals(PaperWidgetContrast.DARK, caption)
    assertTrue(PaperWidgetContrast.contrast(0xCCFFFFFF.toInt(), caption) > 6.0)
  }

  @Test fun transparentForegroundHasNoInventedContrast() {
    assertEquals(PaperWidgetContrast.DARK, PaperWidgetContrast.backing(0x00343A30))
    assertEquals(1.0, PaperWidgetContrast.contrast(0x00343A30, PaperWidgetContrast.LIGHT), 0.0)
  }

  @Test fun selectedPlateNeverLosesAgainstTheOtherForColorAndAlphaSamples() {
    for (alpha in listOf(32, 64, 128, 192, 255)) {
      for (rgb in listOf(0, 0x343A30, 0x777777, 0xCC4444, 0x4488AA, 0xFFFFFF)) {
        val ink = (alpha shl 24) or rgb
        val selected = PaperWidgetContrast.backing(ink)
        val other = if (selected == PaperWidgetContrast.LIGHT) PaperWidgetContrast.DARK
            else PaperWidgetContrast.LIGHT
        assertTrue("ARGB ${ink.toUInt().toString(16)}",
            PaperWidgetContrast.contrast(ink, selected) >= PaperWidgetContrast.contrast(ink, other))
      }
    }
  }

  @Test fun clockAndMetadataMergeWhileWhiteCaptionKeepsItsOwnBand() {
    val bands = PaperWidgetContrast.bands(280, listOf(
        PaperWidgetContrast.Widget(12, 180, 0xFF343A30.toInt()),
        PaperWidgetContrast.Widget(180, 212, 0xFF343A30.toInt()),
        PaperWidgetContrast.Widget(230, 268, 0xFFFFFFFF.toInt())))
    assertEquals(listOf(
        PaperWidgetContrast.Band(0, 221, PaperWidgetContrast.LIGHT),
        PaperWidgetContrast.Band(221, 280, PaperWidgetContrast.DARK)), bands)
  }

  @Test fun classicWhiteWidgetsRetainOneContinuousDarkPlate() {
    assertEquals(listOf(PaperWidgetContrast.Band(0, 280, PaperWidgetContrast.DARK)),
        PaperWidgetContrast.bands(280, listOf(
            PaperWidgetContrast.Widget(12, 180, 0xFFFFFFFF.toInt()),
            PaperWidgetContrast.Widget(200, 268, 0xCCFFFFFF.toInt()))))
  }

  @Test fun emptyOrUnmeasuredBucketsPaintNothing() {
    assertTrue(PaperWidgetContrast.bands(200, emptyList()).isEmpty())
    assertTrue(PaperWidgetContrast.bands(0, listOf(
        PaperWidgetContrast.Widget(0, 0, 0xFFFFFFFF.toInt()))).isEmpty())
  }
}
