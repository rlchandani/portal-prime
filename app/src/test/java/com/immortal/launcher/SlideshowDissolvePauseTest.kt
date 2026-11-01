/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SlideshowDissolvePauseTest {
  private class Fade {
    var running = false
    var elapsed = 0L
    val policy = SlideshowDissolvePause { running = it }
    fun advance(ms: Long) { if (running) elapsed = (elapsed + ms).coerceAtMost(1_800L) }
  }

  @Test fun pauseDuringAnAutomaticDissolveFreezesItsCurrentBlend() {
    val fade = Fade()
    fade.policy.begin(automatic = true)
    fade.advance(450)
    fade.policy.setPaused(true)
    fade.advance(8_000)
    assertEquals(450L, fade.elapsed)
    assertFalse(fade.running)
  }

  @Test fun coveredHostReturningCannotResumeTheUsersPausedDissolve() {
    val fade = Fade()
    fade.policy.begin(automatic = true)
    fade.advance(600)
    fade.policy.setPaused(true)
    fade.policy.setHostActive(false)
    fade.advance(8_000)
    fade.policy.setHostActive(true)
    fade.advance(8_000)
    assertEquals("Full then Photos must retain the paused blend", 600L, fade.elapsed)
    assertFalse(fade.running)
    fade.policy.setPaused(false)
    fade.advance(200)
    assertEquals("Resume continues from the retained fraction", 800L, fade.elapsed)
  }

  @Test fun manualNavigationWhilePausedCompletesItsFiniteDissolve() {
    val fade = Fade()
    fade.policy.setPaused(true)
    fade.policy.begin(automatic = false)
    fade.policy.setPaused(true) // Controller reaffirms pause after showing the manual photo.
    fade.advance(1_800)
    assertEquals(1_800L, fade.elapsed)
    assertTrue(fade.policy.paused)
  }

  @Test fun aManualDissolveStillFreezesUnderTheHostCover() {
    val fade = Fade()
    fade.policy.setPaused(true)
    fade.policy.begin(automatic = false)
    fade.advance(300)
    fade.policy.setHostActive(false)
    fade.advance(8_000)
    assertEquals(300L, fade.elapsed)
    fade.policy.setHostActive(true)
    fade.advance(1_500)
    assertEquals(1_800L, fade.elapsed)
    assertTrue(fade.policy.paused)
  }

  @Test fun resumeUnderANightCoverWaitsUntilTheHostIsActive() {
    val fade = Fade()
    fade.policy.begin(automatic = true)
    fade.advance(300)
    fade.policy.setPaused(true)
    fade.policy.setHostActive(false)
    fade.policy.setPaused(false)
    fade.advance(8_000)
    assertEquals(300L, fade.elapsed)
    fade.policy.setHostActive(true)
    fade.advance(200)
    assertEquals(500L, fade.elapsed)
  }

  @Test fun aNewPauseAfterResumingAlsoFreezesAManualDissolve() {
    val fade = Fade()
    fade.policy.setPaused(true)
    fade.policy.begin(automatic = false)
    fade.advance(200)
    fade.policy.setPaused(false)
    fade.advance(200)
    fade.policy.setPaused(true)
    fade.advance(8_000)
    assertEquals(400L, fade.elapsed)
  }

  @Test fun anAutomaticReplacementDoesNotInheritTheManualPauseExemption() {
    val fade = Fade()
    fade.policy.setPaused(true)
    fade.policy.begin(automatic = false)
    fade.advance(200)
    fade.policy.end()
    fade.policy.begin(automatic = true)
    fade.advance(8_000)
    assertEquals(200L, fade.elapsed)
    assertFalse(fade.running)
  }

  @Test fun endedDissolvesCannotRestartOnHostOrUserResume() {
    val changes = mutableListOf<Boolean>()
    val policy = SlideshowDissolvePause { changes += it }
    policy.begin(automatic = true)
    policy.setPaused(true)
    policy.end() // Stage completion, cancellation, editing or disposal.
    val before = changes.toList()
    policy.setHostActive(false)
    policy.setHostActive(true)
    policy.setPaused(false)
    assertEquals(before, changes)
  }
}
