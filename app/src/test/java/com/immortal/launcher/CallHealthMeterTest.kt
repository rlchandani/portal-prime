/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The in-call health signal ([CallHealthMeter]): the home LAN should read GOOD, and the strained
 * cases (congestion, a busy CPU, a slow round trip, a frozen picture) should read with the plain
 * reason the person sees. A camera the other room turned off must never read as a problem.
 */
class CallHealthMeterTest {
  private fun stats(rttMs: Int? = 15, limitedBy: String = "", recvFps: Double = 30.0, recvKbps: Int = 2_000) =
      CallStats(atMs = 0, rttMs = rttMs, limitedBy = limitedBy, recvFps = recvFps, recvKbps = recvKbps)

  @Test fun aCleanLanLinkIsGood() {
    assertEquals(CallHealth.GOOD, CallHealthMeter.of(stats()).health)
  }

  @Test fun bandwidthLimitedEasesToFair() {
    val r = CallHealthMeter.of(stats(limitedBy = "bandwidth", recvFps = 24.0, recvKbps = 1_500))
    assertEquals(CallHealth.FAIR, r.health)
    assertEquals("Easing quality to keep up", r.reason)
  }

  @Test fun bandwidthLimitedWithACrawlingPictureIsPoor() {
    val r = CallHealthMeter.of(stats(limitedBy = "bandwidth", recvFps = 4.0, recvKbps = 300))
    assertEquals(CallHealth.POOR, r.health)
    assertEquals("Network is busy", r.reason)
  }

  @Test fun cpuLimitedIsFair() {
    val r = CallHealthMeter.of(stats(limitedBy = "cpu", recvFps = 20.0))
    assertEquals(CallHealth.FAIR, r.health)
    assertEquals("Keeping it smooth", r.reason)
  }

  @Test fun aSlowRoundTripIsPoor() {
    val r = CallHealthMeter.of(stats(rttMs = 350))
    assertEquals(CallHealth.POOR, r.health)
    assertEquals("Connection is slow", r.reason)
  }

  @Test fun aSlowRoundTripWhileCongestedBlamesTheNetwork() {
    assertEquals("Network is busy", CallHealthMeter.of(stats(rttMs = 320, limitedBy = "bandwidth")).reason)
  }

  @Test fun anElevatedRoundTripIsFair() {
    val r = CallHealthMeter.of(stats(rttMs = 180))
    assertEquals(CallHealth.FAIR, r.health)
    assertEquals("Connection is a little slow", r.reason)
  }

  @Test fun aFrozenPictureIsPoor() {
    val r = CallHealthMeter.of(stats(recvFps = 0.5, recvKbps = 200))
    assertEquals(CallHealth.POOR, r.health)
    assertEquals("Picture is catching up", r.reason)
  }

  @Test fun aCameraTheOtherRoomTurnedOffIsNotAProblem() {
    // No inbound frames or bytes, camera off: good, never "frozen".
    assertEquals(CallHealth.GOOD, CallHealthMeter.of(stats(recvFps = 0.0, recvKbps = 0), remoteCameraOn = false).health)
  }

  @Test fun missingRoundTripDoesNotFalselyAlarm() {
    assertEquals(CallHealth.GOOD, CallHealthMeter.of(stats(rttMs = null)).health)
  }
}
