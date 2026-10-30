/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedHomeTabAnimationTest {
  private val home = ApprovedLauncherTab.HOME
  private val apps = ApprovedLauncherTab.APPS
  private val tools = ApprovedLauncherTab.TOOLS

  @Test fun oneClockMovesIncomingAndOutgoingTogetherInBothDirections() {
    val transition = ApprovedHomeTabTransition(home)
    val outward = ApprovedHomeTabAnimation(requireNotNull(transition.select(apps)), 0f)
    assertEquals(-.25f, outward.offset(home, .25f)!!, 0f)
    assertEquals(.75f, outward.offset(apps, .25f)!!, 0f)
    assertTrue(transition.finish(outward.plan))

    val returning = ApprovedHomeTabAnimation(requireNotNull(transition.select(home)), 1f)
    assertEquals(-.75f, returning.offset(home, .75f)!!, 0f)
    assertEquals(.25f, returning.offset(apps, .75f)!!, 0f)
    assertEquals(1f, returning.progress(0f), 0f)
  }

  @Test fun interruptedReversalStartsAtBothDisplayedPositionsWithoutResettingClock() {
    val transition = ApprovedHomeTabTransition(home)
    val first = ApprovedHomeTabAnimation(requireNotNull(transition.select(apps)), 0f)
    val clock = .4f
    assertTrue(transition.advance(first.plan, first.progress(clock)))
    val reversal = ApprovedHomeTabAnimation(requireNotNull(transition.select(home)), clock)
    assertEquals(first.offset(home, clock)!!, reversal.offset(home, clock)!!, 0f)
    assertEquals(first.offset(apps, clock)!!, reversal.offset(apps, clock)!!, 0f)
    val halfway = (clock + reversal.target) / 2f
    assertEquals(-.2f, reversal.offset(home, halfway)!!, .00001f)
    assertEquals(.8f, reversal.offset(apps, halfway)!!, .00001f)
    assertTrue(transition.finish(reversal.plan))
    assertEquals(setOf(home), transition.visible)
  }

  @Test fun repeatedThreePageInterruptionsKeepPositionsAndRejectOldCompletion() {
    val transition = ApprovedHomeTabTransition(home)
    var clock = 0f
    var current: ApprovedHomeTabAnimation? = null
    for (tab in listOf(apps, tools, home, tools, apps, home)) {
      current?.let { assertTrue(transition.advance(it.plan, it.progress(clock))) }
      val before = transition.visible.associateWith { transition.offset(it) }
      val next = ApprovedHomeTabAnimation(requireNotNull(transition.select(tab)), clock)
      before.forEach { (page, position) ->
        assertEquals(position, next.offset(page, clock)!!, .00001f)
      }
      current?.let { assertFalse(transition.finish(it.plan)) }
      clock += (next.target - clock) * .37f
      current = next
    }
    assertTrue(transition.finish(requireNotNull(current).plan))
    assertEquals(setOf(home), transition.visible)
    assertEquals(0f, transition.offset(home), 0f)
  }

  @Test fun aPreviouslyVisitedHiddenPageDoesNotJoinTheNewAnimation() {
    val transition = ApprovedHomeTabTransition(home)
    assertTrue(transition.finish(requireNotNull(transition.select(tools))))
    assertTrue(transition.finish(requireNotNull(transition.select(home))))
    val next = ApprovedHomeTabAnimation(requireNotNull(transition.select(apps)), 0f)
    assertNull(next.offset(tools, .5f))
    assertEquals(setOf(home, apps), transition.visible)
  }

  @Test fun arbitraryInterruptedClockValuesHaveAWellDefinedFullSlide() {
    for (start in listOf(0f, .1f, .49f, .5f, .75f, 1f)) {
      val transition = ApprovedHomeTabTransition(home)
      val motion = ApprovedHomeTabAnimation(requireNotNull(transition.select(apps)), start)
      assertTrue(kotlin.math.abs(motion.target - start) >= .5f)
      assertEquals(0f, motion.progress(start), 0f)
      assertEquals(.5f, motion.progress((start + motion.target) / 2f), .00001f)
      assertEquals(1f, motion.progress(motion.target), 0f)
      assertEquals(0f, motion.offset(apps, motion.target)!!, 0f)
      assertEquals(-1f, motion.offset(home, motion.target)!!, 0f)
    }
  }
}
