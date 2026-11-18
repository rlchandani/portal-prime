/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicFloatMathTest {
  // 1000×600 content area with 20 side, 10 top and 80 bottom margins; a 200×100 card.
  private val area = MusicFloatArea(1000f, 600f, start = 20f, top = 10f, end = 20f, bottom = 80f)
  private val card = MusicFloatSize(200f, 100f)
  private val maxX = 1000f - 20f - 200f
  private val maxY = 600f - 80f - 100f

  @Test fun boundsKeepTheCardOffTheHeaderTheFavouritesAndTheDock() {
    assertEquals(20f, area.minX)
    assertEquals(10f, area.minY)
    assertEquals(maxX, area.maxX(card))
    assertEquals(maxY, area.maxY(card))
  }

  @Test fun clampPullsAnOutsidePositionInside() {
    assertEquals(MusicFloatOffset(20f, 10f), MusicFloatMath.clamp(MusicFloatOffset(-50f, -50f), card, area))
    assertEquals(MusicFloatOffset(maxX, maxY), MusicFloatMath.clamp(MusicFloatOffset(5000f, 5000f), card, area))
    assertEquals(MusicFloatOffset(300f, 200f), MusicFloatMath.clamp(MusicFloatOffset(300f, 200f), card, area))
  }

  @Test fun dragFollowsTheFingerWithALimitedRubberEdge() {
    val start = MusicFloatOffset(300f, 200f)
    assertEquals(MusicFloatOffset(340f, 170f),
        MusicFloatMath.drag(start, MusicFloatOffset(40f, -30f), card, area, overshoot = 12f))
    assertEquals(MusicFloatOffset(20f - 12f, 10f - 12f),
        MusicFloatMath.drag(start, MusicFloatOffset(-900f, -900f), card, area, overshoot = 12f))
    assertEquals(MusicFloatOffset(maxX + 12f, maxY + 12f),
        MusicFloatMath.drag(start, MusicFloatOffset(900f, 900f), card, area, overshoot = 12f))
  }

  @Test fun aNegativeOvershootIsTreatedAsNone() {
    assertEquals(MusicFloatOffset(20f, 10f),
        MusicFloatMath.drag(MusicFloatOffset(30f, 30f), MusicFloatOffset(-100f, -100f), card, area, overshoot = -5f))
  }

  @Test fun releaseSettlesInsideAndSnapsToANearEdge() {
    assertEquals(MusicFloatOffset(20f, 10f),
        MusicFloatMath.settle(MusicFloatOffset(8f, -2f), card, area, snap = 32f))
    assertEquals(MusicFloatOffset(maxX, 200f),
        MusicFloatMath.settle(MusicFloatOffset(maxX - 20f, 200f), card, area, snap = 32f))
    assertEquals(MusicFloatOffset(20f, maxY),
        MusicFloatMath.settle(MusicFloatOffset(45f, maxY + 9f), card, area, snap = 32f))
    assertEquals(MusicFloatOffset(400f, 200f),
        MusicFloatMath.settle(MusicFloatOffset(400f, 200f), card, area, snap = 32f))
  }

  @Test fun aCardBiggerThanTheAreaPinsToTheTopStart() {
    val huge = MusicFloatSize(2000f, 2000f)
    assertEquals(MusicFloatOffset(20f, 10f), MusicFloatMath.settle(MusicFloatOffset(500f, 500f), huge, area, 32f))
    assertEquals(MusicFloatPosition(0f, 0f), MusicFloatMath.toPosition(MusicFloatOffset(500f, 500f), huge, area))
  }

  @Test fun positionFractionsRoundTripAndSurviveAnotherSizeOrArea() {
    val o = MusicFloatOffset(400f, 200f)
    val p = MusicFloatMath.toPosition(o, card, area)
    val back = MusicFloatMath.toOffset(p, card, area)
    assertEquals(o.x, back.x, 0.01f)
    assertEquals(o.y, back.y, 0.01f)
    // The default (bottom-right) lands in the bottom-right corner whatever the size.
    val tile = MusicFloatSize(120f, 90f)
    assertEquals(MusicFloatOffset(area.maxX(tile), area.maxY(tile)),
        MusicFloatMath.toOffset(MusicFloatPosition.DEFAULT, tile, area))
    // Out-of-range fractions still land inside.
    assertEquals(MusicFloatOffset(20f, maxY), MusicFloatMath.toOffset(MusicFloatPosition(-1f, 3f), card, area))
  }
}
