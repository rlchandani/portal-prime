/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSheetMachineTest {
  private val closed = MusicSheetState()

  @Test fun idleOpensOnLibraryAndPlayingOnUpNext() {
    assertEquals(MusicSheetTab.LIBRARY, MusicSheetMachine.initial(idle = true).tab)
    assertEquals(MusicSheetTab.UP_NEXT, MusicSheetMachine.initial(idle = false).tab)
  }

  @Test fun openingClosesPopoversAndSetsTheSpringTarget() {
    val withPop = MusicSheetMachine.toggle(closed, MusicPopover.SLEEP)
    assertEquals(MusicPopover.SLEEP, withPop.popover)
    val open = MusicSheetMachine.open(withPop, MusicSheetTab.SEARCH)
    assertTrue(open.open)
    assertEquals(MusicPopover.NONE, open.popover)
    assertEquals(MusicSheetTab.SEARCH, open.tab)
    assertEquals(1f, open.openTarget)
    assertEquals(0f, closed.openTarget)
  }

  @Test fun popoversDoNotOpenOverAnOpenSheet() {
    val open = MusicSheetMachine.open(closed)
    assertEquals(open, MusicSheetMachine.toggle(open, MusicPopover.MOVE))
  }

  @Test fun togglingThePopoverTwiceClosesIt() {
    val once = MusicSheetMachine.toggle(closed, MusicPopover.MOVE)
    assertEquals(MusicPopover.NONE, MusicSheetMachine.toggle(once, MusicPopover.MOVE).popover)
    assertEquals(MusicPopover.ACCOUNTS, MusicSheetMachine.toggle(once, MusicPopover.ACCOUNTS).popover)
  }

  @Test fun searchFocusSqueezesOnlyWhileOpenOnSearch() {
    val search = MusicSheetMachine.open(closed, MusicSheetTab.SEARCH)
    val focused = MusicSheetMachine.focusSearch(search, true)
    assertEquals(1f, focused.focusTarget)
    assertFalse(MusicSheetMachine.focusSearch(closed, true).searchFocused)
    assertFalse(MusicSheetMachine.selectTab(focused, MusicSheetTab.LIBRARY).searchFocused)
    val closedAgain = MusicSheetMachine.close(focused)
    assertFalse(closedAgain.searchFocused)
    assertEquals(0f, closedAgain.focusTarget)
  }

  @Test fun backClosesThePopoverThenTheSheetThenLetsGo() {
    val pop = MusicSheetMachine.toggle(closed, MusicPopover.ACCOUNTS)
    assertEquals(MusicPopover.NONE, MusicSheetMachine.back(pop)?.popover)
    val open = MusicSheetMachine.open(closed)
    assertEquals(false, MusicSheetMachine.back(open)?.open)
    assertNull(MusicSheetMachine.back(closed))
  }

  @Test fun loadingSwitchesTheClosedSheetsTabButNeverAnOpenOne() {
    val idle = MusicSheetMachine.initial(idle = true)
    assertEquals(MusicSheetTab.UP_NEXT, MusicSheetMachine.onLoadedChanged(idle, idle = false).tab)
    val open = MusicSheetMachine.open(idle)
    assertEquals(open, MusicSheetMachine.onLoadedChanged(open, idle = false))
    val playing = MusicSheetMachine.initial(idle = false)
    assertEquals(MusicSheetTab.LIBRARY, MusicSheetMachine.onLoadedChanged(playing, idle = true).tab)
  }

  @Test fun dragFollowsTheFingerAndRubberBandsPastTheEnds() {
    assertEquals(0.5f, MusicSheetDrag.progress(0f, -400f, 800f), 1e-4f)
    assertEquals(0.25f, MusicSheetDrag.progress(0.5f, 200f, 800f), 1e-4f)
    assertEquals(1f + 0.5f * MusicSheetDrag.RUBBER, MusicSheetDrag.progress(1f, -400f, 800f), 1e-4f)
    assertEquals(-0.5f * MusicSheetDrag.RUBBER, MusicSheetDrag.progress(0f, 400f, 800f), 1e-4f)
    assertEquals(0.3f, MusicSheetDrag.progress(0.3f, 100f, 0f), 1e-4f)
  }

  @Test fun velocityIsInProgressUnitsAndClamped() {
    assertEquals(1f, MusicSheetDrag.velocity(-800f, 800f), 1e-4f)
    assertEquals(-MusicSheetDrag.MAX_VELOCITY, MusicSheetDrag.velocity(1e6f, 800f), 1e-4f)
    assertEquals(0f, MusicSheetDrag.velocity(100f, 0f), 1e-4f)
  }

  @Test fun releaseFlicksByDirectionElseByPosition() {
    assertTrue(MusicSheetDrag.settlesOpen(0.1f, 2f))
    assertFalse(MusicSheetDrag.settlesOpen(0.9f, -2f))
    assertTrue(MusicSheetDrag.settlesOpen(0.6f, 0.5f))
    assertFalse(MusicSheetDrag.settlesOpen(0.4f, -0.5f))
  }

  @Test fun springIsTheMocksDampedSpring() {
    assertEquals(0.8f, MusicSheetDrag.SPRING_DAMPING)
    assertEquals(170f, MusicSheetDrag.SPRING_STIFFNESS)
    // Damping 0.8 overshoots by exp(-ζπ/√(1-ζ²)) ≈ 1.5 %, inside the geometry's 5 % clamp.
    val zeta = MusicSheetDrag.SPRING_DAMPING.toDouble()
    val overshoot = Math.exp(-zeta * Math.PI / Math.sqrt(1 - zeta * zeta))
    assertTrue(overshoot < MusicStageGeometry.OVERSHOOT_PROGRESS)
  }
}
