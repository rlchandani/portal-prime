/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** The pure call state machine: every flow, timeout and privacy rule in the mock. */
class CallMachineTest {
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val id = "0123456789abcdef"
  private val t0 = 1_000_000L

  private fun reduce(s: CallState, e: CallEvent) = CallMachine.reduce(s, e)
  private fun dial(kind: CallKind = CallKind.CALL, video: Boolean = true) =
      reduce(CallState(), CallEvent.Dial(id, kitchen, kind, video, t0))
  private fun incoming(decision: IncomingDecision, kind: CallKind = CallKind.CALL, video: Boolean = true, state: CallState = CallState()) =
      reduce(state, CallEvent.Incoming(id, kitchen, kind, video, "192.168.1.20", 8723, decision, t0))
  private fun ringingOut() = reduce(dial().state, CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0 + 300))

  @Test fun dialSendsRingOpensOnlyThePreviewAndAnnounces() {
    val step = dial()
    val call = step.state.call!!
    assertEquals(CallPhase.DIALING, call.phase)
    assertTrue(call.cameraOn)
    assertTrue(step.effects.any { it is CallEffect.SendRing })
    assertTrue(CallEffect.OpenPreview(true) in step.effects)
    assertFalse("the caller's mic never opens while ringing", step.effects.any { it is CallEffect.SetMic || it is CallEffect.StartMedia })
  }

  @Test fun dialWhileInACallDoesNothing() {
    val busy = dial().state
    val again = reduce(busy, CallEvent.Dial("fedcba9876543210", kitchen, CallKind.CALL, true, t0))
    assertEquals(busy, again.state)
    assertTrue(again.effects.isEmpty())
  }

  @Test fun dropInDialKeepsCameraClosedUntilTheReply() {
    val step = dial(CallKind.DROP_IN)
    assertFalse(step.state.call!!.cameraOn)
    assertTrue(CallEffect.OpenPreview(false) in step.effects)
    val ask = reduce(step.state, CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0 + 200))
    assertFalse("Ask: nothing opens until they say yes", ask.state.call!!.cameraOn)
    val chime = reduce(step.state, CallEvent.Replied(id, kitchen.deviceId, CallReply.COUNTDOWN, null, t0 + 200))
    assertEquals(CallPhase.COUNTDOWN, chime.state.call!!.phase)
    assertTrue(chime.state.call!!.cameraOn)
    assertTrue(CallEffect.OpenPreview(true) in chime.effects)
  }

  @Test fun noReplyWithinFourSecondsIsOffline() {
    val s = dial().state
    assertNotNull(reduce(s, CallEvent.Tick(t0 + CallTimings.DIAL_ACK_MS - 1)).state.call)
    val step = reduce(s, CallEvent.Tick(t0 + CallTimings.DIAL_ACK_MS))
    assertNull(step.state.call)
    assertEquals(CallEndReason.OFFLINE, step.state.ended!!.reason)
    assertTrue(CallEffect.StopMedia in step.effects)
  }

  @Test fun ringTimesOutAfter45sAndTellsTheCallee() {
    val s = ringingOut().state
    val step = reduce(s, CallEvent.Tick(t0 + 300 + CallTimings.RING_MS))
    assertEquals(CallEndReason.NO_ANSWER, step.state.ended!!.reason)
    assertTrue(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.NO_ANSWER) in step.effects)
  }

  @Test fun replyFromAnotherDeviceOrCallIsIgnored() {
    val s = dial().state
    assertEquals(s, reduce(s, CallEvent.Replied(id, "intruder", CallReply.DECLINED, null, t0)).state)
    assertEquals(s, reduce(s, CallEvent.Replied("ffffffffffffffff", kitchen.deviceId, CallReply.DECLINED, null, t0)).state)
  }

  @Test fun refusalsEndWithTheirReason() {
    val s = dial().state
    mapOf(CallReply.QUIET to CallEndReason.QUIET, CallReply.BUSY to CallEndReason.BUSY, CallReply.CALLS_OFF to CallEndReason.CALLS_OFF,
        CallReply.DROP_INS_OFF to CallEndReason.DROP_INS_OFF, CallReply.DECLINED to CallEndReason.DECLINED).forEach { (reply, reason) ->
      val step = reduce(s, CallEvent.Replied(id, kitchen.deviceId, reply, 420, t0 + 100))
      assertEquals(reason, step.state.ended!!.reason)
      assertNull(step.state.call)
    }
    assertEquals(420, reduce(s, CallEvent.Replied(id, kitchen.deviceId, CallReply.QUIET, 420, t0)).state.ended!!.quietEndMin)
  }

  @Test fun cancelBeforeAnswerSendsCancelled() {
    val step = reduce(ringingOut().state, CallEvent.HangUp(t0 + 1_000))
    assertEquals(CallEndReason.CANCELLED, step.state.ended!!.reason)
    assertTrue(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.CANCELLED) in step.effects)
  }

  @Test fun verifiedOfferMovesCallerToConnectingThenLive() {
    val connecting = reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 2_000)).state
    assertEquals(CallPhase.CONNECTING, connecting.call!!.phase)
    assertEquals(connecting, reduce(connecting, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0 + 2_100)).state)
    val live = reduce(connecting, CallEvent.MediaUp(id, t0 + 2_500)).state
    assertEquals(CallPhase.LIVE, live.call!!.phase)
    assertEquals(t0 + 2_500, live.call!!.connectedMs)
    assertNull("calls have no cap", live.call!!.capAtMs)
  }

  @Test fun offerFromAStrangerIsRejected() {
    val s = ringingOut().state
    assertEquals(s, reduce(s, CallEvent.RemoteAccepted(id, "someone-else", t0)).state)
  }

  @Test fun incomingRingPresentsAndRingsButOpensNothing() {
    val step = incoming(IncomingDecision.Ring(silent = false))
    val call = step.state.call!!
    assertEquals(CallPhase.RINGING, call.phase)
    assertEquals(CallDirection.INCOMING, call.direction)
    assertTrue(CallEffect.Present in step.effects)
    assertTrue(CallEffect.StartRing(false) in step.effects)
    assertTrue(CallEffect.SendReply(id, kitchen.deviceId, CallReply.RINGING) in step.effects)
    assertFalse("privacy: camera and mic stay closed until Accept",
        step.effects.any { it is CallEffect.StartMedia || it is CallEffect.OpenPreview || it is CallEffect.SetCamera })
  }

  @Test fun acceptOpensMediaWithCameraUnlessAudioOnly() {
    val ringing = incoming(IncomingDecision.Ring(false)).state
    val video = reduce(ringing, CallEvent.Accept(audioOnly = false, now = t0 + 3_000))
    assertTrue(video.effects.first() == CallEffect.StopRing)
    assertTrue((video.effects.single { it is CallEffect.StartMedia } as CallEffect.StartMedia).call.cameraOn)
    val audio = reduce(ringing, CallEvent.Accept(audioOnly = true, now = t0 + 3_000))
    assertFalse(audio.state.call!!.cameraOn)
  }

  @Test fun declineTellsTheCallerAndLogs() {
    val step = reduce(incoming(IncomingDecision.Ring(false)).state, CallEvent.Decline(t0 + 1_000))
    assertEquals(CallEndReason.DECLINED, step.state.ended!!.reason)
    assertTrue(CallEffect.SendReply(id, kitchen.deviceId, CallReply.DECLINED) in step.effects)
    assertTrue(step.effects.any { it is CallEffect.Log })
    assertTrue(CallEffect.StopRing in step.effects)
  }

  @Test fun callerCancellingIsAMissedCallHere() {
    val step = reduce(incoming(IncomingDecision.Ring(false)).state, CallEvent.RemoteEnded(id, kitchen.deviceId, CallEndReason.CANCELLED, t0 + 5_000))
    assertEquals(CallEndReason.MISSED, step.state.ended!!.reason)
    assertTrue((step.effects.single { it is CallEffect.Log } as CallEffect.Log).entry.missed)
  }

  @Test fun quietRejectionLogsAMissedCallWithoutRinging() {
    val step = incoming(IncomingDecision.Reject(CallReply.QUIET))
    assertNull(step.state.call)
    assertTrue(CallEffect.SendReply(id, kitchen.deviceId, CallReply.QUIET) in step.effects)
    assertFalse(step.effects.any { it == CallEffect.Present || it is CallEffect.StartRing })
    assertEquals(CallEndReason.QUIET, (step.effects.single { it is CallEffect.Log } as CallEffect.Log).entry.reason)
  }

  @Test fun otherRejectionsAreNotLogged() {
    val step = incoming(IncomingDecision.Reject(CallReply.DROP_INS_OFF), CallKind.DROP_IN)
    assertFalse(step.effects.any { it is CallEffect.Log })
  }

  @Test fun secondRingWhileBusyIsBusyAndLogged() {
    val busy = dial().state
    val step = reduce(busy, CallEvent.Incoming("aaaaaaaaaaaaaaaa", kitchen, CallKind.CALL, true, "192.168.1.20", 8723,
        IncomingDecision.Ring(false), t0))
    assertEquals(busy, step.state)
    assertTrue(CallEffect.SendReply("aaaaaaaaaaaaaaaa", kitchen.deviceId, CallReply.BUSY) in step.effects)
  }

  @Test fun repeatedRingIsAnsweredAgainWithoutChange() {
    val ringing = incoming(IncomingDecision.Ring(false)).state
    val step = incoming(IncomingDecision.Ring(false), state = ringing)
    assertEquals(ringing, step.state)
    assertEquals(listOf(CallEffect.SendReply(id, kitchen.deviceId, CallReply.RINGING)), step.effects)
  }

  @Test fun silentRingInQuietHoursMakesNoSound() {
    val step = incoming(IncomingDecision.Ring(silent = true))
    assertTrue(step.state.call!!.silent)
    assertTrue(CallEffect.StartRing(true) in step.effects)
  }

  @Test fun incomingRingGivesUpJustAfterTheCaller() {
    val ringing = incoming(IncomingDecision.Ring(false)).state
    assertNotNull(reduce(ringing, CallEvent.Tick(t0 + CallTimings.RING_MS)).state.call)
    assertEquals(CallEndReason.MISSED, reduce(ringing, CallEvent.Tick(t0 + CallTimings.RING_MS + 2_000)).state.ended!!.reason)
  }

  @Test fun chimeDropInChimesThenJoinsAfterThreeSeconds() {
    val step = incoming(IncomingDecision.Countdown, CallKind.DROP_IN)
    assertEquals(CallPhase.COUNTDOWN, step.state.call!!.phase)
    assertTrue(CallEffect.DropInChime in step.effects)
    assertFalse(step.effects.any { it is CallEffect.StartMedia })
    assertEquals(CallPhase.COUNTDOWN, reduce(step.state, CallEvent.Tick(t0 + 2_999)).state.call!!.phase)
    val joined = reduce(step.state, CallEvent.Tick(t0 + CallTimings.DROP_IN_COUNTDOWN_MS))
    assertEquals(CallPhase.CONNECTING, joined.state.call!!.phase)
    assertTrue(joined.effects.any { it is CallEffect.StartMedia })
  }

  @Test fun notNowDuringTheCountdownWins() {
    val countdown = incoming(IncomingDecision.Countdown, CallKind.DROP_IN).state
    val step = reduce(countdown, CallEvent.Decline(t0 + 1_000))
    assertEquals(CallEndReason.DECLINED, step.state.ended!!.reason)
    assertNull(reduce(step.state, CallEvent.Tick(t0 + 5_000)).state.call)
  }

  @Test fun dropInsAreCappedAtFifteenMinutesUnlessKeptGoing() {
    val connecting = reduce(incoming(IncomingDecision.Countdown, CallKind.DROP_IN).state, CallEvent.Tick(t0 + 3_000)).state
    val live = reduce(connecting, CallEvent.MediaUp(id, t0 + 4_000)).state
    val cap = live.call!!.capAtMs!!
    assertEquals(t0 + 4_000 + CallTimings.DROP_IN_CAP_MS, cap)
    val kept = reduce(live, CallEvent.KeepGoing(t0 + 600_000))
    assertEquals(cap + CallTimings.DROP_IN_CAP_MS, kept.state.call!!.capAtMs)
    assertTrue(kept.effects.single() is CallEffect.SendKeep)
    assertNotNull(reduce(kept.state, CallEvent.Tick(cap)).state.call)
    val over = reduce(live, CallEvent.Tick(cap))
    assertEquals(CallEndReason.CAP_REACHED, over.state.ended!!.reason)
  }

  @Test fun remoteKeepGoingTakesTheLaterCap() {
    val live = reduce(reduce(incoming(IncomingDecision.Countdown, CallKind.DROP_IN).state, CallEvent.Tick(t0 + 3_000)).state,
        CallEvent.MediaUp(id, t0 + 4_000)).state
    val later = live.call!!.capAtMs!! + 60_000
    assertEquals(later, reduce(live, CallEvent.RemoteKeepGoing(id, kitchen.deviceId, later)).state.call!!.capAtMs)
    assertEquals(later, reduce(reduce(live, CallEvent.RemoteKeepGoing(id, kitchen.deviceId, later)).state,
        CallEvent.RemoteKeepGoing(id, kitchen.deviceId, later - 5)).state.call!!.capAtMs)
  }

  @Test fun keepGoingDoesNothingOnNormalCalls() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    assertEquals(live, reduce(live, CallEvent.KeepGoing(t0)).state)
  }

  @Test fun networkDropReconnectsOrEndsCleanly() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    val down = reduce(live, CallEvent.MediaDown(id, t0 + 10_000)).state
    assertEquals(CallPhase.RECONNECTING, down.call!!.phase)
    val back = reduce(down, CallEvent.MediaUp(id, t0 + 12_000)).state
    assertEquals(CallPhase.LIVE, back.call!!.phase)
    assertEquals("the call timer keeps its start", t0, back.call!!.connectedMs)
    val lost = reduce(down, CallEvent.Tick(t0 + 10_000 + CallTimings.RECONNECT_MS))
    assertEquals(CallEndReason.NETWORK_LOST, lost.state.ended!!.reason)
    assertTrue(CallEffect.StopMedia in lost.effects)
  }

  @Test fun connectingTimesOutAsFailed() {
    val connecting = reduce(incoming(IncomingDecision.Ring(false)).state, CallEvent.Accept(false, t0)).state
    assertEquals(CallEndReason.FAILED, reduce(connecting, CallEvent.Tick(t0 + CallTimings.CONNECT_MS)).state.ended!!.reason)
  }

  @Test fun leavingTheScreenClosesEverything() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    val step = reduce(live, CallEvent.Left(t0 + 60_000))
    assertEquals(CallEndReason.LEFT, step.state.ended!!.reason)
    assertTrue(CallEffect.StopMedia in step.effects)
    assertTrue(CallEffect.SendEnd(id, kitchen.deviceId, CallEndReason.LEFT) in step.effects)
    val ringing = incoming(IncomingDecision.Ring(false)).state
    assertEquals("leaving a ring declines it", CallEndReason.DECLINED, reduce(ringing, CallEvent.Left(t0)).state.ended!!.reason)
  }

  @Test fun remoteHangUpEndsAndMapsReasons() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    assertEquals(CallEndReason.HUNG_UP, reduce(live, CallEvent.RemoteEnded(id, kitchen.deviceId, CallEndReason.LEFT, t0)).state.ended!!.reason)
    assertEquals(CallEndReason.CAP_REACHED, reduce(live, CallEvent.RemoteEnded(id, kitchen.deviceId, CallEndReason.CAP_REACHED, t0)).state.ended!!.reason)
    assertEquals(CallEndReason.HUNG_UP, reduce(live, CallEvent.RemoteEnded(id, kitchen.deviceId, CallEndReason.QUIET, t0)).state.ended!!.reason)
    assertEquals(live, reduce(live, CallEvent.RemoteEnded(id, "someone", CallEndReason.HUNG_UP, t0)).state)
  }

  @Test fun togglesSendMediaStateOnceConnected() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    val muted = reduce(live, CallEvent.ToggleMic)
    assertFalse(muted.state.call!!.micOn)
    assertTrue(CallEffect.SetMic(false) in muted.effects)
    assertTrue(CallEffect.SendMediaState(id, kitchen.deviceId, true, false) in muted.effects)
    val camOff = reduce(live, CallEvent.ToggleCamera)
    assertTrue(CallEffect.SetCamera(false) in camOff.effects)
  }

  @Test fun cameraToggleBeforeAnswerOnlyMovesThePreview() {
    val ringing = ringingOut()
    val step = reduce(ringing.state, CallEvent.ToggleCamera)
    assertEquals(listOf(CallEffect.OpenPreview(false)), step.effects)
  }

  @Test fun cameraToggleIsBlockedWhileAnAskDropInWaits() {
    val asking = reduce(dial(CallKind.DROP_IN).state, CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0)).state
    val step = reduce(asking, CallEvent.ToggleCamera)
    assertTrue(step.effects.none { it is CallEffect.OpenPreview || it is CallEffect.SetCamera })
  }

  @Test fun acceptedAskDropInMayOpenTheCamera() {
    val asking = reduce(dial(CallKind.DROP_IN).state, CallEvent.Replied(id, kitchen.deviceId, CallReply.RINGING, null, t0)).state
    assertTrue(reduce(asking, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state.call!!.cameraOn)
  }

  @Test fun cameraProblemTurnsCameraOffAndSaysWhy() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    val step = reduce(live, CallEvent.CameraProblem(CameraIssue.SHUTTER))
    assertFalse(step.state.call!!.cameraOn)
    assertEquals(CameraIssue.SHUTTER, step.state.call!!.cameraIssue)
    val retry = reduce(step.state, CallEvent.ToggleCamera)
    assertNull("turning it back on clears the issue", retry.state.call!!.cameraIssue)
  }

  @Test fun remoteMediaStateIsTracked() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0)).state
    val s = reduce(live, CallEvent.RemoteMedia(id, kitchen.deviceId, cameraOn = false, micOn = true)).state
    assertFalse(s.call!!.remoteCameraOn)
  }

  @Test fun mediaFailureEndsAndTellsThePeer() {
    val s = dial().state
    val step = reduce(s, CallEvent.MediaFailed(id, CallEndReason.UNAVAILABLE, t0))
    assertEquals(CallEndReason.UNAVAILABLE, step.state.ended!!.reason)
  }

  @Test fun endedCanBeDismissedAndEventsWithoutACallAreNoOps() {
    val ended = reduce(dial().state, CallEvent.HangUp(t0)).state
    assertNotNull(ended.ended)
    assertNull(reduce(ended, CallEvent.DismissEnded).state.ended)
    assertEquals(ended, reduce(ended, CallEvent.ToggleMic).state)
    assertEquals(ended, reduce(ended, CallEvent.Tick(t0 + 100_000)).state)
  }

  @Test fun durationCountsFromConnection() {
    val live = reduce(reduce(ringingOut().state, CallEvent.RemoteAccepted(id, kitchen.deviceId, t0)).state, CallEvent.MediaUp(id, t0 + 1_000)).state
    val ended = reduce(live, CallEvent.HangUp(t0 + 61_000)).state.ended!!
    assertEquals(60_000L, ended.durationMs)
  }
}
