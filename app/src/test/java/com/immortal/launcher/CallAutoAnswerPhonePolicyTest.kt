/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Auto-answer eligibility, its place in the ring decision, the countdown, and phone peers in the machine. */
class CallAutoAnswerPhonePolicyTest {
  private val sam = "22222222-2222-2222-2222-222222222222"
  private val id = "0123456789abcdef"
  private val t0 = 1_000_000L
  private val on = AutoAnswerRules(grandmaMode = true, enabled = true, allowed = setOf(sam))
  private val phone = CallContact(CallPhoneId.of("tok"), "Sam’s phone", kind = CallContact.Kind.PHONE)

  private fun eligible(rules: AutoAnswerRules = on, caller: String = sam, kind: CallKind = CallKind.CALL, quiet: Boolean = false,
                       declined: Long? = null, now: Long = t0) = CallAutoAnswer.eligible(rules, caller, kind, quiet, declined, now)

  @Test fun onlyForChosenCallersInGrandmaModeWithAutoAnswerOn() {
    assertTrue(eligible())
    assertFalse("off by default", eligible(AutoAnswerRules()))
    assertFalse(eligible(on.copy(grandmaMode = false)))
    assertFalse(eligible(on.copy(enabled = false)))
    assertFalse(eligible(caller = "33333333-3333-3333-3333-333333333333"))
    assertFalse("never a drop-in", eligible(kind = CallKind.DROP_IN))
    assertFalse("never in quiet hours", eligible(quiet = true))
  }

  @Test fun notNowSnoozesThatCallerForHalfAnHour() {
    assertFalse(eligible(declined = t0 - 1_000))
    assertTrue(eligible(declined = t0 - CallAutoAnswer.SNOOZE_AFTER_DECLINE_MS))
    val history = listOf(
        CallHistoryEntry("a".repeat(16), sam, "Sam", CallDirection.INCOMING, CallKind.CALL, CallEndReason.DECLINED, 5_000, 0),
        CallHistoryEntry("b".repeat(16), sam, "Sam", CallDirection.INCOMING, CallKind.CALL, CallEndReason.DECLINED, 9_000, 0),
        CallHistoryEntry("c".repeat(16), sam, "Sam", CallDirection.OUTGOING, CallKind.CALL, CallEndReason.DECLINED, 20_000, 0),
        CallHistoryEntry("d".repeat(16), "x", "X", CallDirection.INCOMING, CallKind.CALL, CallEndReason.DECLINED, 30_000, 0))
    assertEquals(9_000L, CallAutoAnswer.lastDeclinedMs(history, sam))
    assertNull(CallAutoAnswer.lastDeclinedMs(history, "nobody"))
  }

  private fun ctx(autoAnswer: Boolean = true, quiet: Boolean = false, fromPhone: Boolean = false, phonesAllowed: Boolean = true,
                  kind: CallKind = CallKind.CALL) = IncomingContext(kind, CallWhoCanCall.HOME, quiet, CallQuietMode.VISUAL, true,
      DropInPolicy.CHIME, false, autoAnswer, fromPhone, phonesAllowed)

  @Test fun quietHoursWinOverAutoAnswerEvenWithTheSilentRingOption() {
    assertEquals(IncomingDecision.AutoAnswer, CallPolicy.decide(ctx()))
    assertEquals(IncomingDecision.Ring(silent = true), CallPolicy.decide(ctx(quiet = true)))
  }

  @Test fun phonesNeverDropInAndCanBeTurnedAway() {
    assertEquals(IncomingDecision.Reject(CallReply.CALLS_OFF), CallPolicy.decide(ctx(fromPhone = true, kind = CallKind.DROP_IN)))
    assertEquals(IncomingDecision.Reject(CallReply.CALLS_OFF), CallPolicy.decide(ctx(fromPhone = true, phonesAllowed = false)))
    assertEquals(IncomingDecision.Ring(false), CallPolicy.decide(ctx(autoAnswer = false, fromPhone = true)))
  }

  private fun autoRing(peer: CallContact = CallContact(sam, "Sam")) = CallMachine.reduce(CallState(),
      CallEvent.Incoming(id, peer, CallKind.CALL, true, "192.168.1.20", 8723, IncomingDecision.AutoAnswer, t0))

  @Test fun autoAnswerChimesAndConnectsAfterTenSeconds() {
    val step = autoRing()
    val call = step.state.call!!
    assertEquals(CallPhase.RINGING, call.phase)
    assertEquals(t0 + CallTimings.AUTO_ANSWER_MS, call.autoAnswerAtMs)
    assertTrue(CallEffect.AutoAnswerChime in step.effects)
    assertFalse("no ringtone, the chime and countdown instead", step.effects.any { it is CallEffect.StartRing })
    assertFalse("nothing opens during the countdown", step.effects.any { it is CallEffect.StartMedia || it is CallEffect.OpenPreview })
    assertEquals(CallPhase.RINGING, CallMachine.reduce(step.state, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS - 1)).state.call!!.phase)
    val answered = CallMachine.reduce(step.state, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS))
    assertEquals(CallPhase.CONNECTING, answered.state.call!!.phase)
    assertNull(answered.state.call!!.autoAnswerAtMs)
    assertTrue(answered.effects.any { it is CallEffect.StartMedia })
  }

  @Test fun notNowDuringTheCountdownDeclines() {
    val step = CallMachine.reduce(autoRing().state, CallEvent.Decline(t0 + 3_000))
    assertNull(step.state.call)
    assertEquals(CallEndReason.DECLINED, step.state.ended!!.reason)
    assertTrue(CallEffect.SendReply(id, sam, CallReply.DECLINED) in step.effects)
  }

  @Test fun answeringAPhonesCallTellsThePhoneToOffer() {
    val ring = CallMachine.reduce(CallState(), CallEvent.Incoming(id, phone, CallKind.CALL, true, null, 0, IncomingDecision.Ring(false), t0))
    val step = CallMachine.reduce(ring.state, CallEvent.Accept(audioOnly = false, now = t0 + 2_000))
    assertEquals(CallPhase.CONNECTING, step.state.call!!.phase)
    assertTrue(CallEffect.SendGo(id, phone.deviceId) in step.effects)
    assertFalse("the phone is the offerer, the Portal never offers to it", step.effects.any { it is CallEffect.StartMedia })
  }
}

/** Review finding: an auto-answered call must never be widened to people Grandma didn't choose. */
class CallAutoAnswerGroupTest {
  private val sam = CallContact("22222222-2222-2222-2222-222222222222", "Sam")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val id = "0123456789abcdef"
  private val t0 = 1_000_000L

  @Test fun aGroupRingNeverAnswersByItself() {
    val step = CallMachine.reduce(CallState(), CallEvent.Incoming(id, sam, CallKind.CALL, true, "192.168.1.20", 8723,
        IncomingDecision.AutoAnswer, t0, listOf(CallMemberRef(den, "192.168.1.30", 8723))))
    assertNull(step.state.call!!.autoAnswerAtMs)
    assertTrue(step.effects.any { it is CallEffect.StartRing })
  }

  @Test fun anAutoAnsweredCallRefusesJoinsAndAdds() {
    var s = CallMachine.reduce(CallState(), CallEvent.Incoming(id, sam, CallKind.CALL, true, "192.168.1.20", 8723,
        IncomingDecision.AutoAnswer, t0)).state
    s = CallMachine.reduce(s, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS)).state
    assertTrue(s.call!!.autoAnswered)
    s = CallMachine.reduce(s, CallEvent.MediaUp(id, t0 + 11_000, sam.deviceId)).state
    assertEquals(CallPhase.LIVE, s.call!!.phase)
    assertEquals(s, CallMachine.reduce(s, CallEvent.RemoteJoin(id, sam.deviceId, den, t0 + 12_000)).state)
    assertEquals(s, CallMachine.reduce(s, CallEvent.AddPerson(den, t0 + 12_000)).state)
  }
}

/** Review finding: a group ring stripped of members (Group calls off here) still never auto-answers. */
class CallAutoAnswerStrippedGroupTest {
  @Test fun groupRingFlagSurvivesStrippedMembers() {
    val sam = CallContact("22222222-2222-2222-2222-222222222222", "Sam")
    val step = CallMachine.reduce(CallState(), CallEvent.Incoming("0123456789abcdef", sam, CallKind.CALL, true, "192.168.1.20", 8723,
        IncomingDecision.AutoAnswer, 1_000L, emptyList(), groupRing = true))
    assertNull(step.state.call!!.autoAnswerAtMs)
  }

  @Test fun householdKeyWindowIsClosedUntilAPairing() {
    assertFalse(RemotePairing.householdWindowOpen(System.currentTimeMillis()))
  }
}
