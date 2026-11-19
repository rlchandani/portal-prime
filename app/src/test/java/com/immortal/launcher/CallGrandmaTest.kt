/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Grandma mode screens' pure rules: copy, countdown, volume steps, faces, routing and sizes. */
class CallGrandmaTest {
  private val t0 = 5_000_000L

  @Test fun countdownCountsTenToOneThenAnswers() {
    val at = t0 + CallTimings.AUTO_ANSWER_MS
    assertEquals(10, GrandmaCopy.secondsLeft(at, t0))
    assertEquals(10, GrandmaCopy.secondsLeft(at, t0 + 1))
    assertEquals(7, GrandmaCopy.secondsLeft(at, t0 + 3_000))
    assertEquals(1, GrandmaCopy.secondsLeft(at, at - 1))
    assertEquals(0, GrandmaCopy.secondsLeft(at, at))
    assertEquals(0, GrandmaCopy.secondsLeft(at, at + 5_000))
    assertEquals(10, GrandmaCopy.secondsLeft(at, t0 - 9_000))
    assertEquals("Answering by itself in 7 seconds", GrandmaCopy.countdown(7))
    assertEquals("Answering by itself in 1 second", GrandmaCopy.countdown(1))
    assertEquals("Answering now…", GrandmaCopy.countdown(0))
    assertEquals(0f, GrandmaCopy.progress(at, t0), 0.001f)
    assertEquals(0.5f, GrandmaCopy.progress(at, t0 + 5_000), 0.001f)
    assertEquals(1f, GrandmaCopy.progress(at, at + 1), 0.001f)
  }

  @Test fun copyMatchesTheMock() {
    assertEquals("Sam is calling", GrandmaCopy.calling("Sam"))
    assertEquals("Messages", GrandmaCopy.messages(0))
    assertEquals("1 new message", GrandmaCopy.messages(1))
    assertEquals("3 new messages", GrandmaCopy.messages(3))
    assertEquals("Camera on · Mic on · with Sam", GrandmaCopy.liveBar(true, true, "Sam"))
    assertEquals("morning", GrandmaCopy.partOfDay(7))
    assertEquals("afternoon", GrandmaCopy.partOfDay(16))
    assertEquals("evening", GrandmaCopy.partOfDay(19))
    assertEquals("night", GrandmaCopy.partOfDay(23))
    assertEquals("night", GrandmaCopy.partOfDay(2))
  }

  @Test fun volumeStepsTenPercentAndClamps() {
    assertEquals(4, CallVolumeMath.step(3, 1, 10))
    assertEquals(2, CallVolumeMath.step(3, -1, 10))
    assertEquals(10, CallVolumeMath.step(10, 1, 10))
    assertEquals(0, CallVolumeMath.step(0, -1, 10))
    assertEquals("a small range still moves one unit", 1, CallVolumeMath.step(0, 1, 5))
    assertEquals(4, CallVolumeMath.step(1, 1, 30))
    assertEquals(50, CallVolumeMath.percent(5, 10))
    assertEquals(0, CallVolumeMath.percent(0, 0))
  }

  @Test fun facesPutFavouritesFirstThenRoomsThenOpenPhones() {
    val peers = listOf(
        CallPeer("r1", "Den", t0), CallPeer("r2", "Kitchen", t0, favourite = true), CallPeer("r3", "Attic", t0),
        CallPeer("r4", "Office", t0, acceptsCalls = false))
    val phones = listOf(CallPhone("ph0000000000000001", "Sam’s phone", t0), CallPhone("ph0000000000000002", "Old", t0 - 60_000))
    val faces = GrandmaFaces.pick(peers, phones, t0)
    assertEquals(listOf("Kitchen", "Attic", "Den", "Sam’s phone"), faces.map { it.name })
    assertEquals(4, faces.size)
    val few = GrandmaFaces.pick(peers.take(1), phones, t0)
    assertEquals(listOf("Den", "Sam’s phone"), few.map { it.name })
    assertEquals(CallPresence.OFFLINE, GrandmaFaces.pick(listOf(CallPeer("r1", "Den", 0L)), emptyList(), t0).single().presence)
    assertTrue(GrandmaFaces.pick(emptyList(), emptyList(), t0).isEmpty())
  }

  private fun call(direction: CallDirection, phase: CallPhase, kind: CallKind = CallKind.CALL) = ActiveCall("0123456789abcdef",
      CallContact("x", "Sam"), direction, kind, true, phase, t0, t0, cameraOn = true)

  @Test fun grandmaReplacesIncomingAndLiveCallsButNotDropInsOrOutgoingRings() {
    assertTrue(GrandmaScreens.handles(call(CallDirection.INCOMING, CallPhase.RINGING)))
    assertTrue(GrandmaScreens.handles(call(CallDirection.INCOMING, CallPhase.LIVE)))
    assertTrue(GrandmaScreens.handles(call(CallDirection.OUTGOING, CallPhase.CONNECTING)))
    assertTrue(GrandmaScreens.handles(call(CallDirection.OUTGOING, CallPhase.RECONNECTING)))
    assertFalse(GrandmaScreens.handles(call(CallDirection.OUTGOING, CallPhase.RINGING)))
    assertFalse(GrandmaScreens.handles(call(CallDirection.INCOMING, CallPhase.COUNTDOWN, CallKind.DROP_IN)))
    assertFalse(GrandmaScreens.handles(call(CallDirection.INCOMING, CallPhase.LIVE, CallKind.DROP_IN)))
  }

  @Test fun everyWindowKeeps160dpButtonsAndStacksHangUpOnlyUpright() {
    LayoutClass.entries.forEach { assertEquals(160f, GrandmaSizes.of(it).button.value, 0f) }
    assertTrue(GrandmaLayout.inCall(LayoutClass.COMPACT_PORTRAIT).stackButtons)
    assertFalse(GrandmaLayout.inCall(LayoutClass.COMPACT_LANDSCAPE).stackButtons)
    assertFalse(GrandmaLayout.inCall(LayoutClass.LARGE).stackButtons)
    assertEquals(300, GrandmaLayout.ring(LayoutClass.LARGE).avatar)
    // Mini turned: avatar + title + countdown + 160 dp bar + note fit 740 dp less the insets.
    val r = GrandmaLayout.ring(LayoutClass.COMPACT_LANDSCAPE)
    val sizes = GrandmaSizes.of(LayoutClass.COMPACT_LANDSCAPE)
    val column = r.avatar + r.title * 1.05f + r.ringDp + sizes.button.value + r.line * 1.3f + 18 * 4
    assertTrue("$column", column + sizes.edge.value * 2 <= 740)
  }

  @Test fun settingsCopyIsHonest() {
    assertEquals("Never auto-answers in quiet hours (22:00–8:30). The camera light and the live bar always show.",
        GrandmaSettingsCopy.quietNote(true, 22 * 60, 8 * 60 + 30))
    assertTrue(GrandmaSettingsCopy.quietNote(false, 0, 0).contains("turn them on"))
    assertTrue(GrandmaSettingsCopy.personSub(phone = true, chosen = false).contains("always rings"))
    assertTrue(GrandmaSettingsCopy.personSub(phone = false, chosen = true).contains("10-second"))
    assertEquals("Let calls from Sam answer by themselves here?", GrandmaSettingsCopy.confirmTitle("Sam"))
    assertEquals("Portal Toolkit is open · this Portal can ring it", CallPhonesCopy.state(true, 0))
    assertEquals("Not open · last heard just now", CallPhonesCopy.state(false, 10_000))
    assertEquals("Not open · last heard 5 min ago", CallPhonesCopy.state(false, 5 * 60_000L))
    assertEquals("Not open · last heard 2 h ago", CallPhonesCopy.state(false, 125 * 60_000L))
  }

  @Test fun fleetFileRoutesCannotReachTheRules() {
    assertTrue(FleetSecretPaths.isBlocked(java.io.File("/data/user/0/x/no_backup/${AutoAnswerRepository.DIR}/rules.bin")))
    assertFalse(FleetSecretPaths.isBlocked(java.io.File("/data/user/0/x/no_backup/other/rules.bin")))
  }
}
