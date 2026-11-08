/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.MusicSharePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Quiet hours ask first; a routine on screen or a call declines; playing anyway is quiet. */
class MusicSharePolicyTest {
  @Test fun decisions() {
    assertEquals(Decision.ACCEPT, MusicSharePolicy.decide(quiet = false, routine = false, inCall = false, playAnyway = false))
    assertEquals(Decision.QUIET, MusicSharePolicy.decide(quiet = true, routine = false, inCall = false, playAnyway = false))
    assertEquals(Decision.ACCEPT_QUIETLY, MusicSharePolicy.decide(quiet = true, routine = false, inCall = false, playAnyway = true))
    assertEquals(Decision.ROUTINE, MusicSharePolicy.decide(quiet = true, routine = true, inCall = false, playAnyway = true))
    assertEquals(Decision.CALL, MusicSharePolicy.decide(quiet = false, routine = true, inCall = true, playAnyway = true))
    // Play anyway outside quiet hours is just a normal play, at normal volume.
    assertEquals(Decision.ACCEPT, MusicSharePolicy.decide(quiet = false, routine = false, inCall = false, playAnyway = true))
  }

  @Test fun declineReasons() {
    assertEquals("A routine is on screen in Living Area. Nothing was played.", MusicSharePolicy.reason(Decision.ROUTINE, "Living Area"))
    assertEquals("Living Area is on a call. Nothing was played.", MusicSharePolicy.reason(Decision.CALL, "Living Area"))
    assertNull(MusicSharePolicy.reason(Decision.ACCEPT, "Living Area"))
  }

  @Test fun roomState_refusalsWinOverPlaying() {
    assertEquals("routine", MusicSharePolicy.roomState(quiet = true, routine = false, inCall = true, playing = true))
    assertEquals("quiet", MusicSharePolicy.roomState(quiet = true, routine = false, inCall = false, playing = true))
    assertEquals("playing", MusicSharePolicy.roomState(quiet = false, routine = false, inCall = false, playing = true))
    assertEquals("idle", MusicSharePolicy.roomState(quiet = false, routine = false, inCall = false, playing = false))
  }

  @Test fun quietVolume_isCappedLowButNeverRaised() {
    assertEquals(3, MusicSharePolicy.quietStep(current = 8, max = 15))
    assertEquals(2, MusicSharePolicy.quietStep(current = 2, max = 15))
    assertEquals(1, MusicSharePolicy.quietStep(current = 5, max = 3))
    assertEquals(4, MusicSharePolicy.quietStep(current = 4, max = 0))
  }

  @Test fun clock() {
    assertEquals("7:00 AM", MusicSharePolicy.clock(7 * 60))
    assertEquals("12:00 AM", MusicSharePolicy.clock(0))
    assertEquals("12:30 PM", MusicSharePolicy.clock(12 * 60 + 30))
    assertEquals("10:05 PM", MusicSharePolicy.clock(22 * 60 + 5))
  }
}
