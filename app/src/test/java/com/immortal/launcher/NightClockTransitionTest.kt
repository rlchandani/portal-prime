/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class NightClockTransitionTest {
  private class Fixture {
    var now = 0L
    val pending = linkedMapOf<Runnable, Long>()
    val levels = mutableListOf<Float>()
    val settled = mutableListOf<Boolean>()
    val transition = NightClockTransition(
        nowMs = { now },
        postDelayed = { task, delay -> pending[task] = now + delay },
        removeCallbacks = { pending.remove(it) },
        onReveal = { levels.add(it) },
        onSettled = { settled.add(it) })
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

  @Test fun enteringAndLeavingNeverReplaceThePhotoInOneFrame() {
    val f = Fixture()
    f.transition.setActive(true)
    f.transition.setNight(true)
    assertEquals(0f, f.transition.reveal, 0f)
    assertTrue(f.transition.coversPhotos)
    f.advance(1500)
    assertTrue(f.transition.reveal in .48f.. .52f)
    assertTrue(f.settled.isEmpty())
    f.advance(1500)
    assertEquals(1f, f.transition.reveal, 0f)
    assertEquals(listOf(true), f.settled)
    assertTrue(f.levels.zipWithNext().all { (a, b) -> b >= a })
    f.levels.clear()
    f.transition.setNight(false)
    assertEquals(1f, f.transition.reveal, 0f)
    assertTrue(f.transition.coversPhotos)
    f.advance(1500)
    assertTrue(f.transition.reveal in .48f.. .52f)
    assertTrue(f.transition.coversPhotos)
    f.advance(1500)
    assertEquals(0f, f.transition.reveal, 0f)
    assertFalse(f.transition.coversPhotos)
    assertEquals(listOf(true, false), f.settled)
    assertTrue(f.levels.zipWithNext().all { (a, b) -> b <= a })
    assertTrue(f.pending.isEmpty())
  }

  @Test fun reversalStartsAtTheVisibleBlendWithOnePendingCallback() {
    val f = Fixture()
    f.transition.setActive(true)
    f.transition.setNight(true)
    f.advance(1000)
    val before = f.transition.reveal
    f.transition.setNight(false)
    assertEquals(before, f.transition.reveal, 0f)
    assertEquals(1, f.pending.size)
    f.advance(3000)
    assertEquals(listOf(false), f.settled)
    assertEquals(0f, f.transition.reveal, 0f)
  }

  @Test fun hiddenHostFreezesAndResumesWithoutReleasingTheRetainedPhoto() {
    val f = Fixture()
    f.transition.setActive(true)
    f.transition.setNight(true)
    f.advance(800)
    f.transition.setActive(false)
    val before = f.transition.reveal
    f.advance(10_000)
    assertEquals(before, f.transition.reveal, 0f)
    assertTrue(f.pending.isEmpty())
    f.transition.setNight(false)
    assertTrue(f.settled.isEmpty())
    f.transition.setActive(true)
    f.advance(3000)
    assertFalse(f.transition.coversPhotos)
    assertEquals(listOf(false), f.settled)
  }

  @Test fun tapDepartureCancelsRatherThanPlayingAnExitAnimation() {
    val f = Fixture()
    f.transition.setActive(true)
    f.transition.setNight(true)
    f.advance(800)
    val stale = f.pending.keys.single()
    val before = f.levels.size
    f.transition.close()
    stale.run()
    f.transition.setActive(true)
    f.transition.setNight(false)
    f.advance(5000)
    assertEquals(before, f.levels.size)
    assertTrue(f.pending.isEmpty())
    assertTrue(f.settled.isEmpty())
  }

  @Test fun repeatedSensorStateDoesNotRestartTheTransitionOrLeaveATicker() {
    val f = Fixture()
    f.transition.setActive(true)
    f.transition.setNight(true)
    f.advance(1000)
    repeat(100) { f.transition.setNight(true) }
    f.advance(2000)
    assertEquals(1f, f.transition.reveal, 0f)
    assertEquals(listOf(true), f.settled)
    assertTrue(f.levels.size in 100..190)
    assertTrue(f.pending.isEmpty())
  }

  @Test fun disablingBeforeTheFirstFrameReleasesTheSuspendedPhoto() {
    val f = Fixture()
    f.transition.setActive(true)
    f.transition.setNight(true)
    f.transition.setNight(false)
    assertEquals(0f, f.transition.reveal, 0f)
    assertFalse(f.transition.coversPhotos)
    assertEquals(listOf(false), f.settled)
    assertTrue(f.pending.isEmpty())
  }
}
