/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class NightBrightnessFadeTest {
  private class Fixture(prior: Float = -1f, var system: Float = .8f) {
    var now = 0L
    var brightness = prior
    val writes = mutableListOf<Float>()
    val pending = linkedMapOf<Runnable, Long>()
    var callbacks = 0
    val fade = NightBrightnessFade(
        priorBrightness = prior,
        readBrightness = { brightness },
        readSystemBrightness = { system },
        writeBrightness = { brightness = it; writes.add(it) },
        nowMs = { now },
        postDelayed = { task, delay -> pending[task] = now + delay },
        removeCallbacks = { pending.remove(it) })

    fun advance(ms: Long) {
      val end = now + ms
      while (pending.values.minOrNull()?.let { it <= end } == true) {
        val next = pending.minBy { it.value }
        now = next.value
        pending.remove(next.key)
        callbacks++
        next.key.run()
      }
      now = end
    }
  }

  @Test fun fadesFromSystemBrightnessWithoutInterpolatingTheDefaultSentinel() {
    val f = Fixture()
    f.fade.setActive(true)
    assertTrue(f.pending.isEmpty())
    f.fade.setNight(true, .03f)
    assertEquals(-1f, f.brightness, 0f)
    f.advance(1500)
    assertTrue(f.brightness in .26f.. .29f)
    f.advance(1500)
    assertEquals(.03f, f.brightness, 0f)
    assertTrue(f.writes.size in 80..91)
    assertTrue(f.writes.all { it in .03f.. .8f })
    assertTrue(f.writes.zipWithNext().all { (a, b) -> b <= a })
    assertTrue(f.pending.isEmpty())
  }

  @Test fun restoresDefaultOnlyAfterGraduallyReturningToSystemLevel() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(3000)
    f.writes.clear()
    f.system = .6f
    f.fade.setNight(false, .03f)
    f.advance(1500)
    assertTrue(f.brightness in .20f.. .23f)
    f.advance(1500)
    assertEquals(-1f, f.brightness, 0f)
    assertTrue(f.writes.dropLast(1).all { it in .03f.. .6f })
    assertTrue(f.writes.dropLast(1).zipWithNext().all { (a, b) -> b >= a })
    assertTrue(f.pending.isEmpty())
  }

  @Test fun restoresAnExplicitPriorOverrideExactly() {
    val f = Fixture(prior = .45f, system = .8f)
    f.fade.setActive(true)
    f.fade.setNight(true, .01f)
    f.advance(3000)
    f.fade.setNight(false, .01f)
    f.advance(1500)
    assertTrue(f.brightness in .13f.. .15f)
    f.advance(1500)
    assertEquals(.45f, f.brightness, 0f)
    assertFalse(-1f in f.writes)
  }

  @Test fun changedSystemBrightnessContinuesFadingBeforeReleasingTheOverride() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(3000)
    f.fade.setNight(false, .03f)
    f.advance(1500)
    f.system = .2f
    f.advance(1500)
    val current = f.brightness
    assertTrue(current in .7f.. .8f)
    assertEquals(1, f.pending.size)
    f.writes.clear()
    f.advance(3000)
    assertTrue(f.writes.dropLast(1).all { it >= .2f && it <= current })
    assertTrue(f.writes.dropLast(1).zipWithNext().all { (a, b) -> b <= a })
    assertEquals(-1f, f.brightness, 0f)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun reversingAnUnfinishedFadeStartsFromItsLastAppliedLevel() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(600)
    val current = f.brightness
    f.writes.clear()
    f.fade.setNight(false, .03f)
    assertEquals(current, f.brightness, 0f)
    f.advance(3000)
    assertTrue(f.writes.dropLast(1).all { it >= current && it <= .8f })
    assertEquals(-1f, f.brightness, 0f)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun liveNightLevelChangesRetargetWithoutJumpingOrQueuingAnotherAnimator() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(600)
    val current = f.brightness
    f.fade.setNight(true, .15f)
    assertEquals(current, f.brightness, 0f)
    assertEquals(1, f.pending.size)
    f.advance(3000)
    assertEquals(.15f, f.brightness, 0f)
  }

  @Test fun repeatedSameTargetDoesNotExtendTheFade() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(600)
    f.fade.setNight(true, .03f)
    f.advance(2400)
    assertEquals(.03f, f.brightness, 0f)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun hidingCancelsWorkWithoutBrighteningTheDepartingWindow() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(600)
    val current = f.brightness
    f.fade.setActive(false)
    assertTrue(f.pending.isEmpty())
    f.advance(6000)
    assertEquals(current, f.brightness, 0f)
    f.fade.setActive(true)
    f.advance(3000)
    assertEquals(.03f, f.brightness, 0f)
  }

  @Test fun closeRejectsAlreadyQueuedCallbacksAndFutureVisibilityChanges() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    val stale = f.pending.keys.single()
    f.advance(100)
    val current = f.brightness
    f.fade.close()
    stale.run()
    f.fade.setNight(false, .03f)
    f.fade.setActive(true)
    f.advance(6000)
    assertEquals(current, f.brightness, 0f)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun hiddenRequestsApplyOnlyTheLatestTargetOnReturn() {
    val f = Fixture()
    f.fade.setNight(true, .03f)
    f.fade.setNight(true, .08f)
    assertTrue(f.writes.isEmpty())
    assertTrue(f.pending.isEmpty())
    f.fade.setActive(true)
    f.advance(3000)
    assertEquals(.08f, f.brightness, 0f)
  }

  @Test fun aLateCallbackSettlesOnceWithoutReplayingMissedTicks() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    val task = f.pending.keys.single()
    f.pending.clear()
    f.now = 9000
    task.run()
    assertEquals(listOf(.03f), f.writes)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun nearDarkRampKeepsSmallRelativeStepsInBothDirections() {
    val f = Fixture(prior = .03f)
    f.fade.setActive(true)
    for ((start, target) in listOf(.03f to .01f, .01f to .03f)) {
      f.writes.clear()
      f.fade.setNight(true, target)
      f.advance(1500)
      // A perceptual midpoint is below the arithmetic midpoint (.02).
      assertTrue(f.brightness in .018f.. .019f)
      f.advance(1500)
      val levels = listOf(start) + f.writes
      assertTrue("Sub-1/255 window commands must not be discarded", f.writes.size in 80..91)
      assertTrue(levels.zipWithNext().all { (a, b) ->
        kotlin.math.abs(b - a) / minOf(a, b) < .02f
      })
      assertTrue(levels.zipWithNext().all { (a, b) ->
        if (target < start) b <= a else b >= a
      })
      assertEquals(target, f.brightness, 0f)
      assertTrue(f.pending.isEmpty())
    }
  }

  @Test fun fullRangeRampsReachExactEndpointsAtThreeSecondsWithBoundedWork() {
    val f = Fixture(prior = 1f)
    f.fade.setActive(true)
    for (target in listOf(0f, 1f)) {
      f.writes.clear()
      val before = f.callbacks
      f.fade.setNight(true, target)
      f.advance(2999)
      assertTrue(f.brightness > 0f && f.brightness < 1f)
      assertEquals(1, f.pending.size)
      f.advance(1)
      assertEquals(target, f.brightness, 0f)
      assertTrue(f.writes.all { it.isFinite() && it in 0f..1f })
      assertTrue(f.callbacks - before <= 91)
      assertTrue(f.writes.size <= 91)
      assertTrue(f.pending.isEmpty())
      val settledWrites = f.writes.size
      f.advance(60_000)
      assertEquals(settledWrites, f.writes.size)
      assertTrue(f.callbacks - before <= 91)
    }
  }

  @Test fun smallSystemChangeNearDarkRetargetsBeforeRestoringTheSentinel() {
    val f = Fixture(system = .01f)
    f.fade.setActive(true)
    f.fade.setNight(true, .005f)
    f.advance(3000)
    f.fade.setNight(false, .005f)
    f.advance(1500)
    f.system = .012f // Less than 1/255, but a 20% change at this brightness.
    f.advance(1500)
    assertTrue(f.brightness in .005f.. .01f)
    assertEquals(1, f.pending.size)
    f.writes.clear()
    f.advance(3000)
    assertTrue(f.writes.dropLast(1).all { it in .005f.. .012f })
    assertTrue(f.writes.dropLast(1).zipWithNext().all { (a, b) -> b >= a })
    assertEquals(-1f, f.brightness, 0f)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun cancellingBeforeFirstTickLeavesDefaultPolicyAndRejectsStaleWork() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    val stale = f.pending.keys.single()
    f.fade.setActive(false)
    stale.run()
    assertEquals(-1f, f.brightness, 0f)
    assertTrue(f.writes.isEmpty())
    assertTrue(f.pending.isEmpty())
    f.fade.setNight(false, .03f)
    f.fade.setActive(true)
    f.advance(6000)
    assertTrue(f.writes.isEmpty())
    assertTrue(f.pending.isEmpty())
  }
}
