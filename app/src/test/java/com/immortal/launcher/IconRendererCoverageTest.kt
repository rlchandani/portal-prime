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

/**
 * The opaque-fill heuristic that tells a full-bleed app icon from a bare glyph (e.g. the Home
 * Assistant companion mark), so the grid can place the latter on a tile instead of letting it float.
 */
class IconRendererCoverageTest {
  private val OPAQUE = 0xFF336699.toInt()
  private val CLEAR = 0x00000000

  @Test fun emptyIsZero() {
    assertEquals(0f, IconRenderer.opaqueFraction(IntArray(0)), 0f)
  }

  @Test fun fullyOpaqueIsOne() {
    assertEquals(1f, IconRenderer.opaqueFraction(IntArray(100) { OPAQUE }), 0f)
  }

  @Test fun fullyTransparentIsZero() {
    assertEquals(0f, IconRenderer.opaqueFraction(IntArray(100) { CLEAR }), 0f)
  }

  @Test fun sparseGlyphIsBelowFillThreshold() {
    // A monochrome glyph on a transparent field: a fifth of the pixels painted.
    val pixels = IntArray(100) { if (it < 20) OPAQUE else CLEAR }
    assertEquals(0.2f, IconRenderer.opaqueFraction(pixels), 1e-6f)
    assertTrue(IconRenderer.opaqueFraction(pixels) < IconRenderer.MIN_OPAQUE_FILL)
  }

  @Test fun fullBleedTileIsAboveFillThreshold() {
    // A squircle icon fills most of its bitmap (corners cleared by the mask).
    val pixels = IntArray(100) { if (it < 92) OPAQUE else CLEAR }
    assertTrue(IconRenderer.opaqueFraction(pixels) >= IconRenderer.MIN_OPAQUE_FILL)
  }

  @Test fun nearlyTransparentPixelsDoNotCount() {
    // Alpha below the opaque cutoff (anti-aliased edges) is not a background.
    val faint = 0x40_33_66_99
    assertFalse(IconRenderer.opaqueFraction(IntArray(100) { faint }) >= IconRenderer.MIN_OPAQUE_FILL)
  }
}
