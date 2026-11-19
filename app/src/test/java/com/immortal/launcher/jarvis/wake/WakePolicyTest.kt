/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import com.immortal.launcher.jarvis.wake.WakePolicy.Decision
import com.immortal.launcher.jarvis.wake.WakePolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/** When Hearthpane may listen for "Hey Jarvis": every pause rule, and which reason wins. */
class WakePolicyTest {
  private val listening = WakePolicy.Facts(
      enabled = true, micPermission = true, jarvisInstalled = true, modelReady = true, screenInteractive = true,
      dreaming = false, hostResumed = true, hostEligible = true, callActive = false, cameraInUse = false,
      micClaimed = false, quietHours = false, handingOff = false)

  private fun pausedFor(facts: WakePolicy.Facts) = (WakePolicy.decide(facts) as Decision.Pause).reason

  @Test fun aHearthpaneScreenInFrontWithNothingElseOnTheMicListens() {
    assertEquals(Decision.Listen, WakePolicy.decide(listening))
  }

  @Test fun everyRequiredConditionPausesWhenMissing() {
    assertEquals(Reason.DISABLED, pausedFor(listening.copy(enabled = false)))
    assertEquals(Reason.NO_PERMISSION, pausedFor(listening.copy(micPermission = false)))
    assertEquals(Reason.NO_JARVIS, pausedFor(listening.copy(jarvisInstalled = false)))
    assertEquals(Reason.NO_MODEL, pausedFor(listening.copy(modelReady = false)))
  }

  @Test fun screenOffAndTheDreamPause() {
    assertEquals(Reason.SCREEN_OFF, pausedFor(listening.copy(screenInteractive = false)))
    assertEquals(Reason.DREAMING, pausedFor(listening.copy(dreaming = true)))
    assertEquals(Reason.INELIGIBLE_SCREEN, pausedFor(listening.copy(hostEligible = false)))
  }

  @Test fun anotherAppOrJarvissCardInFrontPauses() {
    assertEquals(Reason.NOT_IN_FRONT, pausedFor(listening.copy(hostResumed = false, hostEligible = false)))
    assertEquals(Reason.HANDING_OFF, pausedFor(listening.copy(handingOff = true)))
  }

  @Test fun callsTheCameraOtherRecordingsAndQuietHoursPause() {
    assertEquals(Reason.CALL, pausedFor(listening.copy(callActive = true)))
    assertEquals(Reason.CAMERA, pausedFor(listening.copy(cameraInUse = true)))
    assertEquals(Reason.MIC_BUSY, pausedFor(listening.copy(micClaimed = true)))
    assertEquals(Reason.QUIET_HOURS, pausedFor(listening.copy(quietHours = true)))
  }

  @Test fun theOffSwitchIsTheReasonShownEvenWhenOtherThingsAlsoPause() {
    assertEquals(Reason.DISABLED, pausedFor(listening.copy(enabled = false, callActive = true, quietHours = true)))
    assertEquals(Reason.SCREEN_OFF, pausedFor(listening.copy(screenInteractive = false, hostResumed = false)))
    assertEquals(Reason.CALL, pausedFor(listening.copy(callActive = true, cameraInUse = true, micClaimed = true)))
  }
}
