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

/** Control Center opened over the photo dream (fleet action, alert banner) must not stay "dreaming". */
class ControlCenterPresenceTest {

  @Test
  fun focusedAppOverDream_endsDreamingWithoutInferringOccupancy() {
    for (presence in Presence.values()) {
      for (confident in listOf(false, true)) {
        val dreaming = PresenceState(presence, ScreenState.DREAMING, confident, sinceMs = 42L)
        assertEquals(dreaming.copy(screen = ScreenState.INTERACTIVE), presenceAfterAppFocused(dreaming))
      }
    }
  }

  @Test
  fun focusedAppWhileInteractiveOrOff_isUnchangedOrInteractive() {
    val interactive = PresenceState(Presence.ABSENT, ScreenState.INTERACTIVE, confident = true, sinceMs = 7L)
    assertEquals(interactive, presenceAfterAppFocused(interactive))
    val off = PresenceState(Presence.UNKNOWN, ScreenState.OFF, confident = false, sinceMs = 7L)
    assertEquals(off.copy(screen = ScreenState.INTERACTIVE), presenceAfterAppFocused(off))
  }

  @Test
  fun ccOpenedOverDream_motionRunsAsSoonAsItHasFocus() {
    val dreaming = PresenceState(Presence.UNKNOWN, ScreenState.DREAMING, confident = false, sinceMs = 1L)
    // Resumed but the dream state is stale: this was the bug (motion stayed off until a touch).
    assertFalse(ccMotionAllowed(resumed = true, interactive = true, screen = dreaming.screen))
    val focused = presenceAfterAppFocused(dreaming)
    assertTrue(ccMotionAllowed(resumed = true, interactive = true, screen = focused.screen))
  }

  @Test
  fun motionStaysOffWhenPausedOrScreenOff() {
    assertFalse(ccMotionAllowed(resumed = false, interactive = true, screen = ScreenState.INTERACTIVE))
    assertFalse(ccMotionAllowed(resumed = true, interactive = false, screen = ScreenState.INTERACTIVE))
  }
}
