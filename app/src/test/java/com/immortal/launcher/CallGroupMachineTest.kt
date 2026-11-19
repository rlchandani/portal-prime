/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Group calls: add person, the mesh join, members leaving, hand-over and the cap of four. */
class CallGroupMachineTest {
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val kids = CallContact("44444444-4444-4444-4444-444444444444", "Kids")
  private val office = CallContact("55555555-5555-5555-5555-555555555555", "Office")
  private val phone = CallContact("ph0123456789abcdef", "Sam’s phone", kind = CallContact.Kind.PHONE)
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

  private fun withDenLive(): CallState {
    var s = reduce(live(), CallEvent.AddPerson(den, t0 + 10_000)).state
    s = reduce(s, CallEvent.Replied(id, den.deviceId, CallReply.RINGING, null, t0 + 10_200)).state
    s = reduce(s, CallEvent.RemoteAccepted(id, den.deviceId, t0 + 12_000)).state
    return reduce(s, CallEvent.MediaUp(id, t0 + 12_500, den.deviceId)).state
  }

  @Test fun addPersonRingsThemWithTheMemberList() {
    val step = reduce(live(), CallEvent.AddPerson(den, t0 + 10_000))
    val call = step.state.call!!
    assertEquals(3, call.people)
    assertEquals(LegPhase.RINGING, call.leg(den.deviceId)!!.phase)
    val ring = step.effects.single() as CallEffect.SendGroupRing
    assertEquals(den, ring.to)
    assertEquals(listOf(kitchen), ring.members)
  }

  @Test fun addPersonOnlyWhileLiveForCallsAndNeverPhonesOrMembers() {
    val ringing = reduce(CallState(), CallEvent.Dial(id, kitchen, CallKind.CALL, true, t0)).state
    assertEquals(ringing, reduce(ringing, CallEvent.AddPerson(den, t0 + 1)).state)
    val s = live()
    assertEquals(s, reduce(s, CallEvent.AddPerson(kitchen, t0 + 1)).state)
    assertEquals(s, reduce(s, CallEvent.AddPerson(phone, t0 + 1)).state)
  }

  @Test fun dropInsNeverBecomeGroups() {
    var s = reduce(CallState(), CallEvent.Dial(id, kitchen, CallKind.DROP_IN, true, t0)).state
    s = reduce(s, CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0 + 100)).state
    s = reduce(s, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 3_000)).state
    s = reduce(s, CallEvent.MediaUp(id, t0 + 3_500, kitchen.deviceId)).state
    assertTrue(reduce(s, CallEvent.AddPerson(den, t0 + 4_000)).state.call!!.legs.isEmpty())
  }

  @Test fun theCallHoldsFourAndThenIsFull() {
    var s = withDenLive()
    s = reduce(s, CallEvent.AddPerson(kids, t0 + 20_000)).state
    val call = s.call!!
    assertEquals(4, call.people)
    assertTrue(call.full)
    val more = reduce(s, CallEvent.AddPerson(office, t0 + 21_000))
    assertEquals(s, more.state)
    assertTrue(more.effects.isEmpty())
  }

  @Test fun theNewcomersOfferMakesItJoinAndTheInviterVouchesToOthers() {
    var s = withDenLive()
    s = reduce(s, CallEvent.AddPerson(kids, t0 + 20_000)).state
    val step = reduce(s, CallEvent.RemoteAccepted(id, kids.deviceId, t0 + 22_000))
    assertEquals(LegPhase.JOINING, step.state.call!!.leg(kids.deviceId)!!.phase)
    val joins = step.effects.filterIsInstance<CallEffect.SendJoin>()
    assertEquals(setOf(kitchen.deviceId, den.deviceId), joins.map { it.to }.toSet())
    assertTrue(joins.all { it.newcomer == kids })
    assertTrue(step.effects.any { it is CallEffect.SendMediaState && it.to == kids.deviceId })
  }

  @Test fun declinedQuietBusyAndOfflineInviteesLeaveATellingNotice() {
    for ((reply, reason) in listOf(CallReply.DECLINED to CallEndReason.DECLINED, CallReply.QUIET to CallEndReason.QUIET,
        CallReply.BUSY to CallEndReason.BUSY, CallReply.CALLS_OFF to CallEndReason.CALLS_OFF)) {
      val s = reduce(live(), CallEvent.AddPerson(den, t0 + 10_000)).state
      val step = reduce(s, CallEvent.Replied(id, den.deviceId, reply, null, t0 + 10_300))
      val call = step.state.call!!
      assertTrue(call.legs.isEmpty())
      assertEquals(CallNotice("Den", reason, t0 + 10_300), call.notice)
      assertEquals(CallPhase.LIVE, call.phase)
    }
    val silent = reduce(live(), CallEvent.AddPerson(den, t0 + 10_000)).state
    val offline = reduce(silent, CallEvent.Tick(t0 + 10_000 + CallTimings.DIAL_ACK_MS))
    assertEquals(CallEndReason.OFFLINE, offline.state.call!!.notice!!.reason)
  }

  @Test fun anUnansweredInviteStopsRingingAfterTheRingTime() {
    var s = reduce(live(), CallEvent.AddPerson(den, t0 + 10_000)).state
    s = reduce(s, CallEvent.Replied(id, den.deviceId, CallReply.RINGING, null, t0 + 10_200)).state
    val step = reduce(s, CallEvent.Tick(t0 + 10_000 + CallTimings.RING_MS))
    assertTrue(step.state.call!!.legs.isEmpty())
    assertTrue(CallEffect.SendEnd(id, den.deviceId, CallEndReason.NO_ANSWER) in step.effects)
    assertFalse("a ringing invitee has no peer connection", step.effects.any { it is CallEffect.ClosePeer })
  }

  @Test fun aMemberVouchedByAConnectedMemberJoinsAndOthersAreIgnored() {
    val s = live()
    val vouched = reduce(s, CallEvent.RemoteJoin(id, kitchen.deviceId, den, t0 + 5_000)).state.call!!
    assertEquals(LegPhase.JOINING, vouched.leg(den.deviceId)!!.phase)
    assertFalse(vouched.leg(den.deviceId)!!.offerer)
    assertEquals(s, reduce(s, CallEvent.RemoteJoin(id, office.deviceId, den, t0 + 5_000)).state)
    assertEquals(s, reduce(s, CallEvent.RemoteJoin("fedcba9876543210", kitchen.deviceId, den, t0 + 5_000)).state)
    assertEquals(s, reduce(s, CallEvent.RemoteJoin(id, kitchen.deviceId, phone, t0 + 5_000)).state)
  }

  @Test fun joiningTimesOutAndClosesItsPeer() {
    val s = reduce(live(), CallEvent.RemoteJoin(id, kitchen.deviceId, den, t0 + 5_000)).state
    val step = reduce(s, CallEvent.Tick(t0 + 5_000 + CallTimings.CONNECT_MS))
    assertTrue(step.state.call!!.legs.isEmpty())
    assertTrue(CallEffect.ClosePeer(den.deviceId) in step.effects)
    assertTrue(CallEffect.SendEnd(id, den.deviceId, CallEndReason.FAILED) in step.effects)
  }

  @Test fun aGroupRingIsJoinedOnAcceptByOfferingToEveryMember() {
    val members = listOf(CallMemberRef(kitchen, "192.168.1.20", 8723), CallMemberRef(den, "192.168.1.30", 8723))
    var s = reduce(CallState(), CallEvent.Incoming(id, kitchen, CallKind.CALL, true, "192.168.1.20", 8723,
        IncomingDecision.Ring(false), t0, members)).state
    assertEquals(listOf(CallMemberRef(den, "192.168.1.30", 8723)), s.call!!.invited)
    val step = reduce(s, CallEvent.Accept(audioOnly = false, now = t0 + 3_000))
    s = step.state
    val leg = s.call!!.leg(den.deviceId)!!
    assertTrue(leg.offerer)
    assertEquals(LegPhase.JOINING, leg.phase)
    assertTrue(step.effects.any { it is CallEffect.StartMedia })
    assertEquals(CallEffect.StartLeg(id, leg), step.effects.filterIsInstance<CallEffect.StartLeg>().single())
    assertTrue(s.call!!.invited.isEmpty())
  }

  @Test fun aMemberHangingUpLeavesTheOthersInTheCall() {
    val s = withDenLive()
    val step = reduce(s, CallEvent.RemoteEnded(id, den.deviceId, CallEndReason.HUNG_UP, t0 + 30_000))
    val call = step.state.call!!
    assertEquals(kitchen, call.peer)
    assertTrue(call.legs.isEmpty())
    assertEquals(CallNotice("Den", CallEndReason.HUNG_UP, t0 + 30_000), call.notice)
    assertTrue(CallEffect.ClosePeer(den.deviceId) in step.effects)
    assertFalse(step.effects.any { it is CallEffect.StopMedia })
  }

  @Test fun whenTheFirstMemberLeavesTheNextOneTakesOver() {
    val s = withDenLive()
    val step = reduce(s, CallEvent.RemoteEnded(id, kitchen.deviceId, CallEndReason.HUNG_UP, t0 + 30_000))
    val call = step.state.call!!
    assertEquals(den, call.peer)
    assertEquals(CallPhase.LIVE, call.phase)
    assertTrue(call.legs.isEmpty())
    assertTrue(CallEffect.ClosePeer(kitchen.deviceId) in step.effects)
    assertFalse(step.effects.any { it is CallEffect.StopMedia })
    assertNotNull("the call carries on, its start time kept", call.connectedMs)
  }

  @Test fun whenTheOnlyOthersAreStillRingingTheCallEndsAndTheirRingsStop() {
    val s = reduce(live(), CallEvent.AddPerson(den, t0 + 10_000)).state
    val step = reduce(s, CallEvent.RemoteEnded(id, kitchen.deviceId, CallEndReason.HUNG_UP, t0 + 11_000))
    assertNull(step.state.call)
    assertTrue(CallEffect.SendEnd(id, den.deviceId, CallEndReason.CANCELLED) in step.effects)
    assertTrue(CallEffect.StopMedia in step.effects)
  }

  @Test fun hangingUpTellsEveryMember() {
    val step = reduce(withDenLive(), CallEvent.HangUp(t0 + 40_000))
    assertNull(step.state.call)
    assertTrue(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.HUNG_UP) in step.effects)
    assertTrue(CallEffect.SendEnd(id, den.deviceId, CallEndReason.HUNG_UP) in step.effects)
    assertTrue(CallEffect.StopMedia in step.effects)
  }

  @Test fun leavingTheScreenTellsEveryMemberItWasAHangUp() {
    val step = reduce(withDenLive(), CallEvent.Left(t0 + 40_000))
    assertTrue(CallEffect.SendEnd(id, den.deviceId, CallEndReason.HUNG_UP) in step.effects)
  }

  @Test fun muteAndCameraReachEveryConnectedMember() {
    var s = withDenLive()
    s = reduce(s, CallEvent.AddPerson(kids, t0 + 20_000)).state
    val mic = reduce(s, CallEvent.ToggleMic)
    val to = mic.effects.filterIsInstance<CallEffect.SendMediaState>().map { it.to }.toSet()
    assertEquals("not the still-ringing invitee", setOf(kitchen.deviceId, den.deviceId), to)
    val cam = reduce(s, CallEvent.ToggleCamera)
    assertEquals(setOf(kitchen.deviceId, den.deviceId), cam.effects.filterIsInstance<CallEffect.SendMediaState>().map { it.to }.toSet())
    val shutter = reduce(s, CallEvent.CameraProblem(CameraIssue.SHUTTER))
    assertEquals(2, shutter.effects.filterIsInstance<CallEffect.SendMediaState>().size)
  }

  @Test fun legMediaEventsMoveOnlyThatLeg() {
    val s = withDenLive()
    val down = reduce(s, CallEvent.MediaDown(id, t0 + 50_000, den.deviceId)).state.call!!
    assertEquals(LegPhase.RECONNECTING, down.leg(den.deviceId)!!.phase)
    assertEquals(CallPhase.LIVE, down.phase)
    val gone = reduce(CallState(call = down), CallEvent.Tick(t0 + 50_000 + CallTimings.RECONNECT_MS))
    assertTrue(gone.state.call!!.legs.isEmpty())
    assertTrue(CallEffect.ClosePeer(den.deviceId) in gone.effects)
    val back = reduce(CallState(call = down), CallEvent.MediaUp(id, t0 + 51_000, den.deviceId)).state.call!!
    assertEquals(LegPhase.LIVE, back.leg(den.deviceId)!!.phase)
  }

  @Test fun aFailedLegIsDroppedNotTheCall() {
    val step = reduce(withDenLive(), CallEvent.MediaFailed(id, CallEndReason.FAILED, t0 + 50_000, den.deviceId))
    assertNotNull(step.state.call)
    assertTrue(step.state.call!!.legs.isEmpty())
  }

  @Test fun aFailedFirstMemberHandsOverWhenOthersAreConnected() {
    val step = reduce(withDenLive(), CallEvent.MediaFailed(id, CallEndReason.FAILED, t0 + 50_000, kitchen.deviceId))
    assertEquals(den, step.state.call!!.peer)
    assertTrue(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.FAILED) in step.effects)
  }

  @Test fun theFirstMembersLostWifiHandsOverAfterTheReconnectWait() {
    var s = withDenLive()
    s = reduce(s, CallEvent.MediaDown(id, t0 + 50_000, kitchen.deviceId)).state
    assertEquals(CallPhase.RECONNECTING, s.call!!.phase)
    val step = reduce(s, CallEvent.Tick(t0 + 50_000 + CallTimings.RECONNECT_MS))
    assertEquals(den, step.state.call!!.peer)
  }

  @Test fun remoteMediaStateIsTrackedPerMember() {
    val s = withDenLive()
    val call = reduce(s, CallEvent.RemoteMedia(id, den.deviceId, cameraOn = false, micOn = false)).state.call!!
    assertFalse(call.leg(den.deviceId)!!.remoteCameraOn)
    assertTrue(call.remoteCameraOn)
  }

  @Test fun staleMediaEventsForAClosedLegDoNotTouchTheFirstMember() {
    val s = live()
    assertEquals(s, reduce(s, CallEvent.MediaDown(id, t0 + 9_000, den.deviceId)).state)
  }

  @Test fun busyReplyTellsAnIncomingGroupRingWhileInACall() {
    val s = live()
    val step = reduce(s, CallEvent.Incoming("fedcba9876543210", den, CallKind.CALL, true, "192.168.1.30", 8723,
        IncomingDecision.Ring(false), t0 + 9_000, listOf(CallMemberRef(office, null, 0))))
    assertTrue(CallEffect.SendReply("fedcba9876543210", den.deviceId, CallReply.BUSY) in step.effects)
  }

  @Test fun groupQualityDropsTo360pAtThreeAndTwentyFourFpsAtFour() {
    assertEquals(1280, CallGroupQuality.profile(CallQuality.AUTO, 2, false).width)
    assertEquals(CallGroupQuality.GROUP_3, CallGroupQuality.profile(CallQuality.AUTO, 3, false))
    assertEquals(CallGroupQuality.GROUP_4, CallGroupQuality.profile(CallQuality.BEST, 4, false))
    val saved = CallGroupQuality.profile(CallQuality.SAVE, 3, false)
    assertEquals(640, saved.width)
    assertEquals(700, saved.maxKbps)
    assertEquals(640, CallGroupQuality.profile(CallQuality.AUTO, 2, degraded = true).width)
  }

  @Test fun linkBitrateStepsDownOnCongestionAndClimbsBackSlowly() {
    val busy = CallStats(atMs = 0, limitedBy = "bandwidth")
    val clean = CallStats(atMs = 0, limitedBy = "none")
    var st = CallLinkBitrate.State(800)
    st = CallLinkBitrate.next(st, 800, busy)
    assertEquals(560, st.kbps)
    repeat(20) { st = CallLinkBitrate.next(st, 800, busy) }
    assertEquals(CallLinkBitrate.FLOOR_KBPS, st.kbps)
    repeat(CallLinkBitrate.CLEAN_SAMPLES_TO_RAISE - 1) { st = CallLinkBitrate.next(st, 800, clean) }
    assertEquals(CallLinkBitrate.FLOOR_KBPS, st.kbps)
    st = CallLinkBitrate.next(st, 800, clean)
    assertEquals((CallLinkBitrate.FLOOR_KBPS * CallLinkBitrate.STEP_UP).toInt(), st.kbps)
    assertEquals(800, CallLinkBitrate.next(CallLinkBitrate.State(900), 800, clean).kbps)
  }
}
