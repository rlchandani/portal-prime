/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Calling 2b, the pure machine: phones in group calls, vouching, Grandma locks, and moving a call. */
class CallPhoneGroupMoveTest {
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val kids = CallContact("44444444-4444-4444-4444-444444444444", "Kids")
  private val phone = CallContact("ph0123456789abcdef", "Sam’s phone", kind = CallContact.Kind.PHONE)
  private val phone2 = CallContact("phfedcba9876543210", "Priya’s phone", kind = CallContact.Kind.PHONE)
  private val id = "0123456789abcdef"
  private val t0 = 1_000_000L

  private fun reduce(s: CallState, e: CallEvent) = CallMachine.reduce(s, e)

  /** We called the Kitchen and it is live. */
  private fun live(): CallState {
    var s = reduce(CallState(), CallEvent.Dial(id, kitchen, CallKind.CALL, true, t0)).state
    s = reduce(s, CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0 + 100)).state
    s = reduce(s, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 2_000)).state
    return reduce(s, CallEvent.MediaUp(id, t0 + 2_500, kitchen.deviceId)).state
  }

  /** The phone answered our Add and is connected. */
  private fun withPhoneLive(): CallState {
    var s = reduce(live(), CallEvent.AddPerson(phone, t0 + 10_000)).state
    s = reduce(s, CallEvent.Replied(id, phone.deviceId, CallReply.RINGING, null, t0 + 10_200)).state
    s = reduce(s, CallEvent.RemoteAccepted(id, phone.deviceId, t0 + 12_000)).state
    return reduce(s, CallEvent.MediaUp(id, t0 + 12_500, phone.deviceId)).state
  }

  @Test fun aPhoneCanBeAddedAndIsRungWithThePortalsInTheCall() {
    val step = reduce(live(), CallEvent.AddPerson(phone, t0 + 10_000))
    val ring = step.effects.single() as CallEffect.SendGroupRing
    assertEquals(phone, ring.to)
    assertEquals(listOf(kitchen), ring.members)
    assertFalse(ring.move)
    assertEquals(LegPhase.RINGING, step.state.call!!.leg(phone.deviceId)!!.phase)
  }

  @Test fun onlyOnePhonePerCall() {
    val s = withPhoneLive()
    assertEquals(s, reduce(s, CallEvent.AddPerson(phone2, t0 + 20_000)).state)
    assertFalse(CallGroupMachine.canAdd(s.call!!, phone2, move = false))
  }

  @Test fun thePhonesVerifiedOfferMakesUsVouchForItToTheOthers() {
    var s = reduce(live(), CallEvent.AddPerson(phone, t0 + 10_000)).state
    s = reduce(s, CallEvent.Replied(id, phone.deviceId, CallReply.RINGING, null, t0 + 10_200)).state
    val step = reduce(s, CallEvent.RemoteAccepted(id, phone.deviceId, t0 + 12_000))
    assertEquals(LegPhase.JOINING, step.state.call!!.leg(phone.deviceId)!!.phase)
    val join = step.effects.filterIsInstance<CallEffect.SendJoin>().single()
    assertEquals(kitchen.deviceId, join.to)
    assertEquals(phone, join.newcomer)
  }

  @Test fun aCallWithAPhoneCanGrowAndTheNewcomerWaitsForThePhonesOffer() {
    // We are Den, rung into Kitchen's call that already has Sam's phone.
    val ring = CallEvent.Incoming(id, kitchen, CallKind.CALL, true, "192.168.1.20", 8723, IncomingDecision.Ring(false), t0,
        members = listOf(CallMemberRef(phone, null, 0), CallMemberRef(kids, "192.168.1.21", 8723)))
    var s = reduce(CallState(), ring).state
    val step = reduce(s, CallEvent.Accept(false, t0 + 1_000))
    s = step.state
    val phoneLeg = s.call!!.leg(phone.deviceId)!!
    assertFalse("a phone always offers", phoneLeg.offerer)
    assertEquals(LegPhase.JOINING, phoneLeg.phase)
    val legs = step.effects.filterIsInstance<CallEffect.StartLeg>()
    assertEquals(listOf(kids.deviceId), legs.map { it.leg.peer.deviceId })
  }

  @Test fun aGroupRingNeverCarriesMoreThanOnePhone() {
    val ring = CallEvent.Incoming(id, phone, CallKind.CALL, true, null, 0, IncomingDecision.Ring(false), t0,
        members = listOf(CallMemberRef(phone2, null, 0), CallMemberRef(den, "192.168.1.22", 8723)))
    val call = reduce(CallState(), ring).state.call!!
    assertEquals(listOf(den.deviceId), call.invited.map { it.contact.deviceId })
  }

  @Test fun aVouchedPhoneNewcomerJoinsButASecondPhoneNever() {
    val s = live()
    val joined = reduce(s, CallEvent.RemoteJoin(id, kitchen.deviceId, phone, t0 + 5_000)).state
    assertEquals(LegPhase.JOINING, joined.call!!.leg(phone.deviceId)!!.phase)
    assertFalse(joined.call!!.leg(phone.deviceId)!!.offerer)
    assertEquals(joined, reduce(joined, CallEvent.RemoteJoin(id, kitchen.deviceId, phone2, t0 + 6_000)).state)
  }

  @Test fun onlyAConnectedMemberCanVouch() {
    val s = live()
    assertEquals(s, reduce(s, CallEvent.RemoteJoin(id, den.deviceId, phone, t0 + 5_000)).state)
  }

  @Test fun grandmaCallsAnswerAndSayItSoNobodyWidensOrMovesThem() {
    val ring = CallEvent.Incoming(id, kitchen, CallKind.CALL, true, "192.168.1.20", 8723, IncomingDecision.AutoAnswer, t0)
    var s = reduce(CallState(), ring).state
    val step = reduce(s, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS))
    assertTrue(step.state.call!!.autoAnswered)
    val told = step.effects.filterIsInstance<CallEffect.SendMediaState>().single()
    assertTrue(told.autoAnswered)
    s = reduce(step.state, CallEvent.MediaUp(id, t0 + 11_000, kitchen.deviceId)).state
    assertEquals(s, reduce(s, CallEvent.AddPerson(phone, t0 + 12_000)).state)
    assertEquals(s, reduce(s, CallEvent.MoveTo(phone, t0 + 12_000)).state)
    assertEquals(s, reduce(s, CallEvent.RemoteJoin(id, kitchen.deviceId, den, t0 + 12_000)).state)
  }

  @Test fun theCallerHearsItWasAutoAnsweredAndLocksToo() {
    val s = reduce(live(), CallEvent.RemoteMedia(id, kitchen.deviceId, true, true, autoAnswered = true)).state
    assertTrue(s.call!!.locked)
    assertEquals(s, reduce(s, CallEvent.AddPerson(den, t0 + 20_000)).state)
    assertEquals(s, reduce(s, CallEvent.MoveTo(phone, t0 + 20_000)).state)
    // A later media message without the flag never unlocks it.
    assertTrue(reduce(s, CallEvent.RemoteMedia(id, kitchen.deviceId, false, true)).state.call!!.locked)
  }

  @Test fun moveToPhoneRingsItAsAMove() {
    val step = reduce(live(), CallEvent.MoveTo(phone, t0 + 10_000))
    val ring = step.effects.single() as CallEffect.SendGroupRing
    assertTrue(ring.move)
    assertEquals(phone.deviceId, step.state.call!!.move!!.to)
    assertTrue(step.state.call!!.leg(phone.deviceId)!!.moving)
  }

  @Test fun aMoveOnlyGoesToAPhoneAndNeedsAFreeSeat() {
    val s = live()
    assertEquals(s, reduce(s, CallEvent.MoveTo(den, t0 + 10_000)).state)
    var full = reduce(s, CallEvent.RemoteJoin(id, kitchen.deviceId, den, t0)).state
    full = reduce(full, CallEvent.RemoteJoin(id, kitchen.deviceId, kids, t0)).state
    assertTrue(full.call!!.full)
    assertEquals(full, reduce(full, CallEvent.MoveTo(phone, t0 + 10_000)).state)
  }

  private fun moving(): CallState {
    var s = reduce(live(), CallEvent.MoveTo(phone, t0 + 10_000)).state
    s = reduce(s, CallEvent.Replied(id, phone.deviceId, CallReply.RINGING, null, t0 + 10_200)).state
    s = reduce(s, CallEvent.RemoteAccepted(id, phone.deviceId, t0 + 13_000)).state
    return reduce(s, CallEvent.MediaUp(id, t0 + 13_500, phone.deviceId)).state
  }

  @Test fun whenThePhoneSaysItIsInWeLeaveAndSayWhereTheCallWent() {
    val s = moving()
    assertEquals(t0 + 13_500, s.call!!.move!!.targetLiveMs)
    val step = reduce(s, CallEvent.RemoteMoved(id, phone.deviceId, t0 + 14_000))
    assertNull(step.state.call)
    val ended = step.state.ended!!
    assertEquals(CallEndReason.MOVED, ended.reason)
    assertEquals(phone, ended.movedTo)
    val ends = step.effects.filterIsInstance<CallEffect.SendEnd>()
    val toKitchen = ends.single { it.to == kitchen.deviceId }
    assertEquals(CallEndReason.HUNG_UP, toKitchen.reason)
    assertEquals(phone.deviceId, toKitchen.movedTo)
    assertTrue(ends.none { it.reason == CallEndReason.MOVED })
    assertTrue(step.effects.contains(CallEffect.StopMedia))
  }

  @Test fun aMovedSignalFromAnyoneElseIsIgnored() {
    val s = moving()
    assertEquals(s, reduce(s, CallEvent.RemoteMoved(id, kitchen.deviceId, t0 + 14_000)).state)
    assertEquals(s, reduce(s, CallEvent.RemoteMoved("ffffffffffffffff", phone.deviceId, t0 + 14_000)).state)
  }

  @Test fun withoutTheSignalWeStillLeaveOnceThePhoneHasBeenInAWhile() {
    val s = moving()
    assertNotNull(reduce(s, CallEvent.Tick(t0 + 13_500 + CallTimings.MOVE_SETTLE_MS - 1)).state.call)
    val step = reduce(s, CallEvent.Tick(t0 + 13_500 + CallTimings.MOVE_SETTLE_MS))
    assertEquals(CallEndReason.MOVED, step.state.ended!!.reason)
  }

  @Test fun ifThePhoneDoesNotPickUpTheCallStaysHere() {
    var s = reduce(live(), CallEvent.MoveTo(phone, t0 + 10_000)).state
    s = reduce(s, CallEvent.Replied(id, phone.deviceId, CallReply.DECLINED, null, t0 + 11_000)).state
    val call = s.call!!
    assertNull(call.move)
    assertTrue(call.notice!!.move)
    assertEquals(CallPhase.LIVE, call.phase)
    assertEquals("Sam’s phone didn’t pick up · the call stays here", CallAddPeople.notice(call.notice!!))
  }

  @Test fun theMoveTargetAnnouncesOnceItIsConnectedToEveryone() {
    // We are the Kitchen Portal; Sam's phone is moving its call (with Den) here.
    val ring = CallEvent.Incoming(id, phone, CallKind.CALL, true, null, 0, IncomingDecision.Ring(false), t0,
        members = listOf(CallMemberRef(den, "192.168.1.22", 8723)), move = true)
    var s = reduce(CallState(), ring).state
    assertEquals(phone.deviceId, s.call!!.move!!.from)
    val accepted = reduce(s, CallEvent.Accept(false, t0 + 1_000))
    assertTrue(accepted.effects.any { it is CallEffect.SendGo && it.to == phone.deviceId })
    assertTrue(accepted.effects.any { it is CallEffect.StartLeg && it.leg.peer == den })
    s = accepted.state
    s = reduce(s, CallEvent.MediaUp(id, t0 + 2_000, phone.deviceId)).state
    val last = reduce(s, CallEvent.MediaUp(id, t0 + 2_500, den.deviceId))
    assertEquals(listOf(CallEffect.SendMoved(id, phone.deviceId)), last.effects.filterIsInstance<CallEffect.SendMoved>())
    // Only once.
    val again = reduce(last.state, CallEvent.MediaDown(id, t0 + 3_000, den.deviceId))
    val back = reduce(again.state, CallEvent.MediaUp(id, t0 + 3_500, den.deviceId))
    assertTrue(back.effects.none { it is CallEffect.SendMoved })
    // The phone leaves, naming us: Den carries on as our first member.
    val left = reduce(back.state, CallEvent.RemoteEnded(id, phone.deviceId, CallEndReason.HUNG_UP, t0 + 4_000,
        movedTo = "11111111-1111-1111-1111-111111111111"))
    val call = left.state.call!!
    assertEquals(den, call.peer)
    assertNull(call.move)
    assertEquals(CallEndReason.MOVED, call.notice!!.reason)
    assertEquals("Sam’s phone handed the call over", CallAddPeople.notice(call.notice!!))
  }

  @Test fun onlyThePhoneTheCallCameFromMayPullItBack() {
    val ring = CallEvent.Incoming(id, phone, CallKind.CALL, true, null, 0, IncomingDecision.Ring(false), t0,
        members = listOf(CallMemberRef(den, "192.168.1.22", 8723)), move = true)
    var s = reduce(CallState(), ring).state
    s = reduce(s, CallEvent.Accept(false, t0 + 1_000)).state
    s = reduce(s, CallEvent.MediaUp(id, t0 + 2_000, phone.deviceId)).state
    s = reduce(s, CallEvent.MediaUp(id, t0 + 2_500, den.deviceId)).state
    s = reduce(s, CallEvent.RemoteEnded(id, phone.deviceId, CallEndReason.HUNG_UP, t0 + 4_000, movedTo = "x")).state
    assertEquals(phone.deviceId, s.call!!.cameFrom)
    assertEquals(s, reduce(s, CallEvent.RemotePull(id, "phfedcba9876543210", t0 + 5_000)).state)
    assertEquals(s, reduce(s, CallEvent.RemotePull("ffffffffffffffff", phone.deviceId, t0 + 5_000)).state)
    val pulled = reduce(s, CallEvent.RemotePull(id, phone.deviceId, t0 + 5_000))
    val ringBack = pulled.effects.filterIsInstance<CallEffect.SendGroupRing>().single()
    assertTrue(ringBack.move)
    assertEquals(phone.deviceId, ringBack.to.deviceId)
    assertEquals("Sam’s phone", ringBack.to.name)
    assertEquals(listOf(den), ringBack.members)
  }

  @Test fun aMemberThatMovedIsToldAsAMoveNotALeave() {
    // Sam's phone is a leg here; it moved to the Kids Portal (also in the call).
    var s = reduce(withPhoneLive(), CallEvent.RemoteJoin(id, kitchen.deviceId, kids, t0 + 20_000)).state
    s = reduce(s, CallEvent.MediaUp(id, t0 + 21_000, kids.deviceId)).state
    val step = reduce(s, CallEvent.RemoteEnded(id, phone.deviceId, CallEndReason.HUNG_UP, t0 + 22_000, movedTo = kids.deviceId))
    val call = step.state.call!!
    assertNull(call.leg(phone.deviceId))
    assertEquals("Sam’s phone moved the call to Kids", CallAddPeople.notice(call.notice!!))
    assertTrue(step.effects.contains(CallEffect.ClosePeer(phone.deviceId)))
  }

  @Test fun aMoveRingNeverAnswersByItself() {
    val ring = CallEvent.Incoming(id, phone, CallKind.CALL, true, null, 0, IncomingDecision.AutoAnswer, t0, move = true)
    val call = reduce(CallState(), ring).state.call!!
    assertNull(call.autoAnswerAtMs)
    assertNotNull(call.move)
  }

  @Test fun atNightAMoveShowsSilentlyInsteadOfBeingTurnedAway() {
    val base = IncomingContext(CallKind.CALL, CallWhoCanCall.HOME, quiet = true, quietMode = CallQuietMode.BLOCK, dropInsOn = false,
        dropInFromCaller = DropInPolicy.ASK, busy = false, fromPhone = true)
    assertEquals(IncomingDecision.Reject(CallReply.QUIET), CallPolicy.decide(base))
    assertEquals(IncomingDecision.Ring(silent = true), CallPolicy.decide(base.copy(move = true)))
    // Phones off still wins over a move.
    assertEquals(IncomingDecision.Reject(CallReply.CALLS_OFF), CallPolicy.decide(base.copy(move = true, phonesAllowed = false)))
  }

  @Test fun hangingUpMidMoveCancelsThePhonesRing() {
    val s = reduce(live(), CallEvent.MoveTo(phone, t0 + 10_000)).state
    val step = reduce(s, CallEvent.HangUp(t0 + 11_000))
    val toPhone = step.effects.filterIsInstance<CallEffect.SendEnd>().single { it.to == phone.deviceId }
    assertEquals(CallEndReason.CANCELLED, toPhone.reason)
    assertEquals(CallEndReason.HUNG_UP, step.state.ended!!.reason)
  }
}
