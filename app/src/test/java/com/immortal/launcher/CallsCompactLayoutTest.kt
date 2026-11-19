/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsCompactLayoutTest {
  private val portrait = LayoutClass.of(800, 1220)
  private val landscape = LayoutClass.of(1280, 740)

  @Test fun theMiniClassifiesBothWays() {
    assertEquals(LayoutClass.COMPACT_PORTRAIT, portrait)
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, landscape)
  }

  @Test fun largeValuesAreThePortalPlusDesign() {
    val large = LayoutClass.LARGE
    assertEquals(CallsCompactLayout.Box(420, 280), CallsCompactLayout.selfView(large))
    assertEquals(40, CallsCompactLayout.edge(large))
    val c = CallsCompactLayout.controls(large)
    assertEquals(96, c.circle); assertEquals(134, c.column); assertEquals(150, c.endWidth); assertEquals(170, c.endColumn)
    assertEquals(36, c.bottom)
    val r = CallsCompactLayout.ring(large)
    assertEquals(260, r.outgoingAvatar); assertEquals(280, r.incomingAvatar); assertEquals(176, r.big); assertEquals(110, r.toggle)
    assertEquals(72f, r.outgoingTitle, 0f); assertEquals(84f, r.incomingTitle, 0f); assertEquals(60f, r.endedTitle, 0f)
    assertEquals(120, r.buttonsGap)
    assertEquals(260, CallsCompactLayout.remoteAvatar(large))
    assertEquals(360, CallsCompactLayout.connectionChipOffset(large))
    assertEquals(2, CallsCompactLayout.roomColumns(large))
    assertEquals(56, CallsCompactLayout.pagePadding(large))
  }

  @Test fun theUprightSelfViewIsPortraitNineBySixteen() {
    val v = CallsCompactLayout.selfView(portrait)
    assertTrue(v.heightDp > v.widthDp)
    assertEquals(9f / 16f, v.widthDp.toFloat() / v.heightDp, 0.01f)
    assertTrue(v.widthDp in 200..240)
  }

  @Test fun theTurnedSelfViewKeepsTheLandscapeShape() {
    val v = CallsCompactLayout.selfView(landscape)
    assertEquals(3f / 2f, v.widthDp.toFloat() / v.heightDp, 0.01f)
  }

  @Test fun theControlsFitTheUprightMiniComfortably() {
    val c = CallsCompactLayout.controls(portrait)
    assertTrue("width ${c.width}", c.width + 2 * 48 <= 800)
    assertTrue("touch floor", c.circle >= 64 && c.endHeight >= 64)
  }

  @Test fun theLargeControlsWouldNotHaveFit() {
    assertTrue(CallsCompactLayout.controls(LayoutClass.LARGE).width > 800 - 2 * 24)
  }

  @Test fun theTurnedLiveScreenStacksWithoutOverlapIn740() {
    val height = 740
    val edge = CallsCompactLayout.edge(landscape)
    val c = CallsCompactLayout.controls(landscape)
    val controlsTop = height - c.bottom - c.height
    val self = CallsCompactLayout.selfView(landscape)
    // Self view (under a drop-in bar of 110) ends above the controls.
    assertTrue(110 + edge + self.heightDp < controlsTop)
    // The connection chip (64 tall, offset below centre) ends above the controls.
    val chipTop = height / 2 - (CallsCompactLayout.connectionChipOffset(landscape) + 64) / 2 + CallsCompactLayout.connectionChipOffset(landscape)
    assertTrue(chipTop + 64 <= controlsTop)
    // The remote avatar ends above the chip.
    assertTrue(height / 2 + CallsCompactLayout.remoteAvatar(landscape) / 2 < chipTop)
  }

  @Test fun theTurnedRingScreenFitsIn740() {
    val r = CallsCompactLayout.ring(landscape)
    val edge = CallsCompactLayout.edge(landscape)
    // Outgoing column: live bar 72, avatar, gap, title + sub, gap, chips 64, buttons (big + label).
    val title = (r.outgoingTitle * LINE).toInt() + HEADLINE_GAP + (r.sub * LINE).toInt()
    val buttons = r.big + BIG_LABEL
    val total = 2 * edge + 72 + r.outgoingAvatar + r.gap + title + r.gap + 64 + buttons
    assertTrue("total $total", total <= 740)
    // Incoming, privacy chip on top: chips 64, avatar, gap, title + sub, buttons, gap 20, audio-only 72.
    val inTitle = (r.incomingTitle * LINE).toInt() + HEADLINE_GAP + (r.sub * LINE).toInt()
    val incoming = 2 * edge + 64 + r.incomingAvatar + r.gap + inTitle + buttons + 20 + 72
    assertTrue("incoming $incoming", incoming <= 740)
  }

  @Test fun theRoomsGoOneColumnUpright() {
    assertEquals(1, CallsCompactLayout.roomColumns(portrait))
    assertEquals(2, CallsCompactLayout.roomColumns(landscape))
  }

  private companion object {
    /** A line is about 1.2× the font size. */
    const val LINE = 1.2f
    const val HEADLINE_GAP = 14
    const val BIG_LABEL = 18 + 34
  }
}
