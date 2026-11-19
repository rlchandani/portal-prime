/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gap between hearing "Hey Jarvis" and Jarvis's card owning the mic. */
class WakeHandoffTest {
  @Test fun idleIsNotHandingOff() {
    assertFalse(WakeHandoff().active(0L))
    assertEquals(WakeHandoff(), WakeHandoff().hostPaused().hostResumed())
  }

  @Test fun aWakeHoldsTheMicForJarvisUntilTheCardCoversAndThenCloses() {
    val woke = WakeHandoff().woke(1_000L)
    assertTrue(woke.active(1_100L))
    val covered = woke.hostPaused()
    assertTrue("the card can stay up for minutes", covered.active(1_000L + 10 * WakeHandoff.EXPIRY_MS))
    assertFalse(covered.hostResumed().active(1_000L + 10 * WakeHandoff.EXPIRY_MS))
  }

  @Test fun theScreenResumingBeforeTheCardCoversItDoesNotEndTheHandoff() {
    val woke = WakeHandoff().woke(1_000L).hostResumed()
    assertTrue(woke.active(1_200L))
  }

  @Test fun aDroppedLaunchExpires() {
    val woke = WakeHandoff().woke(1_000L)
    assertTrue(woke.active(1_000L + WakeHandoff.EXPIRY_MS - 1))
    assertFalse(woke.active(1_000L + WakeHandoff.EXPIRY_MS))
  }
}
