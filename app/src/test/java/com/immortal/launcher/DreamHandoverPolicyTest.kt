/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.DreamHandoverPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a starting dream hands the screen to the frame Activity at once (so "Hey Jarvis" is not deaf for the ~2
 * minutes until the Portal's force-wake), and every case where the dream stays exactly as before.
 */
class DreamHandoverPolicyTest {
  private val mainsDream = DreamHandoverPolicy.Facts(
      screensaverEnabled = true, overnight = false, frameHoldsScreen = true, roomEmpty = false,
      screenInteractive = true, screenOwnedElsewhere = false, pinned = false, bridged = false)

  private fun decide(f: DreamHandoverPolicy.Facts) = DreamHandoverPolicy.decide(f)

  @Test fun aDreamOnAMainsPortalHandsOverAtOnce() {
    assertEquals(Decision.HandOver, decide(mainsDream))
  }

  @Test fun overnightStaysWithTheDreamAndTheSleepScheduler() {
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.OVERNIGHT), decide(mainsDream.copy(overnight = true)))
  }

  /** Presence mode or a Go on battery: Meta's dream-or-sleep decision must stay in charge. */
  @Test fun aFrameThatWouldNotHoldTheScreenKeepsTheDream() {
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.NOT_HELD), decide(mainsDream.copy(frameHoldsScreen = false)))
  }

  @Test fun theSwitchOffAnEmptyRoomAScreenOffAndAnotherOwnerKeepTheDream() {
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.SCREENSAVER_OFF),
        decide(mainsDream.copy(screensaverEnabled = false)))
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.ROOM_EMPTY), decide(mainsDream.copy(roomEmpty = true)))
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.SCREEN_OFF), decide(mainsDream.copy(screenInteractive = false)))
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.SCREEN_OWNED),
        decide(mainsDream.copy(screenOwnedElsewhere = true)))
  }

  /** A pinned app's curtain covers it, not an Activity (which would displace the app). */
  @Test fun aPinnedAppKeepsItsCurtain() {
    assertEquals(Decision.Owned(DreamHandoverPolicy.Skip.PINNED), decide(mainsDream.copy(pinned = true)))
  }

  @Test fun aCallsOrJarvisHandoffInFlightKeepsTheDream() {
    assertEquals(Decision.Owned(DreamHandoverPolicy.Skip.BRIDGED), decide(mainsDream.copy(bridged = true)))
  }

  /** The shared rules come first, so the log names the idle frame's own reason. */
  @Test fun aSharedRuleIsTheReasonLogged() {
    assertEquals(Decision.Shared(IdleFramePolicy.Skip.OVERNIGHT),
        decide(mainsDream.copy(overnight = true, pinned = true, bridged = true)))
    assertEquals("overnight window", DreamHandoverPolicy.log(decide(mainsDream.copy(overnight = true))))
  }

  /**
   * The exact cases: for every combination of the facts both read, the dream hands over iff Home's idle timer would
   * have opened the frame (no dream-only owner present), and stays for the same reason otherwise.
   */
  @Test fun theDreamStaysInExactlyTheCasesTheIdleFrameLeavesToIt() {
    for (bits in 0 until (1 shl 6)) {
      fun bit(i: Int) = bits and (1 shl i) != 0
      val f = mainsDream.copy(screensaverEnabled = bit(0), overnight = bit(1), frameHoldsScreen = bit(2),
          roomEmpty = bit(3), screenInteractive = bit(4), screenOwnedElsewhere = bit(5))
      val idle = IdleFramePolicy.decide(DreamHandoverPolicy.idleFacts(f))
      val expected = when (idle) {
        IdleFramePolicy.Decision.Launch -> Decision.HandOver
        is IdleFramePolicy.Decision.Wait -> Decision.Shared(idle.skip)
      }
      assertEquals("facts $f", expected, decide(f))
    }
  }

  @Test fun theRunningDreamFillsTheHomeOnlyFactsSoTheyNeverBlock() {
    val idle = DreamHandoverPolicy.idleFacts(mainsDream)
    assertTrue(idle.systemDreamIsOurs)
    assertFalse(idle.dreaming)
    assertTrue(idle.homeFocused)
    assertFalse(idle.homeKeepsScreenOn)
  }

  @Test fun aBridgeIsInFlightDuringTheStockHandoffOrItsGraceWindow() {
    assertTrue(DreamHandoverPolicy.bridged(inStockHandoff = true, bridgeAgoMs = Long.MAX_VALUE))
    assertTrue(DreamHandoverPolicy.bridged(inStockHandoff = false, bridgeAgoMs = 0L))
    assertTrue(DreamHandoverPolicy.bridged(inStockHandoff = false, bridgeAgoMs = BRIDGE_GRACE_MS))
    assertFalse(DreamHandoverPolicy.bridged(inStockHandoff = false, bridgeAgoMs = BRIDGE_GRACE_MS + 1))
    // No bridge ever (bridgeAt = 0) is decades ago, and a clock step backwards is not a bridge.
    assertFalse(DreamHandoverPolicy.bridged(inStockHandoff = false, bridgeAgoMs = System.currentTimeMillis()))
    assertFalse(DreamHandoverPolicy.bridged(inStockHandoff = false, bridgeAgoMs = -5L))
  }

  @Test fun theHandoverWaitsLongerThanAColdFrameStartButNotLongEnoughToBeNoticed() {
    assertTrue(DreamHandoverTiming.FIRST_FRAME_TIMEOUT_MS in 1_000L..5_000L)
    assertTrue(DreamHandoverTiming.WAKE_BRIDGE_MS >= 1_000L)
  }
}
