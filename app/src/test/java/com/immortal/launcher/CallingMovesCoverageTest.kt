/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** "To phone": which phone may take a call over, the control state, and the moving banner. */
class CallingMovesCoverageTest {
  private val self = "11111111-1111-1111-1111-111111111111"
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val attic = CallContact("44444444-4444-4444-4444-444444444444", "Attic")
  private val otherPhone = CallContact("phfedcba9876543210", "Priya", kind = CallContact.Kind.PHONE)
  private val now = 1_800_000_000_000L
  private val sam = CallPhone("ph0123456789abcdef", "Sam’s phone", now, aliases = mapOf(kitchen.deviceId to "phaaaaaaaaaaaaaaaa"))

  private fun call(
      phase: CallPhase = CallPhase.LIVE,
      kind: CallKind = CallKind.CALL,
      legs: List<CallLeg> = emptyList(),
      move: CallMove? = null,
  ) = ActiveCall("0123456789abcdef", kitchen, CallDirection.OUTGOING, kind, true, phase, now, now,
      cameraOn = true, legs = legs, move = move)

  private fun leg(c: CallContact, phase: LegPhase = LegPhase.LIVE) = CallLeg(c, phase, now, offerer = false)

  @Test fun optionOnlyOffersALiveOrdinaryCall() {
    for (phase in CallPhase.entries.filter { it != CallPhase.LIVE }) {
      assertEquals(phase.name, CallMoves.Option.NOT_NOW, CallMoves.option(call(phase = phase), sam, self, now))
    }
    assertEquals(CallMoves.Option.NOT_NOW, CallMoves.option(call(kind = CallKind.DROP_IN), sam, self, now))
    assertEquals(CallMoves.Option.READY, CallMoves.option(call(), sam, self, now))
  }

  @Test fun optionExplainsEachBlockingReasonInPriorityOrder() {
    assertEquals(CallMoves.Option.LOCKED, CallMoves.option(call().copy(autoAnswered = true), sam, self, now))
    assertEquals(CallMoves.Option.LOCKED, CallMoves.option(call().copy(remoteAutoAnswered = true, move = CallMove(to = "x")), sam, self, now))
    assertEquals(CallMoves.Option.MOVING, CallMoves.option(call(move = CallMove(to = "x")), sam, self, now))
    assertEquals(CallMoves.Option.PHONE_IN_CALL, CallMoves.option(call(legs = listOf(leg(otherPhone))), sam, self, now))
    assertEquals(CallMoves.Option.FULL, CallMoves.option(call(legs = listOf(leg(den), leg(attic))), sam, self, now))
    assertEquals(CallMoves.Option.CLOSED, CallMoves.option(call(), sam.copy(seenMs = now - CallPhones.REACHABLE_MS - 1), self, now))
    assertEquals(CallMoves.Option.NOT_PAIRED, CallMoves.option(call(legs = listOf(leg(den))), sam, self, now))
  }

  @Test fun aParkedBackgroundPollKeepsAQuietPhoneReachable() {
    val parked = sam.copy(seenMs = 0, parkedUntilMs = now)
    assertEquals(CallMoves.Option.READY, CallMoves.option(call(), parked, self, now))
    assertEquals(CallMoves.Option.READY, CallMoves.option(call(), sam.copy(seenMs = now - CallPhones.REACHABLE_MS), self, now))
  }

  @Test fun thisPortalItselfNeverCountsAsUnpaired() {
    // The peer is this Portal's own id (as seen from the other side): it never needs a session.
    val selfPeer = call().copy(peer = CallContact(self, "Me"))
    assertEquals(CallMoves.Option.READY, CallMoves.option(selfPeer, sam.copy(aliases = emptyMap()), self, now))
  }

  @Test fun controlWhileMovingIsADimmedMovingLabel() {
    val c = CallMoves.control(call(move = CallMove(to = sam.id)), listOf(sam), self, now)
    assertEquals(CallMoves.Control(CallMoves.MOVING_LABEL, false, null, false, null), c)
  }

  @Test fun controlMovesStraightToOneReadyPhoneAndPicksAmongSeveral() {
    val notReady = sam.copy(id = "ph1111111111111111", seenMs = 0)
    val one = CallMoves.control(call(), listOf(sam, notReady), self, now)
    assertEquals(CallMoves.Control(CallMoves.LABEL, true, sam, false, null), one)
    val two = CallMoves.control(call(), listOf(sam, sam.copy(id = "ph2222222222222222")), self, now)
    assertEquals(CallMoves.Control(CallMoves.LABEL, true, null, true, null), two)
  }

  @Test fun dimmedControlGivesTheMostUsefulReason() {
    fun why(c: ActiveCall, phones: List<CallPhone> = listOf(sam)) = CallMoves.control(c, phones, self, now).also {
      assertFalse(it.enabled); assertNull(it.target); assertFalse(it.pick); assertEquals(CallMoves.LABEL, it.label)
    }.why
    assertEquals("You can move a call once it’s connected", why(call(phase = CallPhase.CONNECTING)))
    assertEquals("You can move a call once it’s connected", why(call(kind = CallKind.DROP_IN)))
    assertEquals("This call was answered automatically, so it stays on this Portal", why(call().copy(autoAnswered = true)))
    assertEquals("A phone is already in this call", why(call(legs = listOf(leg(otherPhone)))))
    assertEquals("Calls hold up to 4. Someone needs to leave before the call can move", why(call(legs = listOf(leg(den), leg(attic)))))
    assertEquals("Pair a phone with Portal Toolkit to move calls to it", why(call(), emptyList()))
    assertEquals("Sam’s phone isn’t paired with every Portal in this call",
        why(call(legs = listOf(leg(den))), listOf(sam.copy(seenMs = 0), sam)))
    assertEquals("Open Portal Toolkit on your phone, or turn on “Ring when Portal Toolkit is closed”",
        why(call(), listOf(sam.copy(seenMs = 0))))
  }

  @Test fun movingLineFollowsTheTargetLeg() {
    val samContact = sam.contact()
    fun line(phase: LegPhase) = CallMoves.movingLine(call(legs = listOf(leg(samContact, phase)), move = CallMove(to = sam.id)))
    assertEquals("Ringing Sam’s phone · pick up there to move the call", line(LegPhase.RINGING))
    assertEquals("Moving to Sam’s phone…", line(LegPhase.JOINING))
    assertEquals("Sam’s phone is in · this Portal leaves in a moment", line(LegPhase.LIVE))
    assertEquals("Sam’s phone is in · this Portal leaves in a moment", line(LegPhase.RECONNECTING))
  }

  @Test fun movingLineIsNullWithoutAMoveTarget() {
    assertNull(CallMoves.movingLine(call()))
    assertNull("target side of a move has no 'to'", CallMoves.movingLine(call(move = CallMove(from = sam.id))))
    assertNull("target not (yet) a leg", CallMoves.movingLine(call(legs = listOf(leg(den)), move = CallMove(to = sam.id))))
  }
}
