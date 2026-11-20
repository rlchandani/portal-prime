/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Calling copy branches the base suite skips: ringing lines, group/move rings, every ended screen variant. */
class CallingCopyCoverageTest {
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val kids = CallContact("44444444-4444-4444-4444-444444444444", "Kids")
  private val samPhone = CallContact("ph0123456789abcdef", "Sam’s phone", kind = CallContact.Kind.PHONE)
  private val id = "0123456789abcdef"

  private fun call(kind: CallKind = CallKind.CALL, phase: CallPhase = CallPhase.RINGING, dir: CallDirection = CallDirection.OUTGOING,
                   video: Boolean = true) = ActiveCall(id, kitchen, dir, kind, video, phase, 0, 0, cameraOn = true)

  private fun ended(reason: CallEndReason, dir: CallDirection = CallDirection.OUTGOING, kind: CallKind = CallKind.CALL,
                    peer: CallContact = kitchen, connected: Long? = null, end: Long = 1_000, movedTo: CallContact? = null) =
      CallingCopy.ended(EndedCall(id, peer, dir, kind, reason, connected, end, movedTo = movedTo), "4:36 PM", null)

  @Test fun outgoingSubForEachRingingState() {
    assertEquals("Ringing the Kitchen Portal", CallingCopy.outgoingSub(call()))
    assertEquals("Kitchen is chiming and showing a banner. Anyone there can say “Not now”.",
        CallingCopy.outgoingSub(call(kind = CallKind.DROP_IN, phase = CallPhase.COUNTDOWN)))
    assertEquals("Kitchen asks first. Nothing opens until someone there taps Join.",
        CallingCopy.outgoingSub(call(kind = CallKind.DROP_IN)))
    assertEquals("Reaching Kitchen…", CallingCopy.outgoingSub(call(kind = CallKind.DROP_IN, phase = CallPhase.DIALING)))
  }

  @Test fun incomingSubAndLine() {
    assertEquals("Drop-in from Kitchen", CallingCopy.incomingSub(CallKind.DROP_IN, true, "Kitchen"))
    assertEquals("Audio call from Kitchen", CallingCopy.incomingSub(CallKind.CALL, false, "Kitchen"))
    val inc = call(dir = CallDirection.INCOMING, video = false)
    assertEquals("Audio call from Kitchen", CallingCopy.incomingLine(inc))
    val group = inc.copy(invited = listOf(CallMemberRef(den, null, 0), CallMemberRef(kids, null, 0)))
    assertEquals("Kitchen added you to a call with Den and Kids", CallingCopy.incomingLine(group))
    assertEquals("Moving a call here · with Den and Kids", CallingCopy.incomingLine(group.copy(move = CallMove(from = kitchen.deviceId))))
    assertEquals("Moving a call here", CallingCopy.incomingLine(inc.copy(move = CallMove(from = kitchen.deviceId))))
  }

  @Test fun namesJoinsWithCommasAndAnd() {
    assertEquals("", CallingCopy.names(emptyList()))
    assertEquals("Kitchen", CallingCopy.names(listOf("Kitchen")))
    assertEquals("Kitchen and Den", CallingCopy.names(listOf("Kitchen", "Den")))
    assertEquals("Kitchen, Den and Kids", CallingCopy.names(listOf("Kitchen", "Den", "Kids")))
  }

  @Test fun answerLabelTileNameAndBanners() {
    assertEquals("Answer", CallingCopy.answerLabel(call()))
    assertEquals("Pick up here", CallingCopy.answerLabel(call().copy(move = CallMove(from = "x"))))
    assertEquals("Sam’s phone · phone", CallingCopy.tileName(samPhone))
    assertEquals("Kitchen", CallingCopy.tileName(kitchen))
    assertEquals("Kitchen wants to drop in", CallingCopy.dropInBannerTitle(true, "Kitchen"))
    assertEquals("Your camera and mic stay off unless you say yes.", CallingCopy.dropInBannerSub(true, 2))
    assertEquals("Drop-in with Den · Camera off · Mic muted", CallingCopy.dropInBar("Den", false, false, false))
    assertTrue(CallingCopy.liveDotActive(false, true))
    assertTrue(CallingCopy.liveDotActive(true, false))
  }

  @Test fun durationRollsOverIntoHours() {
    assertEquals("1 min", CallingCopy.duration(60_000))
    assertEquals("59 min", CallingCopy.duration(59 * 60_000L + 59_999))
    assertEquals("1 h 0 min", CallingCopy.duration(60 * 60_000L))
    assertEquals("Under a minute", CallingCopy.duration(-1))
  }

  @Test fun quietWithoutAnEndTimeStillExplains() {
    val q = ended(CallEndReason.QUIET)
    assertEquals("Their Portal won’t ring right now. We didn’t wake anyone.", q.body)
    assertFalse(q.retry)
    assertEquals("moon", q.icon)
  }

  @Test fun offlineDiffersForPhonesAndPortals() {
    val phone = ended(CallEndReason.OFFLINE, peer = samPhone)
    assertEquals("Sam’s phone didn’t pick up the ring", phone.title)
    assertTrue(phone.warn)
    val portal = ended(CallEndReason.OFFLINE)
    assertEquals("Their Portal is offline", portal.title)
    assertTrue(portal.body.startsWith("Kitchen didn’t answer the network"))
  }

  @Test fun movedNamesTheTargetOrFallsBackToYourPhone() {
    assertEquals("Call moved to Sam’s phone", ended(CallEndReason.MOVED, movedTo = samPhone).title)
    val generic = ended(CallEndReason.MOVED)
    assertEquals("Call moved to your phone", generic.title)
    assertEquals(CallingCopy.MOVED_BODY, generic.body)
    assertFalse(generic.retry)
  }

  @Test fun declinedReadsDifferentlyForEachSide() {
    val out = ended(CallEndReason.DECLINED)
    assertEquals("Kitchen can’t talk right now", out.title)
    assertTrue(out.retry)
    val inc = ended(CallEndReason.DECLINED, dir = CallDirection.INCOMING)
    assertEquals("Declined", inc.title)
    assertEquals("Kitchen was told you can’t talk right now.", inc.body)
    assertFalse(inc.retry)
  }

  @Test fun networkLostMentionsHowLongItLasted() {
    assertEquals("The Wi-Fi connection to Kitchen was lost after 2 min. Nothing was stored.",
        ended(CallEndReason.NETWORK_LOST, connected = 0, end = 120_000).body)
    assertEquals("The Wi-Fi connection to Kitchen was lost. Nothing was stored.", ended(CallEndReason.NETWORK_LOST).body)
  }

  @Test fun endingsWithoutConnectionAndDropInEndings() {
    assertEquals("4:36 PM · nothing was recorded", ended(CallEndReason.CANCELLED).body)
    assertEquals("Drop-in with Kitchen ended", ended(CallEndReason.LEFT, kind = CallKind.DROP_IN).title)
    assertEquals("Call with Kitchen ended", ended(CallEndReason.HUNG_UP).title)
    assertEquals("Missed call from Kitchen", ended(CallEndReason.MISSED).title)
    assertEquals("4:36 PM · nothing rang for long enough to answer.", ended(CallEndReason.MISSED).body)
    assertEquals("Drop-in with Kitchen ended", ended(CallEndReason.CAP_REACHED).title)
    assertEquals("door", ended(CallEndReason.DROP_INS_OFF).icon)
    assertEquals("bellOff", ended(CallEndReason.CALLS_OFF).icon)
    assertEquals("bellOff", ended(CallEndReason.NO_ANSWER).icon)
    assertEquals("phone", ended(CallEndReason.BUSY).icon)
    for (r in listOf(CallEndReason.FAILED, CallEndReason.UNAVAILABLE, CallEndReason.NO_PERMISSION)) {
      assertTrue(r.name, ended(r).warn)
      assertEquals(r.name, "warn", ended(r).icon)
    }
  }

  @Test fun historyLineFallbacks() {
    fun e(dir: CallDirection, reason: CallEndReason, ms: Long = 0, kind: CallKind = CallKind.CALL) =
        CallHistoryEntry(id, kitchen.deviceId, "Kitchen", dir, kind, reason, 0, ms)
    assertEquals("t · missed", CallingCopy.historyLine(e(CallDirection.INCOMING, CallEndReason.MISSED), "t"))
    assertEquals("t · call · 2 min", CallingCopy.historyLine(e(CallDirection.OUTGOING, CallEndReason.HUNG_UP, 120_000), "t"))
    assertEquals("t · didn’t connect", CallingCopy.historyLine(e(CallDirection.OUTGOING, CallEndReason.NO_ANSWER), "t"))
    assertEquals("t · declined", CallingCopy.historyLine(e(CallDirection.INCOMING, CallEndReason.DECLINED), "t"))
    // Outgoing into quiet hours is not "quiet hours, nothing rang" (that's the callee's line).
    assertEquals("t · didn’t connect", CallingCopy.historyLine(e(CallDirection.OUTGOING, CallEndReason.QUIET), "t"))
    // A drop-in that never connected reads like any other unconnected call.
    assertEquals("t · didn’t connect", CallingCopy.historyLine(e(CallDirection.OUTGOING, CallEndReason.CANCELLED, kind = CallKind.DROP_IN), "t"))
  }

  @Test fun historyDayFollowsTheGivenZone() {
    val utc = TimeZone.getTimeZone("UTC")
    val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    // 2026-06-10 23:30 UTC is already 2026-06-11 08:30 in Tokyo.
    val at = 1_781_134_200_000L
    val now = at + 60 * 60_000L // 2026-06-11 00:30 UTC
    assertEquals("Yesterday", CallingCopy.historyDay(at, now, utc))
    assertEquals("Today", CallingCopy.historyDay(at, now, tokyo))
  }

  @Test fun minuteLabelAtMidnightAndNoon() {
    assertEquals("12:00 AM", CallingCopy.minuteLabel(0, false))
    assertEquals("12:05 PM", CallingCopy.minuteLabel(725, false))
    assertEquals("11:59 PM", CallingCopy.minuteLabel(1439, false))
    assertEquals("0:00", CallingCopy.minuteLabel(0, true))
  }

  @Test fun initialsSplitOnHyphensAndApostrophes() {
    assertEquals("OB", CallingCopy.initials("o’brien den"))
    assertEquals("OH", CallingCopy.initials("O'Hara"))
    assertEquals("US", CallingCopy.initials("up-stairs hall extra"))
    assertEquals("GR", CallingCopy.initials("Guest  room"))
    assertEquals("?", CallingCopy.initials(""))
  }
}
