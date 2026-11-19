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
  private val screens = listOf(1920f to 1280f, 1280f to 800f, 800f to 1172f, 1280f to 692f)
  /**
   * A few shorter heights too (status bar / window insets). 800 wide is the Portal Mini upright
   * (1220 dp less a header); 1280 down to 668 covers the Mini turned (740 dp less a header).
   */
  private val heights = mapOf(1920f to listOf(1280f, 1240f, 1200f), 1280f to listOf(800f, 770f, 740f, 716f, 692f, 668f),
      800f to listOf(1220f, 1196f, 1172f, 1148f, 1100f))

  private fun layouts() = heights.flatMap { (w, hs) -> hs.map { h -> MusicStageGeometry.layout(w, h) } }

  @Test fun picksTheLargeSpecOnlyForTheBigPortal() {
    assertSame(MusicStageGeometry.LARGE, MusicStageGeometry.spec(1920f))
    assertSame(MusicStageGeometry.COMPACT, MusicStageGeometry.spec(1280f))
    assertSame(MusicStageGeometry.COMPACT, MusicStageGeometry.spec(800f))
  }

  @Test fun stacksOnlyACompactStageTallerThanWide() {
    assertTrue(MusicStageGeometry.layout(800f, 1220f).stacked)
    assertTrue(MusicStageGeometry.layout(800f, 1172f).stacked)
    assertFalse(MusicStageGeometry.layout(1280f, 692f).stacked)
    assertFalse(MusicStageGeometry.layout(1280f, 800f).stacked)
    assertFalse(MusicStageGeometry.layout(1920f, 1219f).stacked)
    // A large spec is never stacked, whatever the size passed in.
    assertFalse(MusicStageGeometry.stacked(1200f, 1600f, MusicStageGeometry.LARGE))
  }

  @Test fun sideBySideKeepsItsOriginalColumnAndCards() {
    for ((w, h) in listOf(1920f to 1280f, 1920f to 1219f, 1280f to 800f, 1280f to 740f)) {
      val l = MusicStageGeometry.layout(w, h)
      assertEquals(l.spec.top, l.column.top, 0f)
      assertEquals(l.spec.edge, l.artRect.left, 0f)
      assertEquals(MusicRect(l.spec.edge, l.artTop, l.spec.edge + l.art, l.playerClosed.bottom), l.resumeCard)
      assertEquals(MusicRect(w - l.spec.edge - l.spec.accountsW, l.spec.top - 4f, w - l.spec.edge, l.sheetClosedTop),
          l.accountsMenu)
    }
  }

  @Test fun portraitStacksArtworkPlayerThenAFullWidthColumn() {
    for (h in listOf(1220f, 1172f, 1100f)) {
      val l = MusicStageGeometry.layout(800f, h)
      assertTrue("art above player", l.artRect.bottom <= l.playerClosed.top)
      assertTrue("column below player", l.column.top > l.playerClosed.bottom)
      assertEquals(l.spec.edge, l.column.left, 0f)
      assertEquals(800f - l.spec.edge, l.column.right, 0f)
      assertTrue("column too short at $h: ${l.column.height}", l.column.height >= 400f)
      // Centred artwork and player, both inside the screen edges.
      assertEquals(800f - l.artRect.right, l.artRect.left, 1f)
      assertEquals(800f - l.playerClosed.right, l.playerClosed.left, 1f)
      assertTrue(l.artRect.inside(l.screen))
      assertTrue(l.resumeCard.inside(l.screen))
      assertFalse(l.resumeCard.intersects(l.column))
    }
    assertEquals(329f, MusicStageGeometry.layout(800f, 1220f).art, 0.01f)
  }

  @Test fun noSizeIsNegativeOnAnyScreen() {
    for (l in layouts() + MusicStageGeometry.layout(800f, 900f) + MusicStageGeometry.layout(1000f, 600f)) {
      assertTrue(l.art > 0f)
      assertTrue(l.columnW >= 0f)
      assertTrue(l.column.height >= 0f)
      assertTrue(l.accountsMenu.width >= 0f && l.accountsMenu.height >= 0f)
      assertTrue("strip ${l.width}", l.width - l.spec.pad - l.stripTextX > 0f)
    }
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
