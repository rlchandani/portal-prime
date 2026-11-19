/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBlurTest {
  private fun grey(v: Int) = (255 shl 24) or (v shl 16) or (v shl 8) or v

  @Test fun uniformImagesAreUnchangedIncludingTheirEdges() {
    val px = IntArray(20 * 12) { grey(90) }
    AmbientBlur.blur(px, 20, 12, 4)
    assertTrue(px.all { it == grey(90) })
  }

  @Test fun aBrightSpotSpreadsAndConservesRoughlyItsEnergy() {
    val w = 31
    val h = 31
    val px = IntArray(w * h) { grey(0) }
    px[15 * w + 15] = grey(255)
    AmbientBlur.blur(px, w, h, 3)
    val centre = px[15 * w + 15] and 255
    val near = px[15 * w + 18] and 255
    val far = px[0] and 255
    assertTrue(centre in 1..254)
    assertTrue(near in 1..centre)
    assertEquals(0, far)
    val total = px.sumOf { it and 255 }
    // Rounded integer passes keep the energy near the original 255.
    assertTrue("energy $total", total in 120..400)
  }

  @Test fun outputStaysOpaque() {
    val px = IntArray(9 * 9) { if (it % 2 == 0) grey(255) else grey(0) }
    AmbientBlur.blur(px, 9, 9, 2)
    assertTrue(px.all { it ushr 24 == 255 })
  }

  @Test fun invalidArgumentsAreANoOp() {
    val px = IntArray(4) { grey(it * 60) }
    val copy = px.copyOf()
    AmbientBlur.blur(px, 2, 2, 0)
    AmbientBlur.blur(px, 3, 3, 2)
    assertArrayEquals(copy, px)
  }
}
