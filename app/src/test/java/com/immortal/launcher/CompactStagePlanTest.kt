/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CompactStagePlan.Ground
import com.immortal.launcher.CompactStagePlan.Pairing
import com.immortal.launcher.CompactStagePlan.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 native styles on the Portal Mini (mock mini-screensaver-20261120); LARGE never planned. */
class CompactStagePlanTest {
  private val cp = LayoutClass.COMPACT_PORTRAIT
  private val cl = LayoutClass.COMPACT_LANDSCAPE
  private val landscape = 1472 to 981 // 3:2
  private val landscape43 = 1472 to 1104 // 4:3
  private val portrait = 981 to 1472 // 2:3
  private val square = 1200 to 1200
  private val panorama = 1472 to 490 // 3:1

  private fun plan(style: Style, cls: LayoutClass, vararg photos: Pair<Int, Int>, motion: Boolean = true,
                   backdrop: Boolean = true) =
      CompactStagePlan.plan(style, cls, photos.toList(), if (cls == cp) 800 else 1280, if (cls == cp) 1280 else 800,
          1f, motion, parity = false, backdrop = backdrop)

  /** Every pose of a tile's motion covers its holder (no gaps), or for a contained card fills it. */
  private fun assertCovers(tile: CompactStagePlan.Tile, size: Pair<Int, Int>) {
    for (t in listOf(tile.motion.start, tile.motion.end, tile.motion.at(.5f))) {
      val w = size.first * t.scale
      val h = size.second * t.scale
      assertTrue("left edge", t.x <= 1e-3f)
      assertTrue("top edge", t.y <= 1e-3f)
      assertTrue("right edge", t.x + w >= tile.box.width - 1e-2f)
      assertTrue("bottom edge", t.y + h >= tile.box.height - 1e-2f)
    }
  }

  @Test fun largeIsNeverPlanned() {
    for (style in Style.values()) {
      assertNull(CompactStagePlan.plan(style, LayoutClass.LARGE, listOf(landscape, landscape43), 2160, 1371,
          1.125f, true, false, true))
    }
    assertNull(CompactStagePlan.plan(null, cp, listOf(landscape), 800, 1280, 1f, true, false, true))
  }

  @Test fun largePairingIsTheShippedRule() {
    for (mode in ScreensaverConfig.MODES) {
      val expected = if (PhotoFrameStagePolicy.pairs(mode)) Pairing.PORTRAIT else Pairing.NONE
      assertEquals(mode, expected, CompactStagePlan.pairing(mode, LayoutClass.LARGE))
      assertEquals(mode, expected, CompactStagePlan.pairing(mode, LayoutClass.LARGE, singleFeature = true))
    }
  }

  @Test fun compactPairingFollowsTheScreen() {
    assertEquals(Pairing.LANDSCAPE, CompactStagePlan.pairing(ScreensaverConfig.MODE_SMART, cp))
    assertEquals(Pairing.LANDSCAPE, CompactStagePlan.pairing(ScreensaverConfig.MODE_PORTRAITS, cp))
    assertEquals(Pairing.LANDSCAPE, CompactStagePlan.pairing(ScreensaverConfig.MODE_GALLERY_SPREAD, cp))
    assertEquals(Pairing.NONE, CompactStagePlan.pairing(ScreensaverConfig.MODE_GALLERY_PRINT, cp))
    assertEquals(Pairing.NONE, CompactStagePlan.pairing(ScreensaverConfig.MODE_SMART, cl))
    assertEquals(Pairing.PORTRAIT, CompactStagePlan.pairing(ScreensaverConfig.MODE_PORTRAITS, cl))
    // Night photo and Split glance are single-photo scenes.
    assertEquals(Pairing.NONE, CompactStagePlan.pairing(ScreensaverConfig.MODE_SMART, cp, singleFeature = true))
    assertEquals(Pairing.NONE, CompactStagePlan.pairing(ScreensaverConfig.MODE_PORTRAITS, cl, singleFeature = true))
  }

  @Test fun smartFrameStacksTwoLandscapesUpright() {
    val p = plan(Style.SMART, cp, landscape, landscape43)!!
    assertEquals(Ground.BACKDROP, p.ground)
    assertEquals(CompactStagePlan.PAIR_BACKDROP, p.backdropBrightness, 0f)
    val (a, b) = p.tiles
    assertEquals(ScreensaverLayout.Box(24, 24, 776, 24 + 608), a.box)
    assertEquals(ScreensaverLayout.Box(24, 24 + 608 + 16, 776, 24 + 2 * 608 + 16), b.box)
    assertEquals(18f, a.cornerDp, 0f)
    assertCovers(a, landscape)
    assertCovers(b, landscape43)
    // Opposite drifts: the top card grows, the bottom one settles.
    assertTrue(a.motion.end.scale > a.motion.start.scale)
    assertTrue(b.motion.end.scale < b.motion.start.scale)
  }

  @Test fun smartFrameSinglesUpright() {
    val fill = plan(Style.SMART, cp, portrait)!!
    assertEquals(Ground.BLACK, fill.ground)
    assertEquals(ScreensaverLayout.Box(0, 0, 800, 1280), fill.tiles.single().box)
    assertCovers(fill.tiles.single(), portrait)
    val whole = plan(Style.SMART, cp, landscape)!!
    assertEquals(Ground.BACKDROP, whole.ground)
    val card = whole.tiles.single().box
    // Whole, raised clear of the clock (zone 32/40/32/290).
    assertEquals(736, card.width)
    assertTrue(card.top >= 40 && card.bottom <= 1280 - 290)
    assertEquals(736f / 1472, whole.tiles.single().motion.start.scale, 1e-3f)
    val sweep = plan(Style.SMART, cp, panorama)!!.tiles.single()
    assertEquals(1280f / 490, sweep.motion.start.scale, 1e-4f)
    assertEquals(0f, sweep.motion.start.x, 0f)
    assertEquals(800 - 1472 * 1280f / 490, sweep.motion.end.x, 1e-2f)
  }

  @Test fun smartFrameTurnedFillsLandscapesAndRaisesPortraits() {
    assertEquals(Ground.BLACK, plan(Style.SMART, cl, landscape)!!.ground)
    val portraitPlan = plan(Style.SMART, cl, portrait)!!
    assertEquals(Ground.BACKDROP, portraitPlan.ground)
    // A Smart frame pair that came from the upright screen shows its first photo after a turn.
    assertEquals(1, plan(Style.SMART, cl, landscape, landscape43)!!.tiles.size)
  }

  @Test fun noBackdropFallsBackToTheMatte() {
    assertEquals(Ground.MATTE, plan(Style.SMART, cp, landscape, backdrop = false)!!.ground)
  }

  @Test fun stillMotionHoldsEveryPose() {
    val p = plan(Style.SMART, cp, portrait, motion = false)!!.tiles.single()
    assertEquals(p.motion.start, p.motion.end)
    assertCovers(p, portrait)
  }

  @Test fun pairStacksEdgeToEdgeUprightAndSideBySideTurned() {
    val up = plan(Style.PAIR, cp, landscape, landscape43)!!
    assertEquals(ScreensaverLayout.Box(0, 0, 800, 634), up.tiles[0].box)
    assertEquals(ScreensaverLayout.Box(0, 646, 800, 1280), up.tiles[1].box)
    assertEquals(0f, up.tiles[0].cornerDp, 0f)
    val turned = plan(Style.PAIR, cl, portrait, portrait)!!
    assertEquals(ScreensaverLayout.Box(32, 32, 32 + 598, 768), turned.tiles[0].box)
    assertEquals(ScreensaverLayout.Box(650, 32, 1248, 768), turned.tiles[1].box)
    turned.tiles.forEach { assertCovers(it, portrait) }
    // Upright a portrait pair (left from the turned screen) shows its first photo full-bleed.
    val rotated = plan(Style.PAIR, cp, portrait, portrait)!!
    assertEquals(Ground.BLACK, rotated.ground)
    assertEquals(1, rotated.tiles.size)
  }

  @Test fun wholePhotoNeverCrops() {
    val up = plan(Style.WHOLE, cp, landscape)!!
    assertEquals(Ground.BACKDROP, up.ground)
    val t = up.tiles.single()
    assertEquals(t.motion.start, t.motion.end)
    assertEquals(752, t.box.width)
    assertTrue(t.box.bottom <= 1280 - 280)
    // 2:3 on an 800x1280 panel mismatches by 1.07, under 1.15: whole on black, full screen.
    val near = plan(Style.WHOLE, cp, portrait)!!
    assertEquals(Ground.BLACK, near.ground)
    assertEquals(800, near.tiles.single().box.width)
    assertEquals(Ground.BACKDROP, plan(Style.WHOLE, cl, portrait)!!.ground)
  }

  @Test fun galleryStylesUseTheFixedZoneUprightAndShipTurned() {
    val print = plan(Style.PRINT, cp, landscape)!!
    assertEquals(Ground.PRINT, print.ground)
    val box = print.tiles.single().box
    assertEquals(720, box.width)
    assertTrue(box.top >= 48 && box.bottom <= 1280 - 282)
    assertTrue(print.tiles.single().border)
    val spread = plan(Style.SPREAD, cp, landscape, landscape43)!!
    assertEquals(2, spread.tiles.size)
    assertTrue(spread.tiles[0].box.bottom + 24 <= spread.tiles[1].box.top)
    assertNull(plan(Style.PRINT, cl, landscape))
    assertNull(plan(Style.SPREAD, cl, portrait, portrait))
  }

  @Test fun pairsAreRecheckedPerClass() {
    assertTrue(CompactStagePlan.pairs(Style.SMART, cp, landscape, landscape43))
    assertFalse(CompactStagePlan.pairs(Style.SMART, cp, landscape, portrait))
    assertFalse(CompactStagePlan.pairs(Style.SMART, cl, landscape, landscape43))
    assertTrue(CompactStagePlan.pairs(Style.PAIR, cl, portrait, portrait))
    assertFalse(CompactStagePlan.pairs(Style.PAIR, cp, portrait, portrait))
    // LARGE and an unknown style never drop a companion.
    assertTrue(CompactStagePlan.pairs(Style.PAIR, LayoutClass.LARGE, portrait, portrait))
    assertTrue(CompactStagePlan.pairs(null, cp, portrait, portrait))
    assertFalse(ScreensaverLayout.stackable(square.first, square.second))
  }

  @Test fun wholeCardsAndPrintsClearTheUprightCalendarCard() {
    val calendar = ScreensaverLayout.Box(40, 40, 760, 240)
    val clock = ScreensaverLayout.Box(40, 1080, 400, 1224)
    fun first(style: Style, vararg photos: Pair<Int, Int>) = CompactStagePlan.plan(style, cp, photos.toList(), 800, 1280, 1f,
        true, false, true, occupied = listOf(calendar, clock))!!.tiles.first().box
    assertTrue(first(Style.SMART, landscape).top >= 256)
    assertTrue(first(Style.WHOLE, landscape).top >= 256)
    assertTrue(first(Style.PRINT, landscape).top >= 256)
    assertTrue(first(Style.SPREAD, landscape, landscape43).top >= 256)
    // Full-bleed photos and stacks keep the whole screen (the overlays sit over them by design).
    assertEquals(0, first(Style.SMART, portrait).top)
    assertEquals(24, first(Style.SMART, landscape, landscape43).top)
    // A bottom overlay never moves the zone, and the zone keeps at least half its height.
    val zone = ScreensaverLayout.Box(32, 40, 768, 990)
    assertEquals(zone, CompactStagePlan.clearTop(zone, listOf(clock), 1280, 1f))
    assertEquals(40 + 475, CompactStagePlan.clearTop(zone, listOf(ScreensaverLayout.Box(0, 0, 800, 600)), 1280, 1f).top)
  }

  @Test fun driftClampsToCover() {
    val m = CompactStagePlan.drift(1000, 1000, 800, 600, 1f, 1.02f, .3f, -.3f, 0f, 0f)
    assertCovers(CompactStagePlan.Tile(0, ScreensaverLayout.Box(0, 0, 800, 600), m), 1000 to 1000)
  }

  @Test fun classicPansTheLongAxis() {
    val up = ScreensaverLayout.classicPan(1500, 1000, 800, 1280, 0)!!
    assertTrue(up.pan)
    // Contained 800x533, covered to the full 1280 height, travelling the whole overflow.
    assertEquals(1280f / 533.3333f, up.scale0, 1e-3f)
    assertEquals((1920f - 800f) / 2f, up.x0, .5f)
    assertEquals(-up.x0, up.x1, 0f)
    assertEquals(0f, up.y0, 0f)
    val reverse = ScreensaverLayout.classicPan(1500, 1000, 800, 1280, 1)!!
    assertEquals(-up.x0, reverse.x0, 0f)
    val turned = ScreensaverLayout.classicPan(1000, 1500, 1280, 800, 0)!!
    assertTrue(turned.pan)
    assertEquals(0f, turned.x0, 0f)
    assertTrue(turned.y0 > 0f)
    val zoom = ScreensaverLayout.classicPan(1000, 1500, 800, 1280, 0)!!
    assertFalse(zoom.pan)
    assertEquals(zoom.scale0 * ScreensaverLayout.CLASSIC_ZOOM, zoom.scale1, 1e-4f)
    // The drift never outruns the zoom's overscan.
    assertTrue(kotlin.math.abs(zoom.x1) <= (800 * zoom.scale1 / zoom.scale0 - 800) / 2f)
    assertNull(ScreensaverLayout.classicPan(0, 1, 800, 1280, 0))
  }

  @Test fun cinemaIsBestTurnedOnlyOnTheMini() {
    assertEquals(ScreensaverConfig.BEST_TURNED, ScreensaverConfig.modeHint("cinema", compact = true))
    assertEquals("", ScreensaverConfig.modeHint("cinema", compact = false))
    assertEquals("", ScreensaverConfig.modeHint(ScreensaverConfig.MODE_SMART, compact = true))
    assertNotNull(CompactStagePlan.style(ScreensaverConfig.MODE_SMART))
    assertNull(CompactStagePlan.style(ScreensaverConfig.MODE_SPLIT_GLANCE))
  }
}
