/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The YouTube player's rectangle must stay ≥ 200×200, on screen and clear of everything that can
 * draw (sheet, its menus, toasts, popovers) at every rest state, and the sheet edge may never reach
 * it during the spring, on both Portals.
 */
class MusicStageGeometryTest {
  private val screens = listOf(1920f to 1280f, 1280f to 800f)
  /** A few shorter heights too (status bar / window insets). */
  private val heights = mapOf(1920f to listOf(1280f, 1240f, 1200f), 1280f to listOf(800f, 770f, 740f))

  private fun layouts() = heights.flatMap { (w, hs) -> hs.map { h -> MusicStageGeometry.layout(w, h) } }

  @Test fun picksTheLargeSpecOnlyForTheBigPortal() {
    assertSame(MusicStageGeometry.LARGE, MusicStageGeometry.spec(1920f))
    assertSame(MusicStageGeometry.COMPACT, MusicStageGeometry.spec(1280f))
  }

  @Test fun matchesTheMockOnBothPortals() {
    val big = MusicStageGeometry.layout(1920f, 1280f)
    assertEquals(MusicRect(120f, 894f, 504f, 1110f), big.playerClosed)
    assertEquals(MusicRect(64f, 84f, 448f, 300f), big.playerOpen)
    assertEquals(84f, big.sheetOpenTop - big.playerOpen.bottom, 0.01f)
    assertEquals(42f, big.sheetClosedTop - big.playerClosed.bottom, 0.01f)
    val small = MusicStageGeometry.layout(1280f, 800f)
    assertEquals(17f, small.sheetOpenTop - small.playerOpen.bottom, 0.01f)
    // The mock rounds the 16.5 dp centring offset up too, leaving 16 dp below the player.
    assertEquals(16f, small.sheetClosedTop - small.playerClosed.bottom, 0.01f)
  }

  @Test fun playerIsAtLeastTheYouTubeMinimumAndOnScreenInEveryRestState() {
    for (l in layouts()) {
      for (r in listOf(l.playerClosed, l.playerOpen, l.playerSearch)) {
        assertTrue("$r too small", r.width >= MusicStageGeometry.MIN_PLAYER && r.height >= MusicStageGeometry.MIN_PLAYER)
        assertTrue("$r off screen", r.inside(l.screen))
      }
    }
  }

  @Test fun sheetAndItsMenusNeverTouchThePlayerAtRest() {
    for (l in layouts()) {
      for ((p, f) in listOf(0f to 0f, 1f to 0f, 1f to 1f)) {
        val frame = MusicStageGeometry.frame(l, p, f)
        assertTrue("gap at p=$p f=$f ${l.width}x${l.height}", frame.gap > 0f)
        assertFalse(l.sheet(frame.sheetTop).intersects(frame.player))
      }
    }
  }

  @Test fun sheetEdgeNeverReachesThePlayerDuringTheSpringIncludingOvershoot() {
    for (l in layouts()) {
      for (focus in listOf(0f, 0.5f, 1f)) {
        var p = -MusicStageGeometry.OVERSHOOT_PROGRESS - 0.1f
        while (p <= 1.2f) {
          val frame = MusicStageGeometry.frame(l, p, focus)
          assertTrue("gap ${frame.gap} at p=$p f=$focus", frame.gap > 0f)
          assertTrue(frame.player.inside(l.screen))
          p += 0.01f
        }
      }
    }
  }

  @Test fun toastsNeverTouchThePlayer() {
    for (l in layouts()) {
      val cases = listOf(Triple(0f, 0f, l.toast(open = false, searching = false)),
          Triple(1f, 0f, l.toast(open = true, searching = false)),
          Triple(1f, 1f, l.toast(open = true, searching = true)))
      for ((p, f, toast) in cases) {
        val player = MusicStageGeometry.frame(l, p, f).player
        assertFalse("toast $toast over player $player", toast.intersects(player))
        assertTrue(toast.inside(l.screen))
        assertTrue(toast.width > 0f)
      }
    }
  }

  @Test fun closedPopoversAndCaptionStayClearOfThePlayer() {
    for (l in layouts()) {
      val player = l.playerClosed
      assertFalse("column", l.column.intersects(player))
      assertFalse("accounts", l.accountsMenu.intersects(player))
      assertFalse("artwork", l.artRect.intersects(player))
      l.playerCaption?.let { assertFalse("caption", it.intersects(player)) }
      assertTrue(l.columnW > 0f)
    }
  }

  @Test fun stripTextSitsRightOfTheOpenPlayer() {
    for (l in layouts()) {
      assertTrue(l.stripTextX > l.playerOpen.right)
      assertTrue(l.stripTextX > l.playerSearch.right)
    }
  }

  @Test fun searchFocusSqueezesTheStripOnBothPortals() {
    for ((w, h) in screens) {
      val l = MusicStageGeometry.layout(w, h)
      assertTrue(l.sheetSearchTop < l.sheetOpenTop)
      assertTrue(l.sheetHeight >= l.height - l.sheetSearchTop)
    }
  }

  @Test fun fadesHandOverAroundTheMiddle() {
    val l = MusicStageGeometry.layout(1920f, 1280f)
    val closed = MusicStageGeometry.frame(l, 0f, 0f)
    val open = MusicStageGeometry.frame(l, 1f, 0f)
    assertEquals(1f, closed.nowAlpha); assertEquals(0f, closed.stripAlpha); assertEquals(1f, closed.peekAlpha)
    assertEquals(0f, open.nowAlpha); assertEquals(1f, open.stripAlpha); assertEquals(1f, open.tabsAlpha)
    assertEquals(0f, open.peekAlpha)
  }

  @Test fun everyControlMeetsTheTouchFloor() {
    for (d in listOf(MusicStageDims.LARGE, MusicStageDims.COMPACT)) {
      d.targets.forEach { assertTrue("$it < 64", it >= MusicStageGeometry.MIN_TARGET) }
    }
    assertTrue(MusicStageDims.LARGE.target >= 80f)
  }

  @Test fun rectIntersectionIgnoresTouchingEdges() {
    val a = MusicRect(0f, 0f, 10f, 10f)
    assertFalse(a.intersects(MusicRect(10f, 0f, 20f, 10f)))
    assertTrue(a.intersects(MusicRect(9f, 9f, 20f, 20f)))
  }
}
