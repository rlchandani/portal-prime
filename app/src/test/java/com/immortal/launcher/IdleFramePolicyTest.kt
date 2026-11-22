/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.IdleFramePolicy.Decision
import com.immortal.launcher.IdleFramePolicy.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Home's idle timer opens the photo frame as Hearthpane's Activity, and every case where it leaves the system
 * dream (the fallback) exactly as before.
 */
class IdleFramePolicyTest {
  private val idleMainsFrame = IdleFramePolicy.Facts(
      screensaverEnabled = true, systemDreamIsOurs = true, overnight = false, frameHoldsScreen = true,
      roomEmpty = false, screenInteractive = true, dreaming = false, homeFocused = true, homeKeepsScreenOn = false,
      screenOwnedElsewhere = false)

  private fun skipFor(f: IdleFramePolicy.Facts) = (IdleFramePolicy.decide(f) as Decision.Wait).skip

  @Test fun anIdleHomeOnAMainsPortalOpensTheFrame() {
    assertEquals(Decision.Launch, IdleFramePolicy.decide(idleMainsFrame))
  }

  @Test fun whenTheOldDreamWouldNotRunTheFrameDoesNotEither() {
    assertEquals(Skip.SCREENSAVER_OFF, skipFor(idleMainsFrame.copy(screensaverEnabled = false)))
    assertEquals(Skip.NOT_OUR_DREAM, skipFor(idleMainsFrame.copy(systemDreamIsOurs = false)))
    assertEquals(Skip.KEPT_AWAKE, skipFor(idleMainsFrame.copy(homeKeepsScreenOn = true)))
    assertEquals(Skip.SCREEN_OFF, skipFor(idleMainsFrame.copy(screenInteractive = false)))
  }

  /** The overnight window keeps SleepScheduler's dark screen / night clock path untouched. */
  @Test fun overnightIsLeftToTheSleepScheduler() {
    assertEquals(Skip.OVERNIGHT, skipFor(idleMainsFrame.copy(overnight = true)))
  }

  /** Presence mode or a Go on battery: Meta's dream-or-sleep decision must stay in charge. */
  @Test fun aFrameThatWouldNotHoldTheScreenLeavesItToThePresencePolicy() {
    assertEquals(Skip.NOT_HELD, skipFor(idleMainsFrame.copy(frameHoldsScreen = false)))
  }

  @Test fun anEmptyRoomSleepsAsBefore() {
    assertEquals(Skip.ROOM_EMPTY, skipFor(idleMainsFrame.copy(roomEmpty = true)))
  }

  @Test fun aRunningDreamADialogOrAnotherOwnerWins() {
    assertEquals(Skip.DREAMING, skipFor(idleMainsFrame.copy(dreaming = true)))
    assertEquals(Skip.NOT_FOCUSED, skipFor(idleMainsFrame.copy(homeFocused = false)))
    assertEquals(Skip.SCREEN_OWNED, skipFor(idleMainsFrame.copy(screenOwnedElsewhere = true)))
  }

  @Test fun theOffSwitchIsTheReasonLogged() {
    assertEquals(Skip.SCREENSAVER_OFF, skipFor(idleMainsFrame.copy(screensaverEnabled = false, overnight = true,
        dreaming = true, homeKeepsScreenOn = true)))
  }

  @Test fun onlyAConfidentAbsenceMeansAnEmptyRoom() {
    assertTrue(IdleFramePolicy.roomEmpty(PresenceState(Presence.ABSENT, ScreenState.INTERACTIVE, true, 0L)))
    // The always-on frame masks the proxy: an unconfident absence is a guess, not Meta's CV.
    assertFalse(IdleFramePolicy.roomEmpty(PresenceState(Presence.ABSENT, ScreenState.INTERACTIVE, false, 0L)))
    assertFalse(IdleFramePolicy.roomEmpty(PresenceState(Presence.PRESENT, ScreenState.INTERACTIVE, true, 0L)))
    assertFalse(IdleFramePolicy.roomEmpty(PresenceState(Presence.UNKNOWN, ScreenState.OFF, true, 0L)))
  }

  @Test fun theFrameOpensTenSecondsBeforeTheScreenTimeoutAheadOfTheDim() {
    assertEquals(50_000L, IdleFrameTiming.idleDelayMs(60_000L))
    assertEquals(290_000L, IdleFrameTiming.idleDelayMs(300_000L))
    assertEquals(10_000L, IdleFrameTiming.idleDelayMs(20_000L))
  }

  @Test fun aVeryShortTimeoutOpensAtHalfOfIt() {
    assertEquals(7_500L, IdleFrameTiming.idleDelayMs(15_000L))
  }

  @Test fun aScreenThatNeverTimesOutNeverOpensTheFrame() {
    assertNull(IdleFrameTiming.idleDelayMs(Int.MAX_VALUE.toLong()))
    assertNull(IdleFrameTiming.idleDelayMs(0L))
    assertNull(IdleFrameTiming.idleDelayMs(-1L))
  }

  @Test fun aTouchRestartsTheCount() {
    assertEquals(20_000L, IdleFrameTiming.remainingMs(50_000L, idleSinceMs = 30_000L))
    assertEquals(0L, IdleFrameTiming.remainingMs(50_000L, idleSinceMs = 50_000L))
    assertEquals(0L, IdleFrameTiming.remainingMs(50_000L, idleSinceMs = 90_000L))
  }
}
