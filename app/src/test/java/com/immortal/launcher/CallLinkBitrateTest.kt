/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-link adaptive bitrate: the LAN fast-start opening estimate and the step-down /
 * recovery loop. These drive real in-home call quality after a Wi-Fi blip, so the behaviour is
 * pinned here rather than left to the live call. Pure JVM logic, no WebRTC.
 */
class CallLinkBitrateTest {
  private val clean = CallStats(atMs = 0, limitedBy = "none")
  private val busy = CallStats(atMs = 0, limitedBy = "bandwidth")

  // --- LAN fast-start opening estimate --------------------------------------------------------

  @Test fun fastStartOpensAutoAtItsFullBudget() {
    // AUTO's per-link budget (2500) is at the cap, so the picture opens at the full rate.
    assertEquals(2_500, CallLinkBitrate.startEstimateKbps(2_500))
  }

  @Test fun fastStartCapsAVeryHighCeilingSoBestDoesNotBlastTheFullRate() {
    // "Best" (4000) opens at the cap and lets the estimator climb the rest.
    assertEquals(CallLinkBitrate.LAN_FAST_START_KBPS, CallLinkBitrate.startEstimateKbps(4_000))
  }

  @Test fun fastStartOpensSmallBudgetsAtTheirFullCeiling() {
    assertEquals(700, CallLinkBitrate.startEstimateKbps(700)) // Save data
    assertEquals(800, CallLinkBitrate.startEstimateKbps(800)) // group of 3
    assertEquals(600, CallLinkBitrate.startEstimateKbps(600)) // group of 4
  }

  @Test fun fastStartNeverOpensBelowTheFloor() {
    assertEquals(CallLinkBitrate.FLOOR_KBPS, CallLinkBitrate.startEstimateKbps(10))
    assertTrue(CallLinkBitrate.startEstimateKbps(0) >= CallLinkBitrate.FLOOR_KBPS)
  }

  // --- step down ------------------------------------------------------------------------------

  @Test fun congestionStepsTheCeilingDownBy30Percent() {
    assertEquals(560, CallLinkBitrate.next(CallLinkBitrate.State(800), 800, busy).kbps) // 800 * 0.7
  }

  @Test fun repeatedCongestionSettlesAtTheFloorAndStaysThere() {
    var st = CallLinkBitrate.State(800)
    repeat(20) { st = CallLinkBitrate.next(st, 800, busy) }
    assertEquals(CallLinkBitrate.FLOOR_KBPS, st.kbps)
    // Still congested: it holds at the floor, never oscillates upward.
    st = CallLinkBitrate.next(st, 800, busy)
    assertEquals(CallLinkBitrate.FLOOR_KBPS, st.kbps)
    assertEquals(0, st.clean)
  }

  // --- recovery (LAN-tuned) -------------------------------------------------------------------

  @Test fun aLinkMustStayCleanBeforeItClimbs() {
    var st = CallLinkBitrate.State(CallLinkBitrate.FLOOR_KBPS)
    repeat(CallLinkBitrate.CLEAN_SAMPLES_TO_RAISE - 1) { st = CallLinkBitrate.next(st, 800, clean) }
    assertEquals(CallLinkBitrate.FLOOR_KBPS, st.kbps) // not yet
    st = CallLinkBitrate.next(st, 800, clean)
    assertEquals((CallLinkBitrate.FLOOR_KBPS * CallLinkBitrate.STEP_UP).toInt(), st.kbps) // now it climbs
  }

  @Test fun freshCongestionDuringRecoveryResetsTheCleanRunSoItCannotOscillate() {
    var st = CallLinkBitrate.State(CallLinkBitrate.FLOOR_KBPS)
    repeat(CallLinkBitrate.CLEAN_SAMPLES_TO_RAISE - 1) { st = CallLinkBitrate.next(st, 800, clean) }
    st = CallLinkBitrate.next(st, 800, busy) // a blip wipes the clean run
    assertEquals(0, st.clean)
    st = CallLinkBitrate.next(st, 800, clean) // one clean sample is not enough to climb again
    assertEquals(1, st.clean)
    assertEquals(CallLinkBitrate.FLOOR_KBPS, st.kbps)
  }

  @Test fun recoveryStopsExactlyAtTheTargetAndNeverOvershoots() {
    // From a value just below target, the +30% step is clamped to the budget.
    val st = CallLinkBitrate.next(CallLinkBitrate.State(900), 800, clean)
    assertEquals(800, st.kbps)
  }

  @Test fun aFullHealthyLinkReportsItselfAtTheTargetWithNoClimbPending() {
    val st = CallLinkBitrate.next(CallLinkBitrate.State(800), 800, clean)
    assertEquals(800, st.kbps)
    assertEquals(0, st.clean)
  }

  @Test fun recoveryFromTheFloorReachesAnAutoBudgetInAHandfulOfSteps() {
    var st = CallLinkBitrate.State(CallLinkBitrate.FLOOR_KBPS)
    var climbs = 0
    repeat(200) {
      val before = st.kbps
      st = CallLinkBitrate.next(st, 2_500, clean)
      if (st.kbps > before) climbs++
      if (st.kbps >= 2_500) return@repeat
    }
    assertEquals(2_500, st.kbps)
    // Brisk: a dozen climbs would be the old 15% pace; the LAN tuning needs far fewer.
    assertTrue("climbs=$climbs", climbs <= 10)
  }
}
