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

  @Test fun bareGlyphWithoutKnownTileGetsNeutralTile() {
    // The Home Assistant case on device: a bare glyph whose package has no known identity tile.
    assertEquals(IconRenderer.IconBacking.NEUTRAL_TILE,
        IconRenderer.iconBacking(hasBitmap = true, fullBleed = false, hasIdentity = false))
  }

  @Test fun bareGlyphWithKnownTileStaysBranded() {
    // A known approved package keeps its branded identity tile, unchanged.
    assertEquals(IconRenderer.IconBacking.BRANDED_TILE,
        IconRenderer.iconBacking(hasBitmap = true, fullBleed = false, hasIdentity = true))
  }

  @Test fun fullBleedArtworkIsLeftUntouched() {
    // An icon with its own background is never double-tiled, known or not.
    assertEquals(IconRenderer.IconBacking.RAW_ARTWORK,
        IconRenderer.iconBacking(hasBitmap = true, fullBleed = true, hasIdentity = false))
    assertEquals(IconRenderer.IconBacking.RAW_ARTWORK,
        IconRenderer.iconBacking(hasBitmap = true, fullBleed = true, hasIdentity = true))
  }

  @Test fun noArtworkFallsBackToCatalogGlyph() {
    assertEquals(IconRenderer.IconBacking.NO_ARTWORK,
        IconRenderer.iconBacking(hasBitmap = false, fullBleed = false, hasIdentity = false))
    assertEquals(IconRenderer.IconBacking.NO_ARTWORK,
        IconRenderer.iconBacking(hasBitmap = false, fullBleed = false, hasIdentity = true))
  }

  @Test fun catalogGlyphWithIdentityIsTiledOnThePlainGrid() {
    // The "Home Assistant" built-in: a catalog glyph (no artwork) with a known identity on the
    // plain Apps grid gets its branded tile, never a bare glyph, like its tiled Home siblings.
    assertTrue(IconRenderer.tiled(plain = true, hasIdentity = true))
  }

  @Test fun catalogGlyphWithoutIdentityStaysUntiledOnThePlainGrid() {
    assertFalse(IconRenderer.tiled(plain = true, hasIdentity = false))
  }

  @Test fun folderMembersAreAlwaysTiled() {
    assertTrue(IconRenderer.tiled(plain = false, hasIdentity = false))
    assertTrue(IconRenderer.tiled(plain = false, hasIdentity = true))
  }
}
