/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedHomeTabTransitionTest {
  private val home = ApprovedLauncherTab.HOME
  private val apps = ApprovedLauncherTab.APPS
  private val tools = ApprovedLauncherTab.TOOLS

  @Test fun reversalContinuesFromBothDisplayedPositions() {
    val transition = ApprovedHomeTabTransition(home)
    val outward = requireNotNull(transition.select(apps))
    transition.advance(outward, .25f)
    assertEquals(-.25f, transition.offset(home), 0f)
    assertEquals(.75f, transition.offset(apps), 0f)

    val returning = requireNotNull(transition.select(home))
    assertEquals(-.25f, transition.offset(home), 0f)
    assertEquals(.75f, transition.offset(apps), 0f)
    transition.advance(returning, .5f)
    assertEquals(-.125f, transition.offset(home), 0f)
    assertEquals(.875f, transition.offset(apps), 0f)
    assertEquals(setOf(home, apps), transition.visible)
    assertTrue(transition.finish(returning))
    assertEquals(setOf(home), transition.visible)
    assertEquals(0f, transition.offset(home), 0f)
  }

  @Test fun cancelledFramesAndCompletionCannotMoveOrHideTheReplacement() {
    val transition = ApprovedHomeTabTransition(home)
    val first = requireNotNull(transition.select(apps))
    transition.advance(first, .5f)
    val replacement = requireNotNull(transition.select(tools))
    transition.advance(replacement, .5f)

    assertFalse(transition.advance(first, 1f))
    assertFalse(transition.finish(first))
    assertEquals(tools, transition.selected)
    assertEquals(setOf(home, apps, tools), transition.visible)
    assertEquals(-.75f, transition.offset(home), 0f)
    assertEquals(-.25f, transition.offset(apps), 0f)
    assertEquals(.5f, transition.offset(tools), 0f)
    assertTrue(transition.finish(replacement))
    assertEquals(setOf(tools), transition.visible)
  }

  @Test fun sameTabRefreshDoesNotRestartOrLoseTheRunningAnimation() {
    val transition = ApprovedHomeTabTransition(home)
    val running = requireNotNull(transition.select(apps))
    transition.advance(running, .5f)
    assertNull(transition.select(apps))
    assertEquals(.5f, transition.offset(apps), 0f)
    assertTrue(transition.finish(running))
    assertEquals(setOf(apps), transition.visible)
    assertNull(transition.select(apps))
  }

  @Test fun settledHiddenPageReentersFromTheRequestedSide() {
    val transition = ApprovedHomeTabTransition(tools)
    val back = requireNotNull(transition.select(home))
    assertEquals(-1f, transition.offset(home), 0f)
    assertTrue(transition.finish(back))
    assertEquals(1f, transition.offset(tools), 0f)

    val middle = requireNotNull(transition.select(apps))
    assertEquals(1f, transition.offset(apps), 0f)
    assertTrue(transition.finish(middle))
    val forward = requireNotNull(transition.select(tools))
    assertEquals(1f, transition.offset(tools), 0f)
    transition.advance(forward, .5f)
    assertEquals(-.5f, transition.offset(apps), 0f)
    assertEquals(.5f, transition.offset(tools), 0f)
  }

  @Test fun settlingInvalidatesAnUnfinishedAnimationBeforeHostRelease() {
    val transition = ApprovedHomeTabTransition(home)
    val running = requireNotNull(transition.select(apps))
    transition.advance(running, .5f)
    transition.settle()
    assertEquals(setOf(apps), transition.visible)
    assertEquals(0f, transition.offset(apps), 0f)
    assertFalse(transition.advance(running, .75f))
    assertFalse(transition.finish(running))
  }

  @Test fun oldCompletionCannotEndANewTransitionToTheSameTab() {
    val transition = ApprovedHomeTabTransition(home)
    val old = requireNotNull(transition.select(apps))
    assertTrue(transition.finish(old))
    assertTrue(transition.finish(requireNotNull(transition.select(home))))
    val current = requireNotNull(transition.select(apps))
    transition.advance(current, .25f)

    assertFalse(transition.finish(old))
    assertFalse(transition.advance(old, 1f))
    assertEquals(.75f, transition.offset(apps), 0f)
    assertEquals(setOf(home, apps), transition.visible)
    assertTrue(transition.finish(current))
  }

  @Test fun hiddenPositionFromAnEarlierExitCannotChooseTheNextEntranceEdge() {
    val transition = ApprovedHomeTabTransition(home)
    assertTrue(transition.finish(requireNotNull(transition.select(apps))))
    assertTrue(transition.finish(requireNotNull(transition.select(tools))))
    assertEquals(-1f, transition.offset(apps), 0f)
    assertTrue(transition.finish(requireNotNull(transition.select(home))))

    val next = requireNotNull(transition.select(apps))
    assertEquals(1f, transition.offset(apps), 0f)
    transition.advance(next, .5f)
    assertEquals(.5f, transition.offset(apps), 0f)
    assertEquals(-.5f, transition.offset(home), 0f)
  }
}
