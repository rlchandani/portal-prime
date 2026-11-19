/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.lifecycle.Lifecycle.State
import com.immortal.launcher.JarvisPendingTaps.Admission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisPendingTapsTest {
  private val home = "home"

  @Test fun firstTapStartsAPreparation() {
    assertEquals(Admission.NEW, JarvisPendingTaps<String>().admit(home, nowMs = 0))
  }

  @Test fun rapidSecondTapJoinsThePendingPreparation() {
    val taps = JarvisPendingTaps<String>()
    taps.hold(home, nowMs = 1_000) { error("a joined tap must not cancel the pending one") }
    assertEquals(Admission.JOINED, taps.admit(home, nowMs = 1_150))
    assertTrue(taps.isHeld(home))
  }

  @Test fun aPreparationThatNeverCompletedNoLongerSwallowsTaps() {
    val taps = JarvisPendingTaps<String>()
    var cancelled = 0
    taps.hold(home, nowMs = 0) { cancelled++ }
    assertEquals(Admission.REPLACED_STALE, taps.admit(home, nowMs = JarvisPendingTaps.STALE_AFTER_MS))
    assertEquals(1, cancelled)
    assertFalse(taps.isHeld(home))
    // Every later tap is admitted too: nothing is left behind to swallow them.
    assertEquals(Admission.NEW, taps.admit(home, nowMs = JarvisPendingTaps.STALE_AFTER_MS + 1))
  }

  @Test fun hoursOfStuckStateAreStillReplaced() {
    val taps = JarvisPendingTaps<String>()
    taps.hold(home, nowMs = 0) {}
    assertEquals(Admission.REPLACED_STALE, taps.admit(home, nowMs = 6 * 60 * 60 * 1_000L))
  }

  @Test fun aClockThatWentBackwardsCannotPinAPendingEntry() {
    val taps = JarvisPendingTaps<String>()
    taps.hold(home, nowMs = 10_000) {}
    assertEquals(Admission.REPLACED_STALE, taps.admit(home, nowMs = 5_000))
  }

  @Test fun aThrowingCancelStillFreesTheHost() {
    val taps = JarvisPendingTaps<String>()
    taps.hold(home, nowMs = 0) { error("observer already gone") }
    assertEquals(Admission.REPLACED_STALE, taps.admit(home, nowMs = 60_000))
    assertFalse(taps.isHeld(home))
  }

  @Test fun aRetiredPreparationCannotReleaseItsReplacement() {
    val taps = JarvisPendingTaps<String>()
    val old = taps.hold(home, nowMs = 0) {}
    taps.admit(home, nowMs = 60_000)
    val fresh = taps.hold(home, nowMs = 60_000) {}
    taps.release(home, old)
    assertTrue(taps.isHeld(home))
    taps.release(home, fresh)
    assertFalse(taps.isHeld(home))
  }

  @Test fun hostsAreIndependent() {
    val taps = JarvisPendingTaps<String>()
    taps.hold(home, nowMs = 0) {}
    assertEquals(Admission.NEW, taps.admit("controlCenter", nowMs = 10))
  }

  @Test fun cancelRunsTheHeldCancellation() {
    val taps = JarvisPendingTaps<String>()
    var cancelled = false
    taps.hold(home, nowMs = 0) { cancelled = true }
    taps.cancel(home)
    assertTrue(cancelled)
  }

  @Test fun aTapOnAResumedScreenNeedsItStillResumed() {
    assertTrue(JarvisTapHost.stillShowing(resumedAtTap = true, now = State.RESUMED))
    assertFalse(JarvisTapHost.stillShowing(resumedAtTap = true, now = State.STARTED))
    assertFalse(JarvisTapHost.stillShowing(resumedAtTap = true, now = State.CREATED))
  }

  @Test fun aTapOnHomeBehindAClosingCardOpensWhileHomeIsStillVisible() {
    assertTrue(JarvisTapHost.stillShowing(resumedAtTap = false, now = State.STARTED))
    assertTrue(JarvisTapHost.stillShowing(resumedAtTap = false, now = State.RESUMED))
  }

  @Test fun aScreenThatStoppedOrWentAwayNeverLaunches() {
    assertFalse(JarvisTapHost.stillShowing(resumedAtTap = false, now = State.CREATED))
    assertFalse(JarvisTapHost.stillShowing(resumedAtTap = false, now = State.DESTROYED))
    assertFalse(JarvisTapHost.stillShowing(resumedAtTap = false, now = null))
  }
}
