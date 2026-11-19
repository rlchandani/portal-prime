/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAnalysisTest {
  private fun argb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b

  private fun image(w: Int, h: Int, colour: (Int, Int) -> Int) = IntArray(w * h) { colour(it % w, it / w) }

  @Test fun lightnessIsPerceptualAndOrdered() {
    assertEquals(0f, PhotoAnalysis.lightness(argb(0, 0, 0)), 1e-3f)
    assertEquals(1f, PhotoAnalysis.lightness(argb(255, 255, 255)), 1e-3f)
    // sRGB mid-grey (119) is perceptual mid-lightness.
    assertEquals(.5f, PhotoAnalysis.lightness(argb(119, 119, 119)), .01f)
    assertTrue(PhotoAnalysis.lightness(argb(0, 255, 0)) > PhotoAnalysis.lightness(argb(0, 0, 255)))
  }

  @Test fun regionAveragesOnlyTheRequestedArea() {
    // Top half white, bottom half black.
    val a = PhotoAnalysis.of(image(64, 64) { _, y -> if (y < 32) argb(255, 255, 255) else argb(0, 0, 0) }, 64, 64)
    assertEquals(1f, a.region(0f, 0f, 1f, .5f).luminance, .01f)
    assertEquals(0f, a.region(0f, .5f, 1f, 1f).luminance, .01f)
    assertEquals(.5f, a.region(0f, .25f, 1f, .75f).luminance, .02f)
  }

  @Test fun regionClampsAndRejectsEmptyAreas() {
    val a = PhotoAnalysis.of(image(32, 32) { _, _ -> argb(255, 255, 255) }, 32, 32)
    assertEquals(1f, a.region(-1f, -1f, 2f, 2f).luminance, .01f)
    assertEquals(.4f, a.region(.5f, .5f, .5f, .9f).luminance, 0f)
  }

  @Test fun textureSeparatesBusyFromFlatZones() {
    val busy = PhotoAnalysis.of(image(64, 64) { x, y -> if ((x + y) % 2 == 0) argb(255, 255, 255) else argb(0, 0, 0) }, 64, 64)
    val flat = PhotoAnalysis.of(image(64, 64) { _, _ -> argb(128, 128, 128) }, 64, 64)
    assertTrue(busy.region(0f, 0f, 1f, 1f).texture > .4f)
    assertEquals(0f, flat.region(0f, 0f, 1f, 1f).texture, 1e-3f)
  }

  @Test fun dominantPicksTheMostPopulousVividHue() {
    val px = image(40, 40) { x, _ -> if (x < 30) argb(30, 120, 220) else argb(230, 60, 40) }
    val c = PhotoAnalysis.dominant(px, 40, 40)!!
    assertTrue((c and 255) > (c shr 16 and 255)) // blue wins
  }

  @Test fun monochromePhotosHaveNoDominantColour() {
    assertNull(PhotoAnalysis.dominant(image(20, 20) { x, _ -> argb(x * 12, x * 12, x * 12) }, 20, 20))
  }

  @Test fun saliencyFollowsDetailAndIgnoresFlatImages() {
    val flat = PhotoAnalysis.saliency(FloatArray(48 * 32) { .5f }, 48, 32)
    assertNull(flat)
    val luma = FloatArray(48 * 32) { i -> val x = i % 48; val y = i / 48; if (x in 34..42 && y in 8..20 && (x + y) % 2 == 0) 1f else .2f }
    val focus = PhotoAnalysis.saliency(luma, 48, 32)
    assertNotNull(focus)
    assertTrue("focus leans right: $focus", focus!!.x > .55f)
    assertTrue(focus.x <= 1f && focus.y in 0f..1f)
  }

  @Test fun analysisToleratesBadInput() {
    val a = PhotoAnalysis.of(IntArray(3), 10, 10)
    assertEquals(.4f, a.region(0f, 0f, 1f, 1f).luminance, 1e-5f)
    assertNull(a.dominant)
  }

  @Test fun rgbToHslRoundTripsThroughTheAccentConverter() {
    val out = FloatArray(3)
    PhotoAnalysis.rgbToHsl(255, 0, 0, out)
    assertEquals(0f, out[0], .5f)
    assertEquals(1f, out[1], 1e-3f)
    assertEquals(.5f, out[2], 1e-3f)
    assertEquals(argb(255, 0, 0), HearthReadability.hslToArgb(0f, 1f, .5f))
  }
}
