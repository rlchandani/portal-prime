/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What this Portal reports about its own room for follow-me, and what counts as someone arriving. */
class FollowMeArrivalTest {
  private val t = 10_000_000L

  @Test fun aGuessIsUnknownNotPresent() {
    // Meta's camera presence pauses while Hearthpane's music screen is in front; the last reading
    // is kept but marked not confident. Follow-me must not read that as "someone is still here".
    assertEquals(Presence.UNKNOWN, FollowMeArrival.local(Presence.PRESENT, confident = false))
    assertEquals(Presence.PRESENT, FollowMeArrival.local(Presence.PRESENT, confident = true))
    assertEquals(Presence.ABSENT, FollowMeArrival.local(Presence.ABSENT, confident = true))
  }

  @Test fun presentAfterEmptyIsAnArrival() {
    assertTrue(FollowMeArrival.isArrival(Presence.ABSENT, t - 1_000L, Presence.PRESENT, t))
  }

  @Test fun presentAfterALongUnknownIsAnArrival() {
    assertTrue(FollowMeArrival.isArrival(Presence.UNKNOWN, t - FollowMeArrival.UNKNOWN_MS, Presence.PRESENT, t))
  }

  @Test fun aBriefUnknownBlipIsNotAnArrival() {
    assertFalse(FollowMeArrival.isArrival(Presence.UNKNOWN, t - 5_000L, Presence.PRESENT, t))
  }

  @Test fun onlyBecomingPresentCounts() {
    assertFalse(FollowMeArrival.isArrival(Presence.PRESENT, t - 120_000L, Presence.ABSENT, t))
    assertFalse(FollowMeArrival.isArrival(Presence.ABSENT, t - 120_000L, Presence.UNKNOWN, t))
  }
}
