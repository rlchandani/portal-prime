/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge transitions of [CallMachine] the flow tests in [CallMachineTest] don't reach: events in the
 * wrong phase or direction, stale ids, the group-ring invite rules, and the model's derived values.
 */
class CallMachineEdgeTest {
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val office = CallContact("44444444-4444-4444-4444-444444444444", "Office")
  private val attic = CallContact("55555555-5555-5555-5555-555555555555", "Attic")
  private val phone = CallContact("66666666-6666-6666-6666-666666666666", "Priya's phone", kind = CallContact.Kind.PHONE)
  private val phone2 = CallContact("77777777-7777-7777-7777-777777777777", "Walt's phone", kind = CallContact.Kind.PHONE)
  private val id = "0123456789abcdef"
  private val t0 = 1_000_000L

  private fun reduce(s: CallState, e: CallEvent) = CallMachine.reduce(s, e)
  private fun dial(kind: CallKind = CallKind.CALL, video: Boolean = true) =
      reduce(CallState(), CallEvent.Dial(id, kitchen, kind, video, t0)).state
  private fun ring(
      decision: IncomingDecision = IncomingDecision.Ring(false), peer: CallContact = kitchen,
      kind: CallKind = CallKind.CALL, members: List<CallMemberRef> = emptyList(), move: Boolean = false,
      groupRing: Boolean = members.isNotEmpty(),
  ) = reduce(CallState(), CallEvent.Incoming(id, peer, kind, true, "192.168.1.20", 8723, decision, t0,
      members = members, groupRing = groupRing, move = move))
  private fun ref(c: CallContact) = CallMemberRef(c, "192.168.1.${c.name.length}", 8723)

  /** Outgoing call placed, accepted by the callee and media up. */
  private fun liveOut(kind: CallKind = CallKind.CALL): CallState {
    val s = reduce(dial(kind), CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 500)).state
    return reduce(s, CallEvent.MediaUp(id, t0 + 900)).state
  }

  private fun assertUnchanged(s: CallState, e: CallEvent) {
    val step = reduce(s, e)
    assertEquals(s, step.state)
    assertTrue("no effects for $e", step.effects.isEmpty())
  }

  // ---- Phase and model derived values -------------------------------------------------------

  @Test fun phaseFlagsPartitionTheLifecycle() {
    assertEquals(setOf(CallPhase.LIVE, CallPhase.RECONNECTING), CallPhase.entries.filter { it.connected }.toSet())
    assertEquals(setOf(CallPhase.DIALING, CallPhase.RINGING, CallPhase.COUNTDOWN),
        CallPhase.entries.filter { it.beforeAnswer }.toSet())
    assertFalse(CallPhase.CONNECTING.connected || CallPhase.CONNECTING.beforeAnswer)
  }

  @Test fun activeCallCountsMembersPhonesAndLocks() {
    val solo = dial().call!!
    assertEquals(listOf(kitchen), solo.members)
    assertEquals(2, solo.people)
    assertFalse(solo.group || solo.full || solo.locked)
    assertEquals(0, solo.phones)
    val group = solo.copy(legs = listOf(CallLeg(den, LegPhase.RINGING, t0, offerer = false),
        CallLeg(phone, LegPhase.RINGING, t0, offerer = false)))
    assertEquals(listOf(kitchen, den, phone), group.members)
    assertEquals(CallGroup.MAX_PEOPLE, group.people)
    assertTrue(group.group && group.full)
    assertEquals(1, group.phones)
    assertEquals(den, group.leg(den.deviceId)?.peer)
    assertNull(group.leg(office.deviceId))
    assertTrue(group.isMember(kitchen.deviceId) && group.isMember(phone.deviceId))
    assertFalse(group.isMember(office.deviceId))
    assertTrue(solo.copy(autoAnswered = true).locked)
    assertTrue(solo.copy(remoteAutoAnswered = true).locked)
  }

  @Test fun anEndedCallNeverHasANegativeDuration() {
    val never = EndedCall(id, kitchen, CallDirection.OUTGOING, CallKind.CALL, CallEndReason.HUNG_UP, null, t0)
    assertEquals(0L, never.durationMs)
    val skewed = never.copy(connectedMs = t0 + 5_000)
    assertEquals("a clock step back clamps to zero", 0L, skewed.durationMs)
    assertTrue(CallState(call = dial().call).busy)
    assertFalse(CallState().busy)
  }

  // ---- Events that don't apply ---------------------------------------------------------------

  @Test fun eventsWithoutACallChangeNothing() {
    val idle = CallState()
    for (e in listOf(CallEvent.Tick(t0), CallEvent.Accept(false, t0), CallEvent.HangUp(t0), CallEvent.ToggleMic,
        CallEvent.MediaUp(id, t0), CallEvent.KeepGoing(t0))) assertUnchanged(idle, e)
  }

  @Test fun anOutgoingCallCannotBeAcceptedOrDeclined() {
    val s = dial()
    assertUnchanged(s, CallEvent.Accept(false, t0 + 10))
    assertUnchanged(s, CallEvent.Decline(t0 + 10))
  }

  @Test fun anAnsweredIncomingCallCannotBeAcceptedOrDeclinedAgain() {
    val connecting = reduce(ring().state, CallEvent.Accept(false, t0 + 100)).state
    assertEquals(CallPhase.CONNECTING, connecting.call!!.phase)
    assertUnchanged(connecting, CallEvent.Accept(true, t0 + 200))
    assertUnchanged(connecting, CallEvent.Decline(t0 + 200))
  }

  @Test fun repliesToAnIncomingOrAnsweredCallAreIgnored() {
    val incoming = ring().state
    assertUnchanged(incoming, CallEvent.Replied(id, kitchen.deviceId, CallReply.BUSY, null, t0 + 10))
    val live = liveOut()
    assertUnchanged(live, CallEvent.Replied(id, kitchen.deviceId, CallReply.DECLINED, null, t0 + 10))
  }

  @Test fun aRepeatedRingingOrCountdownReplyChangesNothing() {
    val ringing = reduce(dial(), CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0 + 100)).state
    assertUnchanged(ringing, CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0 + 900))
    val countdown = reduce(dial(CallKind.DROP_IN), CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0 + 100)).state
    assertUnchanged(countdown, CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0 + 900))
  }

  @Test fun aCountdownReplyAfterRingingDoesNotReopenAnAlreadyOpenPreview() {
    // A video call's preview is already open; the switch to the countdown must not ask again.
    val ringing = reduce(dial(), CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0 + 100)).state
    val step = reduce(ringing, CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0 + 200))
    assertEquals(CallPhase.COUNTDOWN, step.state.call!!.phase)
    assertTrue(step.effects.isEmpty())
    // An audio drop-in has no preview to open at all.
    val audio = reduce(dial(CallKind.DROP_IN, video = false),
        CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0 + 100))
    assertFalse(audio.state.call!!.cameraOn)
    assertTrue(audio.effects.isEmpty())
  }

  @Test fun everyRefusalEndsWithItsOwnReasonAndQuietKeepsTheEndTime() {
    val cases = mapOf(CallReply.BUSY to CallEndReason.BUSY, CallReply.CALLS_OFF to CallEndReason.CALLS_OFF,
        CallReply.DROP_INS_OFF to CallEndReason.DROP_INS_OFF, CallReply.DECLINED to CallEndReason.DECLINED,
        CallReply.QUIET to CallEndReason.QUIET)
    for ((reply, reason) in cases) {
      val ended = reduce(dial(), CallEvent.Replied(id, kitchen.deviceId, reply, 7 * 60, t0 + 50)).state.ended!!
      assertEquals(reason, ended.reason)
      assertEquals(if (reply == CallReply.QUIET) 7 * 60 else null, ended.quietEndMin)
    }
  }

  @Test fun aRemoteAcceptForAnIncomingOrConnectedCallIsIgnored() {
    assertUnchanged(ring().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 10))
    assertUnchanged(liveOut(), CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 10))
    assertUnchanged(dial(), CallEvent.RemoteAccepted("ffffffffffffffff", kitchen.deviceId, t0 + 10))
  }

  @Test fun mediaEventsForAnotherCallOrPhaseAreIgnored() {
    val ringing = dial()
    assertUnchanged(ringing, CallEvent.MediaUp(id, t0 + 10))
    assertUnchanged(ringing, CallEvent.MediaDown(id, t0 + 10))
    val live = liveOut()
    assertUnchanged(live, CallEvent.MediaUp("ffffffffffffffff", t0 + 10))
    assertUnchanged(live, CallEvent.MediaUp(id, t0 + 10))
    assertUnchanged(live, CallEvent.MediaDown("ffffffffffffffff", t0 + 10))
    assertUnchanged(live, CallEvent.MediaFailed("ffffffffffffffff", CallEndReason.FAILED, t0 + 10))
  }

  @Test fun mediaEventsAboutAClosedLegAreStale() {
    val live = liveOut()
    assertUnchanged(live, CallEvent.MediaDown(id, t0 + 10, peer = den.deviceId))
    assertUnchanged(live, CallEvent.MediaFailed(id, CallEndReason.FAILED, t0 + 10, peer = den.deviceId))
    val down = reduce(live, CallEvent.MediaDown(id, t0 + 10, peer = kitchen.deviceId))
    assertEquals(CallPhase.RECONNECTING, down.state.call!!.phase)
  }

  @Test fun recoveringKeepsTheFirstConnectTimeAndTheCap() {
    val live = liveOut(CallKind.DROP_IN)
    val call = live.call!!
    assertEquals(t0 + 900, call.connectedMs)
    assertEquals(t0 + 900 + CallTimings.DROP_IN_CAP_MS, call.capAtMs)
    val down = reduce(live, CallEvent.MediaDown(id, t0 + 5_000)).state
    val back = reduce(down, CallEvent.MediaUp(id, t0 + 7_000)).state.call!!
    assertEquals(CallPhase.LIVE, back.phase)
    assertEquals(t0 + 7_000, back.phaseSinceMs)
    assertEquals("duration counts from the first connect", t0 + 900, back.connectedMs)
    assertEquals(call.capAtMs, back.capAtMs)
  }

  @Test fun keepGoingNeedsAConnectedDropIn() {
    val dropIn = reduce(dial(CallKind.DROP_IN), CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 100)).state
    // Connecting: no cap yet, nothing to extend.
    assertUnchanged(dropIn, CallEvent.KeepGoing(t0 + 200))
    val capped = dropIn.copy(call = dropIn.call!!.copy(capAtMs = t0 + 999))
    assertUnchanged(capped, CallEvent.KeepGoing(t0 + 200))
  }

  @Test fun keepGoingAfterTheCapExtendsFromNow() {
    val live = liveOut(CallKind.DROP_IN)
    val late = live.call!!.capAtMs!! + 1_000
    val step = reduce(live, CallEvent.KeepGoing(late))
    assertEquals(late + CallTimings.DROP_IN_CAP_MS, step.state.call!!.capAtMs)
    assertEquals(listOf(CallEffect.SendKeep(id, kitchen.deviceId, late + CallTimings.DROP_IN_CAP_MS)), step.effects)
  }

  @Test fun remoteKeepGoingIsIgnoredWithoutACapOrFromAStranger() {
    val live = liveOut()
    assertUnchanged(live, CallEvent.RemoteKeepGoing(id, kitchen.deviceId, t0 + 99_999))
    val dropIn = liveOut(CallKind.DROP_IN)
    assertUnchanged(dropIn, CallEvent.RemoteKeepGoing(id, den.deviceId, t0 + 99_999_999))
    val earlier = reduce(dropIn, CallEvent.RemoteKeepGoing(id, kitchen.deviceId, t0))
    assertEquals("an earlier cap never shortens the call", dropIn.call!!.capAtMs, earlier.state.call!!.capAtMs)
  }

  @Test fun remoteMediaFromAStrangerIsIgnoredAndAutoAnswerSticks() {
    val live = liveOut()
    assertUnchanged(live, CallEvent.RemoteMedia(id, den.deviceId, cameraOn = false, micOn = false))
    val locked = reduce(live, CallEvent.RemoteMedia(id, kitchen.deviceId, true, true, autoAnswered = true)).state
    assertTrue(locked.call!!.remoteAutoAnswered)
    val later = reduce(locked, CallEvent.RemoteMedia(id, kitchen.deviceId, false, true, autoAnswered = false)).state
    assertTrue("a later state without the flag never unlocks the call", later.call!!.remoteAutoAnswered)
    assertFalse(later.call!!.remoteCameraOn)
  }

  @Test fun remoteEndForAnotherCallIsIgnored() {
    assertUnchanged(liveOut(), CallEvent.RemoteEnded("ffffffffffffffff", kitchen.deviceId, CallEndReason.HUNG_UP, t0))
    assertUnchanged(liveOut(), CallEvent.RemoteEnded(id, den.deviceId, CallEndReason.HUNG_UP, t0))
  }

  @Test fun remoteEndReasonsOutsideTheKeptSetReadAsHungUp() {
    for (reason in listOf(CallEndReason.QUIET, CallEndReason.BUSY, CallEndReason.DECLINED, CallEndReason.OFFLINE)) {
      val ended = reduce(liveOut(), CallEvent.RemoteEnded(id, kitchen.deviceId, reason, t0 + 2_000)).state.ended!!
      assertEquals("remote $reason", CallEndReason.HUNG_UP, ended.reason)
    }
    for (reason in listOf(CallEndReason.CAP_REACHED, CallEndReason.NETWORK_LOST, CallEndReason.FAILED)) {
      val ended = reduce(liveOut(), CallEvent.RemoteEnded(id, kitchen.deviceId, reason, t0 + 2_000)).state.ended!!
      assertEquals(reason, ended.reason)
    }
  }

  @Test fun hangingUpALiveCallIsHungUpAndLeavingIsLeftButBothSayHungUp() {
    val hung = reduce(liveOut(), CallEvent.HangUp(t0 + 3_000))
    assertEquals(CallEndReason.HUNG_UP, hung.state.ended!!.reason)
    assertEquals(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.HUNG_UP), hung.effects.first())
    val left = reduce(liveOut(), CallEvent.Left(t0 + 3_000))
    assertEquals(CallEndReason.LEFT, left.state.ended!!.reason)
    assertEquals(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.LEFT), left.effects.first())
    assertEquals(2_100L, left.state.ended!!.durationMs)
  }

  @Test fun leavingDuringAnIncomingRingDeclinesIt() {
    val step = reduce(ring().state, CallEvent.Left(t0 + 100))
    assertEquals(CallEndReason.DECLINED, step.state.ended!!.reason)
    assertEquals(CallEffect.SendReply(id, kitchen.deviceId, CallReply.DECLINED), step.effects.first())
  }

  // ---- Timers ------------------------------------------------------------------------------

  @Test fun anOutgoingCountdownThatNeverConnectsFails() {
    val countdown = reduce(dial(CallKind.DROP_IN), CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0)).state
    val edge = t0 + CallTimings.DROP_IN_COUNTDOWN_MS + CallTimings.CONNECT_MS
    assertNotNull(reduce(countdown, CallEvent.Tick(edge - 1)).state.call)
    val step = reduce(countdown, CallEvent.Tick(edge))
    assertEquals(CallEndReason.FAILED, step.state.ended!!.reason)
    assertEquals(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.FAILED), step.effects.first())
  }

  @Test fun anIncomingCountdownWaitsItsFullThreeSeconds() {
    val s = ring(IncomingDecision.Countdown, kind = CallKind.DROP_IN).state
    assertUnchanged(s, CallEvent.Tick(t0 + CallTimings.DROP_IN_COUNTDOWN_MS - 1))
    assertEquals(CallPhase.CONNECTING, reduce(s, CallEvent.Tick(t0 + CallTimings.DROP_IN_COUNTDOWN_MS)).state.call!!.phase)
  }

  @Test fun aLiveCallWithoutACapNeverEndsByItself() {
    assertUnchanged(liveOut(), CallEvent.Tick(t0 + 10 * CallTimings.DROP_IN_CAP_MS))
    val reconnecting = reduce(liveOut(), CallEvent.MediaDown(id, t0 + 1_000)).state
    assertUnchanged(reconnecting, CallEvent.Tick(t0 + 1_000 + CallTimings.RECONNECT_MS - 1))
  }

  // ---- Auto-answer and group rings ---------------------------------------------------------

  @Test fun autoAnswerCountsDownThenConnectsByItselfAndSaysSo() {
    val s = ring(IncomingDecision.AutoAnswer).state
    val call = s.call!!
    assertEquals(t0 + CallTimings.AUTO_ANSWER_MS, call.autoAnswerAtMs)
    assertUnchanged(s, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS - 1))
    val step = reduce(s, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS))
    val next = step.state.call!!
    assertEquals(CallPhase.CONNECTING, next.phase)
    assertTrue(next.autoAnswered && next.locked)
    assertNull(next.autoAnswerAtMs)
    assertTrue(CallEffect.SendMediaState(id, kitchen.deviceId, true, true, autoAnswered = true) in step.effects)
  }

  @Test fun anAutoAnsweredDropInIsTreatedAsACall() {
    val call = ring(IncomingDecision.AutoAnswer, kind = CallKind.DROP_IN).state.call!!
    assertEquals(CallKind.CALL, call.kind)
  }

  @Test fun aGroupRingOrAMoveNeverAutoAnswers() {
    val group = ring(IncomingDecision.AutoAnswer, members = listOf(ref(den)))
    assertNull(group.state.call!!.autoAnswerAtMs)
    assertTrue(CallEffect.StartRing(false) in group.effects)
    val named = ring(IncomingDecision.AutoAnswer, groupRing = true)
    assertNull("a group ring this Portal won't join still never auto-answers", named.state.call!!.autoAnswerAtMs)
    val move = ring(IncomingDecision.AutoAnswer, move = true)
    assertNull(move.state.call!!.autoAnswerAtMs)
    assertEquals(CallMove(from = kitchen.deviceId), move.state.call!!.move)
  }

  @Test fun onlyACallCanBeMovedHere() {
    assertEquals(CallMove(from = kitchen.deviceId), ring(move = true).state.call!!.move)
    assertNull(ring(move = true, kind = CallKind.DROP_IN).state.call!!.move)
  }

  @Test fun groupRingInvitesDropTheCallerDuplicatesAndExtraPhones() {
    val members = listOf(ref(kitchen), ref(den), ref(den), ref(phone), ref(phone2), ref(office), ref(attic))
    val invited = ring(members = members).state.call!!.invited.map { it.contact }
    assertEquals("caller removed, one phone, capped at the leg limit", listOf(den, phone), invited)
    assertTrue(invited.size <= CallGroup.MAX_LEGS)
  }

  @Test fun aPhoneCallerLeavesNoRoomForAnotherPhone() {
    val invited = ring(peer = phone, members = listOf(ref(phone2), ref(den))).state.call!!.invited.map { it.contact }
    assertEquals(listOf(den), invited)
  }

  @Test fun aDropInNeverCarriesGroupInvites() {
    val call = ring(kind = CallKind.DROP_IN, members = listOf(ref(den))).state.call!!
    assertTrue(call.invited.isEmpty())
  }

  @Test fun acceptingAPhonesCallTellsThePhoneToGo() {
    val step = reduce(ring(peer = phone).state, CallEvent.Accept(audioOnly = true, now = t0 + 100))
    assertTrue(CallEffect.SendGo(id, phone.deviceId) in step.effects)
    assertFalse(step.effects.any { it is CallEffect.StartMedia })
    assertFalse("audio only clears the camera", step.state.call!!.cameraOn)
  }

  @Test fun aRepeatedRingAfterAnsweringIsNotAnsweredAgain() {
    val connecting = reduce(ring().state, CallEvent.Accept(false, t0 + 100)).state
    val again = reduce(connecting, CallEvent.Incoming(id, kitchen, CallKind.CALL, true, null, 0,
        IncomingDecision.Ring(false), t0 + 200))
    assertEquals(connecting, again.state)
    assertTrue(again.effects.isEmpty())
  }

  @Test fun aRepeatedDropInRingRepliesCountdown() {
    val s = ring(IncomingDecision.Countdown, kind = CallKind.DROP_IN).state
    val again = reduce(s, CallEvent.Incoming(id, kitchen, CallKind.DROP_IN, true, null, 0, IncomingDecision.Countdown, t0 + 50))
    assertEquals(listOf(CallEffect.SendReply(id, kitchen.deviceId, CallReply.COUNTDOWN)), again.effects)
  }

  @Test fun anOutgoingCallWithTheSameIdTreatsARingAsBusy() {
    val s = dial()
    val step = reduce(s, CallEvent.Incoming(id, kitchen, CallKind.CALL, true, null, 0, IncomingDecision.Ring(false), t0 + 50))
    assertEquals(s, step.state)
    assertEquals(CallEffect.SendReply(id, kitchen.deviceId, CallReply.BUSY), step.effects.first())
  }

  // ---- Media toggles -----------------------------------------------------------------------

  @Test fun turningTheCameraBackOnClearsItsProblem() {
    val live = liveOut()
    val broken = reduce(live, CallEvent.CameraProblem(CameraIssue.entries.first())).state
    assertNotNull(broken.call!!.cameraIssue)
    val on = reduce(broken, CallEvent.ToggleCamera)
    assertTrue(on.state.call!!.cameraOn)
    assertNull(on.state.call!!.cameraIssue)
    assertTrue(CallEffect.SetCamera(true) in on.effects)
    val flagged = on.state.copy(call = on.state.call!!.copy(cameraIssue = CameraIssue.entries.first()))
    val off = reduce(flagged, CallEvent.ToggleCamera)
    assertFalse(off.state.call!!.cameraOn)
    assertEquals("turning it off keeps the reason", CameraIssue.entries.first(), off.state.call!!.cameraIssue)
  }

  @Test fun togglingWhileConnectingTellsThePeer() {
    val connecting = reduce(dial(), CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 100)).state
    val step = reduce(connecting, CallEvent.ToggleCamera)
    assertTrue(CallEffect.SetCamera(false) in step.effects)
    assertTrue(step.effects.any { it is CallEffect.SendMediaState })
  }

  @Test fun theMicToggleAlwaysSaysItsState() {
    val step = reduce(dial(), CallEvent.ToggleMic)
    assertFalse(step.state.call!!.micOn)
    assertEquals(CallEffect.SetMic(false), step.effects.first())
    assertEquals(CallEffect.SendMediaState(id, kitchen.deviceId, true, false, false), step.effects[1])
  }

  // ---- end() -------------------------------------------------------------------------------

  @Test fun endNamesTheMoveTargetAndSendsHungUpOnTheWire() {
    val call = liveOut().call!!.copy(move = CallMove(to = den.deviceId),
        legs = listOf(CallLeg(den, LegPhase.LIVE, t0, offerer = false)))
    val step = CallMachine.end(CallState(call = call), call, CallEndReason.MOVED, t0 + 9_000)
    assertEquals(CallEndReason.MOVED, step.state.ended!!.reason)
    assertEquals(den, step.state.ended!!.movedTo)
    assertNull(step.state.call)
    assertEquals(listOf(CallEffect.StopRing, CallEffect.StopMedia), step.effects.filter {
      it == CallEffect.StopRing || it == CallEffect.StopMedia })
    assertEquals(CallEffect.AnnouncePresence, step.effects.last())
  }

  @Test fun endOtherwiseNamesNoMoveTarget() {
    val call = liveOut().call!!.copy(move = CallMove(to = den.deviceId))
    val ended = CallMachine.end(CallState(call = call), call, CallEndReason.HUNG_UP, t0 + 9_000).state.ended!!
    assertNull(ended.movedTo)
  }
}
