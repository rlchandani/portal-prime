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
    f.advance(900)
    assertTrue(f.brightness in .35f.. .45f)
    f.advance(900)
    assertEquals(.03f, f.brightness, 0f)
    assertTrue(f.writes.size in 15..56)
    assertTrue(f.writes.all { it in .03f.. .8f })
    assertTrue(f.writes.zipWithNext().all { (a, b) -> b <= a })
    assertTrue(f.pending.isEmpty())
  }

  @Test fun restoresDefaultOnlyAfterGraduallyReturningToSystemLevel() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(1800)
    f.writes.clear()
    f.system = .6f
    f.fade.setNight(false, .03f)
    f.advance(900)
    assertTrue(f.brightness in .27f.. .35f)
    f.advance(900)
    assertEquals(-1f, f.brightness, 0f)
    assertTrue(f.writes.dropLast(1).all { it in .03f.. .6f })
    assertTrue(f.writes.dropLast(1).zipWithNext().all { (a, b) -> b >= a })
    assertTrue(f.pending.isEmpty())
  }

  @Test fun restoresAnExplicitPriorOverrideExactly() {
    val f = Fixture(prior = .45f, system = .8f)
    f.fade.setActive(true)
    f.fade.setNight(true, .01f)
    f.advance(1800)
    f.fade.setNight(false, .01f)
    f.advance(900)
    assertTrue(f.brightness in .19f.. .26f)
    f.advance(900)
    assertEquals(.45f, f.brightness, 0f)
    assertFalse(-1f in f.writes)
  }

  @Test fun changedSystemBrightnessContinuesFadingBeforeReleasingTheOverride() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(1800)
    f.fade.setNight(false, .03f)
    f.advance(900)
    f.system = .2f
    f.advance(900)
    val current = f.brightness
    assertTrue(current in .7f.. .8f)
    assertEquals(1, f.pending.size)
    f.writes.clear()
    f.advance(1800)
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
    f.advance(1800)
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
    f.advance(1800)
    assertEquals(.15f, f.brightness, 0f)
  }

  @Test fun repeatedSameTargetDoesNotExtendTheFade() {
    val f = Fixture()
    f.fade.setActive(true)
    f.fade.setNight(true, .03f)
    f.advance(600)
    f.fade.setNight(true, .03f)
    f.advance(1200)
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
    f.advance(1800)
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
    f.advance(1800)
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
}
