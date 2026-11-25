/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo-fit setting at "fit" holds every photo whole in each style that honours it
 * ([PhotoFitPolicy.honoursFit]), in every scene: the Smart frame day scene (upright and turned),
 * its Night photo, and compact Classic. "fill" keeps the shipped behaviour.
 */
class PhotoFitEverywhereTest {
  private val upright = 800 to 1280 // Portal Mini, upright
  private val plus = 1920 to 1080 // Portal+
  private val portrait34 = 1104 to 1472 // 3:4: the cover crop keeps 83 % upright
  private val portrait23 = 1000 to 1500 // 2:3: keeps 94 % upright
  private val landscape32 = 1500 to 1000

  private fun settings(mode: String, fit: String) = ScreensaverConfig.Settings(mode = mode, fit = fit)

  private fun look(mode: String, fit: String, night: Boolean = false) =
      PhotoFrameStagePolicy.presentation(settings(mode, fit), nightPhoto = night, splitGlance = false)

  // ---- Smart frame upright, day (the backlog report) -----------------------------------------

  @Test fun smartFrameUprightFitNeverFillsAPortraitThatKeepsEightyPercent() {
    val (w, h) = upright
    for (photo in listOf(portrait34, portrait23)) {
      assertTrue("precondition: fill would crop it", ScreensaverLayout.keep(photo.first, photo.second, w, h) >= ScreensaverLayout.FILL_KEEP)
      val fit = look(ScreensaverConfig.MODE_SMART, ScreensaverConfig.FIT_FIT)
      val plan = CompactStagePlan.plan(CompactStagePlan.Style.SMART, LayoutClass.COMPACT_PORTRAIT, listOf(photo),
          w, h, 1f, fit.motion, parity = false, backdrop = true, fill = fit.fill)!!
      val tile = plan.tiles.single()
      val t = tile.motion.start
      assertTrue("whole width", photo.first * t.scale <= tile.box.width + 1f)
      assertTrue("whole height", photo.second * t.scale <= tile.box.height + 1f)
      assertEquals("still", tile.motion.start, tile.motion.end)
    }
  }

  @Test fun smartFrameUprightFillStillFillsThatPortrait() {
    val (w, h) = upright
    val fill = look(ScreensaverConfig.MODE_SMART, ScreensaverConfig.FIT_FILL)
    val plan = CompactStagePlan.plan(CompactStagePlan.Style.SMART, LayoutClass.COMPACT_PORTRAIT, listOf(portrait34),
        w, h, 1f, fill.motion, parity = false, backdrop = true, fill = fill.fill)!!
    assertEquals(ScreensaverLayout.Box(0, 0, w, h), plan.tiles.single().box)
    assertEquals(CompactStagePlan.Ground.BLACK, plan.ground)
  }

  // ---- Smart frame on the Portal+ (LARGE), day -----------------------------------------------
  // LARGE is never planned by CompactStagePlan ([CompactStageFitTest.largeIsNeverPlannedForEitherFit]);
  // the day scene frames each photo through [SlideshowGeometry.plan] with the fit-derived fill flag
  // ([SlideshowStage.display]). A credible central face is what lets "fill" cover-crop a tall photo,
  // so it is the case where honouring fit actually matters.
  private val centralFace = listOf(SlideshowGeometry.Region(.45f, .42f, .55f, .55f))
  private val tallPortrait = 1000 to 1500 // on 1920x1080 a cover crop drops well over half of it

  /** The Portal+ day frame for [photo] under [fit], as the native stage computes it. */
  private fun plusFrame(photo: Pair<Int, Int>, fit: String, regions: List<SlideshowGeometry.Region>) =
      look(ScreensaverConfig.MODE_SMART, fit).let { p ->
        SlideshowGeometry.plan(photo.first, photo.second, plus.first, plus.second, regions, p.fill, p.motion)
      }

  @Test fun smartFramePlusFitShowsThePortraitWholeNeverCropping() {
    val (vw, vh) = plus
    for (regions in listOf(centralFace, emptyList())) {
      val frame = plusFrame(tallPortrait, ScreensaverConfig.FIT_FIT, regions)
      assertTrue("fit is whole, never a crop", frame.whole)
      // Contained and centred: the entire photo is on screen, letterboxed.
      val contain = minOf(vw.toFloat() / tallPortrait.first, vh.toFloat() / tallPortrait.second)
      assertEquals(contain, frame.start.scale, 1e-3f)
      assertTrue("whole width visible", tallPortrait.first * frame.start.scale <= vw + 1f)
      assertTrue("whole height visible", tallPortrait.second * frame.start.scale <= vh + 1f)
    }
  }

  @Test fun smartFramePlusFillStillCropsThatPortrait() {
    val (_, vh) = plus
    // The setting is the only lever: the same photo and face cover-crop under "fill".
    val frame = plusFrame(tallPortrait, ScreensaverConfig.FIT_FILL, centralFace)
    assertFalse("fill cover-crops", frame.whole)
    // The crop drops well over half the image (the backlog's ">=80% fill" complaint), which fit avoids.
    val shownFraction = vh / (tallPortrait.second * frame.start.scale)
    assertTrue("fill drops most of the photo ($shownFraction shown)", shownFraction < .5f)
  }

  @Test fun smartFramePlusFitShowsALandscapeWhole() {
    val frame = plusFrame(landscape32, ScreensaverConfig.FIT_FIT, centralFace)
    assertTrue(frame.whole)
    assertEquals(frame.start, frame.end)
  }

  // ---- Night photo ---------------------------------------------------------------------------

  @Test fun nightPhotoHoldsTheWholePhotoUnderFit() {
    for (mode in listOf(ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS)) {
      assertTrue(mode, look(mode, ScreensaverConfig.FIT_FIT, night = true).wholeAtNight)
      assertFalse(mode, look(mode, ScreensaverConfig.FIT_FILL, night = true).wholeAtNight)
    }
    // Upright Mini: a matching portrait was covered at night even with fit; now it is whole.
    val (w, h) = upright
    assertTrue(ScreensaverLayout.nightWhole(portrait23.first, portrait23.second, w, h, fit = true))
    assertTrue(ScreensaverLayout.nightWhole(portrait34.first, portrait34.second, w, h, fit = true))
    // Portal+: a matching landscape too.
    assertTrue(ScreensaverLayout.nightWhole(landscape32.first, landscape32.second, plus.first, plus.second, fit = true))
  }

  @Test fun nightPhotoUnderFillKeepsTheShippedRule() {
    val (w, h) = upright
    assertFalse(ScreensaverLayout.nightWhole(portrait23.first, portrait23.second, w, h, fit = false))
    assertTrue(ScreensaverLayout.nightWhole(landscape32.first, landscape32.second, w, h, fit = false))
    assertFalse(ScreensaverLayout.nightWhole(landscape32.first, landscape32.second, plus.first, plus.second, fit = false))
    // The default stays "fill", and styles that don't honour fit keep their Night photo as shipped.
    assertFalse(ScreensaverLayout.nightWhole(portrait23.first, portrait23.second, w, h))
    for (mode in listOf(ScreensaverConfig.MODE_WHOLE, ScreensaverConfig.MODE_SPLIT_GLANCE)) {
      assertFalse(mode, look(mode, ScreensaverConfig.FIT_FIT, night = true).wholeAtNight)
      assertFalse(mode, look(mode, ScreensaverConfig.FIT_FILL, night = true).wholeAtNight)
    }
  }

  // ---- Compact Classic -----------------------------------------------------------------------

  @Test fun compactClassicUnderFitHoldsThePhotoWholeAndStill() {
    val (w, h) = upright
    // Under fit there is no pose: the view rests FIT_CENTER, the whole photo letterboxed.
    assertNull(ScreensaverLayout.classicPan(portrait23.first, portrait23.second, w, h, 0, fill = false))
    assertNull(ScreensaverLayout.classicPan(landscape32.first, landscape32.second, w, h, 0, fill = false))
    assertNull(ScreensaverLayout.classicPan(portrait23.first, portrait23.second, h, w, 1, fill = false))
  }

  @Test fun compactClassicUnderFillKeepsThePanAndZoom() {
    val (w, h) = upright
    for (beat in 0..3) {
      assertEquals(ScreensaverLayout.classicPan(portrait23.first, portrait23.second, w, h, beat),
          ScreensaverLayout.classicPan(portrait23.first, portrait23.second, w, h, beat, fill = true))
    }
    assertNotNull(ScreensaverLayout.classicPan(landscape32.first, landscape32.second, w, h, 0, fill = true))
    assertTrue(ScreensaverLayout.classicPan(landscape32.first, landscape32.second, w, h, 0, fill = true)!!.pan)
  }
}
