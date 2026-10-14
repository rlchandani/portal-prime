/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceScreenStatePolicyTest {

  @Test
  fun startupInteractive_preservesObservedDream() {
    for (fallback in ScreenState.values()) {
      val expected = if (fallback == ScreenState.DREAMING) ScreenState.DREAMING else ScreenState.INTERACTIVE
      assertEquals(expected, initialPresenceScreen(interactive = true, fallback = fallback))
    }
  }

  @Test
  fun startupNonInteractive_overridesEveryFallback() {
    for (fallback in ScreenState.values()) {
      assertEquals(ScreenState.OFF, initialPresenceScreen(interactive = false, fallback = fallback))
    }
  }

  @Test
  fun startupUnknown_preservesEveryFallback() {
    assertEquals(ScreenState.OFF, initialPresenceScreen(interactive = null, fallback = ScreenState.OFF))
    assertEquals(ScreenState.INTERACTIVE, initialPresenceScreen(interactive = null, fallback = ScreenState.INTERACTIVE))
    assertEquals(ScreenState.DREAMING, initialPresenceScreen(interactive = null, fallback = ScreenState.DREAMING))
  }

  @Test
  fun screenOnWithUnknownOccupancy_doesNotInferPresence() {
    val previous = PresenceState(Presence.UNKNOWN, ScreenState.OFF, confident = false, sinceMs = 123L)

    assertEquals(
        PresenceState(Presence.UNKNOWN, ScreenState.INTERACTIVE, confident = true, sinceMs = 123L),
        presenceAfterScreenOn(previous, confident = true))
    assertEquals(
        PresenceState(Presence.UNKNOWN, ScreenState.INTERACTIVE, confident = false, sinceMs = 123L),
        presenceAfterScreenOn(previous, confident = false))
  }

  @Test
  fun screenOnWithoutConfidence_masksPreviouslyPresentOccupancy() {
    val previous = PresenceState(Presence.PRESENT, ScreenState.OFF, confident = true, sinceMs = 456L)

    assertEquals(
        PresenceState(Presence.UNKNOWN, ScreenState.INTERACTIVE, confident = false, sinceMs = 456L),
        presenceAfterScreenOn(previous, confident = false))
    assertEquals(
        PresenceState(Presence.PRESENT, ScreenState.OFF, confident = true, sinceMs = 456L),
        previous)
  }

  @Test
  fun screenOnWithAbsentOccupancy_neverPromotesToPresent() {
    val previous = PresenceState(Presence.ABSENT, ScreenState.OFF, confident = false, sinceMs = 789L)

    assertEquals(
        PresenceState(Presence.ABSENT, ScreenState.INTERACTIVE, confident = true, sinceMs = 789L),
        presenceAfterScreenOn(previous, confident = true))
    assertEquals(
        PresenceState(Presence.UNKNOWN, ScreenState.INTERACTIVE, confident = false, sinceMs = 789L),
        presenceAfterScreenOn(previous, confident = false))
  }

  @Test
  fun screenOnWithConfidence_retainsCurrentPresentOccupancy() {
    val previous = PresenceState(Presence.PRESENT, ScreenState.INTERACTIVE, confident = false, sinceMs = 1011L)

    assertEquals(
        PresenceState(Presence.PRESENT, ScreenState.INTERACTIVE, confident = true, sinceMs = 1011L),
        presenceAfterScreenOn(previous, confident = true))
  }

  @Test
  fun screenOnDuringDream_preservesDreaming() {
    val previous = PresenceState(Presence.PRESENT, ScreenState.DREAMING, confident = false, sinceMs = 1213L)

    assertEquals(
        PresenceState(Presence.PRESENT, ScreenState.DREAMING, confident = true, sinceMs = 1213L),
        presenceAfterScreenOn(previous, confident = true))
  }

  @Test
  fun screenOnWithoutConfidenceDuringDream_preservesDreamingAndMasksOccupancy() {
    val previous = PresenceState(Presence.PRESENT, ScreenState.DREAMING, confident = true, sinceMs = 1415L)

    assertEquals(
        PresenceState(Presence.UNKNOWN, ScreenState.DREAMING, confident = false, sinceMs = 1415L),
        presenceAfterScreenOn(previous, confident = false))
  }

  @Test
  fun repeatedScreenOn_isIdempotentForEveryStateAndConfidence() {
    for (presence in Presence.values()) {
      for (screen in ScreenState.values()) {
        for (previousConfidence in listOf(false, true)) {
          for (confident in listOf(false, true)) {
            val previous = PresenceState(presence, screen, previousConfidence, sinceMs = 1617L)
            val first = presenceAfterScreenOn(previous, confident)

            assertEquals(first, presenceAfterScreenOn(first, confident))
            assertEquals(1617L, first.sinceMs)
          }
        }
      }
    }
  }
}
