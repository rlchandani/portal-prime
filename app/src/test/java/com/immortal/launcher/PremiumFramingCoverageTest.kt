/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SlideshowGeometry.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart-frame edge cases: degenerate sizes, corrupt face metadata, faces that can't fit, still frames. */
class PremiumFramingCoverageTest {
  private val face = Region(.4f, .3f, .55f, .5f)

  private fun plan(
      iw: Int = 3000, ih: Int = 2000, vw: Int = 2160, vh: Int = 1440,
      regions: List<Region>? = listOf(face), keepPeople: Boolean = true, fill: Boolean = true,
      motion: Boolean = true, focus: PremiumFraming.Focus? = null,
  ) = PremiumFraming.plan(iw, ih, vw, vh, regions, keepPeople, fill, motion, focus)

  @Test fun anyNonPositiveSizeGivesAnEmptyWholeFrame() {
    listOf(
        plan(iw = 0), plan(ih = 0), plan(vw = 0), plan(vh = 0), plan(iw = -1), plan(vh = -10),
    ).forEach { frame ->
      assertTrue(frame.contained)
      assertTrue(frame.motion.whole)
      assertEquals(0f, frame.motion.start.scale, 0f)
      assertEquals(frame.motion.start, frame.motion.end)
    }
  }

  @Test fun mismatchIsSymmetricAndMaximalForDegenerateSizes() {
    assertEquals(1.0, PremiumFraming.mismatch(1500, 1000, 3000, 2000), 1e-9)
    assertEquals(PremiumFraming.mismatch(1000, 1500, 1500, 1000), PremiumFraming.mismatch(1500, 1000, 1000, 1500), 1e-9)
    assertEquals(2.25, PremiumFraming.mismatch(1000, 1500, 1500, 1000), 1e-9)
    assertEquals(Double.MAX_VALUE, PremiumFraming.mismatch(0, 1, 1, 1), 0.0)
    assertEquals(Double.MAX_VALUE, PremiumFraming.mismatch(1, 0, 1, 1), 0.0)
    assertEquals(Double.MAX_VALUE, PremiumFraming.mismatch(1, 1, 0, 1), 0.0)
    assertEquals(Double.MAX_VALUE, PremiumFraming.mismatch(1, 1, 1, -1), 0.0)
  }

  @Test fun corruptFaceBoxesShowThePhotoWhole() {
    val corrupt = listOf(
        Region(Float.NaN, .1f, .2f, .3f), Region(.1f, Float.NaN, .2f, .3f),
        Region(.1f, .1f, Float.POSITIVE_INFINITY, .3f), Region(.1f, .1f, .2f, Float.NEGATIVE_INFINITY),
        Region(-.1f, .1f, .2f, .3f), Region(.1f, -.1f, .2f, .3f),
        Region(.1f, .1f, 1.1f, .3f), Region(.1f, .1f, .2f, 1.1f),
        Region(.3f, .1f, .2f, .3f), Region(.1f, .3f, .2f, .2f), Region(.2f, .2f, .2f, .4f),
    )
    corrupt.forEach { bad ->
      val frame = plan(regions = listOf(face, bad))
      assertTrue("$bad", frame.contained)
      assertTrue("$bad", frame.motion.whole)
    }
  }

  @Test fun tooManyFaceBoxesAreTreatedAsCorrupt() {
    val many = List(257) { face }
    assertTrue(plan(regions = many).contained)
    assertFalse(plan(regions = List(256) { face }).contained)
  }

  @Test fun corruptFacesAreIgnoredWhenPeopleNeedNotBeKept() {
    val frame = plan(regions = listOf(Region(Float.NaN, 0f, 1f, 1f)), keepPeople = false)
    assertFalse(frame.contained)
  }

  @Test fun facesThatCannotFitTheFrameShowThePhotoWhole() {
    // A face spanning almost the whole height: the cover crop of a wide photo would cut it off.
    val tall = Region(.45f, 0f, .55f, 1f)
    assertTrue(plan(iw = 4000, ih = 2000, vw = 2160, vh = 1440, regions = listOf(tall, Region(0f, .4f, 1f, .6f))).contained)
  }

  @Test fun fitModeAndAspectMismatchAlwaysShowTheWholePhoto() {
    assertTrue(plan(fill = false).contained)
    assertTrue(plan(iw = 1000, ih = 3000).contained)
    assertFalse(plan(iw = 1000, ih = 3000).let { it.motion.start == it.motion.end })
  }

  @Test fun stillContainedFrameDoesNotMove() {
    val frame = plan(fill = false, motion = false)
    assertEquals(frame.motion.start, frame.motion.end)
    assertEquals(PremiumFraming.INSET.toDouble(),
        frame.motion.start.scale / minOf(2160.0 / 3000, 1440.0 / 2000), 1e-4)
  }

  @Test fun stillCoverFrameCoversTheViewportWithoutMoving() {
    val frame = plan(motion = false, regions = emptyList(), focus = PremiumFraming.Focus(.9f, .1f))
    assertFalse(frame.contained)
    assertFalse(frame.motion.whole)
    assertEquals(frame.motion.start, frame.motion.end)
    val t = frame.motion.start
    assertTrue(t.x <= 1e-3f && t.y <= 1e-3f)
    assertTrue(t.x + 3000 * t.scale >= 2160 - 1e-2f)
    assertTrue(t.y + 2000 * t.scale >= 1440 - 1e-2f)
  }

  @Test fun innerRectIsTheSmallerEndOfThePath() {
    val frame = plan(regions = emptyList())
    val small = if (frame.motion.start.scale <= frame.motion.end.scale) frame.motion.start else frame.motion.end
    val rect = frame.innerRect(3000, 2000)
    assertEquals(small.x, rect[0], 0f)
    assertEquals(small.y + 2000 * small.scale, rect[3], 1e-3f)
  }

  @Test fun regionFocusIgnoresTinyOrEmptyInput() {
    assertNull(PremiumFraming.regionFocus(emptyList()))
    assertNull(PremiumFraming.regionFocus(listOf(Region(.5f, .5f, .5001f, .5001f))))
    val focus = PremiumFraming.regionFocus(listOf(face))!!
    assertTrue(focus.x in 0.4f..0.55f)
    assertTrue(focus.y in 0.3f..0.5f)
  }
}
