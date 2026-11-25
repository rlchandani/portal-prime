/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CallReconnectPolicy.Action
import com.immortal.launcher.CallReconnectPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offerer-link ICE-restart decision ([CallReconnectPolicy]) and the answer gate
 * ([CallAnswerGate]). These are the testable brain of the F1 reconnection work; the live recovery
 * itself needs a 2-Portal test, so every timing and glare rule is pinned here first.
 */
class CallReconnectPolicyTest {
  private val P = CallReconnectPolicy
  private val t0 = 1_000_000L
  private fun down(now: Long = t0) = P.down(State(), now)

  // --- decide: gating -------------------------------------------------------------------------

  @Test fun aHealthyLinkWaits() {
    assertEquals(Action.WAIT, P.decide(State(), t0, offerer = true, enabled = true))
  }

  @Test fun onlyTheOffererMayRestart() {
    val st = down()
    assertEquals(Action.WAIT, P.decide(st, t0 + P.GRACE_MS, offerer = false, enabled = true))
  }

  @Test fun disabledBuildsNeverRestart() {
    // Shipping builds keep today's behaviour: the trigger is off until the live test enables it.
    val st = down()
    assertEquals(Action.WAIT, P.decide(st, t0 + P.GRACE_MS, offerer = true, enabled = false))
  }

  // --- decide: timing -------------------------------------------------------------------------

  @Test fun aBlipIsGivenTheGraceToSelfHealFirst() {
    val st = down()
    assertEquals(Action.WAIT, P.decide(st, t0 + P.GRACE_MS - 1, offerer = true, enabled = true))
    assertEquals(Action.RESTART_ICE, P.decide(st, t0 + P.GRACE_MS, offerer = true, enabled = true))
  }

  @Test fun theFirstDropInstantSticksAcrossRepeats() {
    val st = P.down(P.down(State(), t0), t0 + 500)
    assertEquals(t0, st.downSinceMs)
  }

  @Test fun attemptsAreSpacedByTheBackoff() {
    val st = State(downSinceMs = t0, attempts = 1, lastAttemptMs = t0 + 5_000)
    assertEquals(Action.WAIT, P.decide(st, t0 + 5_000 + P.ATTEMPT_BACKOFF_MS - 1, offerer = true, enabled = true))
    assertEquals(Action.RESTART_ICE, P.decide(st, t0 + 5_000 + P.ATTEMPT_BACKOFF_MS, offerer = true, enabled = true))
  }

  @Test fun itGivesUpAfterTheAttemptBudgetSoTheCallCanEndHonestly() {
    val st = State(downSinceMs = t0, attempts = P.MAX_ATTEMPTS, lastAttemptMs = t0 + 1)
    assertEquals(Action.GIVE_UP, P.decide(st, t0 + 1_000_000, offerer = true, enabled = true))
  }

  // --- state transitions ----------------------------------------------------------------------

  @Test fun recoveryClearsTheWholeRecord() {
    assertEquals(State(), P.up())
  }

  @Test fun attemptedRecordsTheCountAndInstant() {
    val st = P.attempted(down(), t0 + P.GRACE_MS)
    assertEquals(1, st.attempts)
    assertEquals(t0 + P.GRACE_MS, st.lastAttemptMs)
    assertEquals(t0, st.downSinceMs)
  }

  @Test fun aPersistentOutageRestartsExactlyTheBudgetThenGivesUp() {
    var st = down()
    var now = t0 + P.GRACE_MS
    var restarts = 0
    repeat(50) {
      when (P.decide(st, now, offerer = true, enabled = true)) {
        Action.RESTART_ICE -> { st = P.attempted(st, now); restarts++ }
        Action.GIVE_UP -> return@repeat
        Action.WAIT -> Unit
      }
      now += P.ATTEMPT_BACKOFF_MS
    }
    assertEquals(P.MAX_ATTEMPTS, restarts)
    assertEquals(Action.GIVE_UP, P.decide(st, now, offerer = true, enabled = true))
  }

  // --- CallAnswerGate -------------------------------------------------------------------------

  private val id = "0123456789abcdef"
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private fun call(phase: CallPhase) = ActiveCall(id, kitchen, CallDirection.OUTGOING, CallKind.CALL, true, phase, 0, 0, cameraOn = true)
  private fun withLeg(phase: LegPhase) = call(CallPhase.LIVE).copy(legs = listOf(CallLeg(den, phase, 0, offerer = false)))

  @Test fun gateRejectsNoCallWrongCallAndStrangers() {
    assertFalse(CallAnswerGate.accepts(null, id, kitchen.deviceId))
    assertFalse(CallAnswerGate.accepts(call(CallPhase.CONNECTING), "other", kitchen.deviceId))
    assertFalse(CallAnswerGate.accepts(call(CallPhase.CONNECTING), id, "stranger"))
  }

  @Test fun gateAcceptsTheInitialPeerOfferWhileConnecting() {
    assertTrue(CallAnswerGate.accepts(call(CallPhase.CONNECTING), id, kitchen.deviceId))
  }

  @Test fun gateRejectsThePeerBeforeItHasAnswered() {
    assertFalse(CallAnswerGate.accepts(call(CallPhase.RINGING), id, kitchen.deviceId))
  }

  @Test fun gateAcceptsASignedReofferWhileThePeerIsUp() {
    assertTrue(CallAnswerGate.accepts(call(CallPhase.LIVE), id, kitchen.deviceId))
    assertTrue(CallAnswerGate.accepts(call(CallPhase.RECONNECTING), id, kitchen.deviceId))
  }

  @Test fun gateAcceptsALegWhileJoiningOrUpButNotWhileMerelyRinging() {
    assertTrue(CallAnswerGate.accepts(withLeg(LegPhase.JOINING), id, den.deviceId))
    assertTrue(CallAnswerGate.accepts(withLeg(LegPhase.LIVE), id, den.deviceId))
    assertTrue(CallAnswerGate.accepts(withLeg(LegPhase.RECONNECTING), id, den.deviceId))
    assertFalse(CallAnswerGate.accepts(withLeg(LegPhase.RINGING), id, den.deviceId))
  }

  @Test fun freshStateHasNoDrop() {
    assertNull(State().downSinceMs)
  }
}
