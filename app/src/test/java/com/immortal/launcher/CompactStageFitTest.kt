/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CompactStagePlan.Ground
import com.immortal.launcher.CompactStagePlan.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo-fit setting on the compact native styles: "fill" keeps the phase-2 rule (fill when the
 * crop keeps 80 %), "fit" always shows every photo whole. LARGE (Portal+) is never planned, so it
 * keeps the shipped presentation for both values.
 */
class CompactStageFitTest {
  private val cp = LayoutClass.COMPACT_PORTRAIT
  private val cl = LayoutClass.COMPACT_LANDSCAPE
  private val landscape = 1472 to 981 // 3:2
  private val landscape43 = 1472 to 1104 // 4:3
  private val portrait = 981 to 1472 // 2:3
  private val panorama = 1472 to 490 // 3:1

  private fun size(cls: LayoutClass) = if (cls == cp) 800 to 1280 else 1280 to 800

  private fun plan(style: Style, cls: LayoutClass, fill: Boolean, vararg photos: Pair<Int, Int>, motion: Boolean = true) =
      size(cls).let { (w, h) ->
        CompactStagePlan.plan(style, cls, photos.toList(), w, h, 1f, motion, parity = false, backdrop = true, fill = fill)
      }

  private fun fill(fit: String, mode: String = ScreensaverConfig.MODE_SMART) =
      PhotoFrameStagePolicy.presentation(ScreensaverConfig.Settings(fit = fit, mode = mode), false, false).fill

  /** The tile shows its whole photo, still: the photo exactly spans the card, nothing cropped. */
  private fun assertWhole(tile: CompactStagePlan.Tile, photo: Pair<Int, Int>) {
    assertEquals("still", tile.motion.start, tile.motion.end)
    val t = tile.motion.start
    assertTrue("photo width inside card", photo.first * t.scale <= tile.box.width + 1f)
    assertTrue("photo height inside card", photo.second * t.scale <= tile.box.height + 1f)
    assertTrue("card hugs the photo", photo.first * t.scale >= tile.box.width - 1f &&
        photo.second * t.scale >= tile.box.height - 1f)
    assertEquals(0f, t.x, 1f)
    assertEquals(0f, t.y, 1f)
  }

  // ---- The setting -------------------------------------------------------------------------

  @Test fun fitSettingHasOnlyFillAndFit() {
    // No "auto": anything else is rejected by fleet/settings coercion, and an unset install fills.
    assertEquals(ScreensaverConfig.FIT_FILL, FleetScreensaver.coerceFit("fill"))
    assertEquals(ScreensaverConfig.FIT_FIT, FleetScreensaver.coerceFit("fit"))
    assertNull(FleetScreensaver.coerceFit("auto"))
    assertEquals(ScreensaverConfig.FIT_FILL, ScreensaverConfig.Settings().fit)
  }

  @Test fun presentationCarriesTheFitSetting() {
    for (mode in listOf(ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS)) {
      assertTrue(mode, fill(ScreensaverConfig.FIT_FILL, mode))
      assertFalse(mode, fill(ScreensaverConfig.FIT_FIT, mode))
    }
    // Whole photo never fills, whatever the setting.
    assertFalse(fill(ScreensaverConfig.FIT_FILL, ScreensaverConfig.MODE_WHOLE))
    assertFalse(fill(ScreensaverConfig.FIT_FIT, ScreensaverConfig.MODE_WHOLE))
  }

  // ---- LARGE (Portal+): never planned, for either value ---------------------------------------

  @Test fun largeIsNeverPlannedForEitherFit() {
    for (fill in listOf(true, false)) for (style in Style.values()) {
      assertNull(CompactStagePlan.plan(style, LayoutClass.LARGE, listOf(portrait), 2160, 1371, 1.125f,
          true, false, true, fill = fill))
      assertNull(CompactStagePlan.plan(style, LayoutClass.LARGE, listOf(landscape, landscape43), 1371, 2160, 1.125f,
          true, false, true, fill = fill))
    }
  }

  // ---- fill: the phase-2 rule ---------------------------------------------------------------

  @Test fun fillKeepsThePhaseTwoRule() {
    // The default is fill, and an explicit fill plans the identical scene.
    for (cls in listOf(cp, cl)) for (style in Style.values()) for (photos in listOf(
        listOf(portrait), listOf(landscape), listOf(panorama), listOf(landscape, landscape43), listOf(portrait, portrait))) {
      val (w, h) = size(cls)
      assertEquals("$cls $style $photos",
          CompactStagePlan.plan(style, cls, photos, w, h, 1f, true, false, true),
          CompactStagePlan.plan(style, cls, photos, w, h, 1f, true, false, true, fill = true))
    }
    // Upright a 2:3 crop keeps over 80 %: full-bleed on black. Turned, a 3:2 landscape fills.
    assertEquals(ScreensaverLayout.Box(0, 0, 800, 1280), plan(Style.SMART, cp, true, portrait)!!.tiles.single().box)
    assertEquals(ScreensaverLayout.Box(0, 0, 1280, 800), plan(Style.SMART, cl, true, landscape)!!.tiles.single().box)
    assertEquals(ScreensaverLayout.Box(0, 0, 800, 1280), plan(Style.PAIR, cp, true, portrait)!!.tiles.single().box)
    // A panorama sweeps at full height.
    assertEquals(1280f / 490, plan(Style.SMART, cp, true, panorama)!!.tiles.single().motion.start.scale, 1e-4f)
  }

  // ---- fit: always whole --------------------------------------------------------------------

  @Test fun fitShowsTheSmartFrameSingleWholeUpright() {
    // 2:3 on 800x1280 mismatches by 1.07: whole, full screen width, on black.
    val near = plan(Style.SMART, cp, false, portrait)!!
    assertEquals(Ground.BLACK, near.ground)
    assertEquals(800, near.tiles.single().box.width)
    assertNotEquals(1280, near.tiles.single().box.height)
    assertWhole(near.tiles.single(), portrait)
    // A landscape is far off the upright panel: whole over its blurred backdrop.
    val far = plan(Style.SMART, cp, false, landscape)!!
    assertEquals(Ground.BACKDROP, far.ground)
    assertWhole(far.tiles.single(), landscape)
  }

  @Test fun fitShowsTheSmartFrameSingleWholeTurned() {
    // 3:2 on 1280x800 mismatches by 1.07: whole on black, full height.
    val near = plan(Style.SMART, cl, false, landscape)!!
    assertEquals(Ground.BLACK, near.ground)
    assertEquals(800, near.tiles.single().box.height)
    assertWhole(near.tiles.single(), landscape)
    val far = plan(Style.SMART, cl, false, portrait)!!
    assertEquals(Ground.BACKDROP, far.ground)
    assertWhole(far.tiles.single(), portrait)
  }

  @Test fun fitNeverSweepsAPanorama() {
    for (cls in listOf(cp, cl)) {
      val p = plan(Style.SMART, cls, false, panorama)!!
      assertEquals(Ground.BACKDROP, p.ground)
      assertWhole(p.tiles.single(), panorama)
    }
  }

  @Test fun fitShowsALonePairPhotoWhole() {
    assertWhole(plan(Style.PAIR, cp, false, portrait)!!.tiles.single(), portrait)
    assertWhole(plan(Style.PAIR, cl, false, landscape)!!.tiles.single(), landscape)
  }

  @Test fun fitStacksWholeLandscapesUpright() {
    for (style in listOf(Style.SMART, Style.PAIR)) {
      val p = plan(style, cp, false, landscape, landscape43)!!
      assertEquals(Ground.BACKDROP, p.ground)
      assertEquals(2, p.tiles.size)
      assertWhole(p.tiles[0], landscape)
      assertWhole(p.tiles[1], landscape43)
      assertTrue(p.tiles[0].box.bottom <= p.tiles[1].box.top)
    }
  }

  @Test fun fitPairsWholePortraitsTurned() {
    val p = plan(Style.PAIR, cl, false, portrait, portrait)!!
    assertEquals(2, p.tiles.size)
    p.tiles.forEach { assertWhole(it, portrait) }
    assertTrue(p.tiles[0].box.right <= p.tiles[1].box.left)
  }

  @Test fun fitIsStillWithMotionOff() {
    assertWhole(plan(Style.SMART, cp, false, portrait, motion = false)!!.tiles.single(), portrait)
  }

  @Test fun wholeAndGalleryStylesIgnoreTheSetting() {
    for (cls in listOf(cp, cl)) for (style in listOf(Style.WHOLE, Style.PRINT, Style.SPREAD)) {
      for (photos in listOf(arrayOf(portrait), arrayOf(landscape), arrayOf(landscape, landscape43))) {
        assertEquals("$cls $style", plan(style, cls, true, *photos), plan(style, cls, false, *photos))
      }
    }
  }
}
