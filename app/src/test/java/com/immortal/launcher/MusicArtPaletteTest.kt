/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicArtPaletteTest {
  private val navy = 0xFF1E2A47.toInt()
  private val cream = 0xFFF6E9D0.toInt()
  private val ember = 0xFFD9481A.toInt()
  private val grey = 0xFF7A7A7A.toInt()

  private fun image(vararg parts: Pair<Int, Int>): IntArray =
      parts.flatMap { (color, n) -> List(n) { color } }.toIntArray()

  @Test fun darkestMassIsDeepAndBrightestIsLight() {
    val p = MusicArtPaletteMath.extract(image(navy to 300, cream to 200, ember to 60, grey to 40))
    assertEquals(navy, p.deep)
    assertEquals(cream, p.light)
    assertEquals(ember, p.accent)
    assertEquals(grey, p.mid)
  }

  @Test fun isDeterministic() {
    val px = image(navy to 120, cream to 80, ember to 30, grey to 70)
    assertEquals(MusicArtPaletteMath.extract(px), MusicArtPaletteMath.extract(px.reversedArray()))
  }

  @Test fun aTinySpeckIsNotTheAccent() {
    val p = MusicArtPaletteMath.extract(image(navy to 500, grey to 300, cream to 300, ember to 2))
    assertTrue(p.accent != ember)
  }

  @Test fun transparentOrEmptyImagesFallBack() {
    assertEquals(MusicArtPalette.IDLE, MusicArtPaletteMath.extract(IntArray(0)))
    assertEquals(MusicArtPalette.NEUTRAL, MusicArtPaletteMath.extract(IntArray(50) { 0x00FFFFFF }, MusicArtPalette.NEUTRAL))
  }

  @Test fun aFlatImageGivesOneColourEverywhere() {
    val p = MusicArtPaletteMath.extract(IntArray(64) { ember })
    assertTrue(p.colors.all { it == ember })
  }

  @Test fun coverCropIsTheCentreThreeQuarterSquare() {
    assertEquals(Triple(30, 15, 90), MusicArtPaletteMath.coverCrop(150, 120))
    assertEquals(Triple(0, 0, 1), MusicArtPaletteMath.coverCrop(1, 1))
  }

  @Test fun saturationOfGreyIsZero() {
    assertEquals(0.0, MusicArtPaletteMath.saturation(doubleArrayOf(120.0, 120.0, 120.0)), 1e-9)
    assertEquals(1.0, MusicArtPaletteMath.saturation(doubleArrayOf(255.0, 0.0, 0.0)), 1e-9)
  }
}
