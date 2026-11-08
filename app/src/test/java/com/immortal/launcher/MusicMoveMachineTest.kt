/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The source Portal's side of "Move to…": offer → fetched → acked → paused; anything else keeps playing. */
class MusicMoveMachineTest {
  private val m = MusicMoveMachine
  private val t0 = 1_000_000L
  private val offering = m.start(MusicMoveState.Idle, "a1b2c3d4", "living", "Living Area", t0).state

  private fun ack(status: MusicHandoffStatus, id: String = "a1b2c3d4", to: String = "family") =
      MusicHandoffWire.Ack(id, to, status, "Living Area")

  @Test fun startOffersOnlyFromIdle() {
    assertEquals(MusicMoveState.Offering("a1b2c3d4", "living", "Living Area", t0), offering)
    val handed = MusicMoveState.Handed("living", "Living Area")
    assertEquals(handed, m.start(handed, "ffff0000", "kitchen", "Kitchen", t0).state)
    assertEquals(offering, m.start(offering, "ffff0000", "kitchen", "Kitchen", t0).state)
  }

  @Test fun playingAckPausesHereAndShowsWhereItPlays() {
    val step = m.ack(offering, "living", ack(MusicHandoffStatus.PLAYING))
    assertEquals(MusicMoveState.Handed("living", "Living Area"), step.state)
    assertTrue(step.pauseLocal)
    assertEquals("Now playing in Living Area", step.toast)
  }

  @Test fun declinesKeepPlayingHereWithTheReason() {
    val cases = mapOf(
        MusicHandoffStatus.DECLINED_QUIET to "Living Area is in quiet hours, so the music stayed here.",
        MusicHandoffStatus.DECLINED_ROUTINE to "A routine is on screen in Living Area, so the music stayed here.",
        MusicHandoffStatus.DECLINED_CALL to "Living Area is on a call, so the music stayed here.",
        MusicHandoffStatus.NEEDS_TAP to "Living Area needs a tap on Play to start. The music is still playing here.",
        MusicHandoffStatus.FAILED to "Living Area couldn't start the music. It's still playing here.")
    cases.forEach { (status, copy) ->
      val step = m.ack(offering, "living", ack(status))
      assertEquals(MusicMoveState.Idle, step.state)
      assertFalse(step.pauseLocal)
      assertEquals(copy, step.toast)
    }
  }

  @Test fun acksFromAnotherRoomOrForAnotherOfferAreIgnored() {
    assertEquals(offering, m.ack(offering, "kitchen", ack(MusicHandoffStatus.PLAYING)).state)
    assertEquals(offering, m.ack(offering, "living", ack(MusicHandoffStatus.PLAYING, id = "99999999")).state)
    assertEquals(MusicMoveState.Idle, m.ack(MusicMoveState.Idle, "living", ack(MusicHandoffStatus.PLAYING)).state)
  }

  @Test fun noFetchInTimeOrNoAnswerInTimeKeepsPlaying() {
    assertEquals(offering, m.tick(offering, t0 + MusicMoveMachine.FETCH_MS - 1).state)
    val unfetched = m.tick(offering, t0 + MusicMoveMachine.FETCH_MS)
    assertEquals(MusicMoveState.Idle, unfetched.state)
    assertEquals("Living Area didn't answer. The music is still playing here.", unfetched.toast)
    val fetched = m.claim(offering, "a1b2c3d4", "living", t0 + 1_000).state
    assertEquals(fetched, m.tick(fetched, t0 + MusicMoveMachine.FETCH_MS + 1).state)
    val late = m.tick(fetched, t0 + MusicMoveMachine.ANSWER_MS)
    assertEquals(MusicMoveState.Idle, late.state)
    assertFalse(late.pauseLocal)
  }

  @Test fun claimIsSingleUseForTheNamedRoomWhileFresh() {
    val first = m.claim(offering, "a1b2c3d4", "living", t0 + 1_000)
    assertTrue(first.allowed)
    assertTrue((first.state as MusicMoveState.Offering).fetched)
    assertFalse(m.claim(first.state, "a1b2c3d4", "living", t0 + 2_000).allowed)
    assertFalse(m.claim(offering, "a1b2c3d4", "kitchen", t0 + 1_000).allowed)
    assertFalse(m.claim(offering, "deadbeef", "living", t0 + 1_000).allowed)
    assertFalse(m.claim(offering, "a1b2c3d4", "living", t0 + MusicMoveMachine.FETCH_MS).allowed)
    assertFalse(m.claim(MusicMoveState.Idle, "a1b2c3d4", "living", t0).allowed)
  }

  @Test fun bringItBackRecallsAndReturnsToIdleWhenItPlaysHere() {
    val handed = MusicMoveState.Handed("living", "Living Area")
    val recalling = m.recall(handed, "beefbeef", t0).state
    assertEquals(MusicMoveState.Recalling("beefbeef", "living", "Living Area", t0), recalling)
    assertTrue(m.isRecallOffer(recalling, "living", "beefbeef"))
    assertFalse(m.isRecallOffer(recalling, "kitchen", "beefbeef"))
    assertFalse(m.isRecallOffer(recalling, "living", "00000000"))
    assertEquals(MusicMoveState.Idle, m.playingHere(recalling))
    assertEquals(MusicMoveState.Idle, m.playingHere(handed))
    assertEquals(offering, m.playingHere(offering))
  }

  @Test fun recallThatFindsNothingOrNoAnswer() {
    val recalling = MusicMoveState.Recalling("beefbeef", "living", "Living Area", t0)
    val nothing = m.ack(recalling, "living", ack(MusicHandoffStatus.NOTHING, id = "beefbeef"))
    assertEquals(MusicMoveState.Idle, nothing.state)
    assertEquals("Nothing is playing in Living Area.", nothing.toast)
    val quiet = m.ack(recalling, "living", ack(MusicHandoffStatus.FAILED, id = "beefbeef"))
    assertEquals(MusicMoveState.Handed("living", "Living Area"), quiet.state)
    val timeout = m.tick(recalling, t0 + MusicMoveMachine.ANSWER_MS)
    assertEquals(MusicMoveState.Handed("living", "Living Area"), timeout.state)
    assertEquals("Living Area didn't answer.", timeout.toast)
  }

  @Test fun bannerCopy() {
    assertEquals("Moving to Living Area…", MusicMoveMachine.banner(offering))
    assertEquals("Starting in Living Area…", MusicMoveMachine.banner(m.claim(offering, "a1b2c3d4", "living", t0).state))
    assertEquals("Playing in Living Area", MusicMoveMachine.banner(MusicMoveState.Handed("living", "Living Area")))
    assertEquals("Bringing it back from Living Area…",
        MusicMoveMachine.banner(MusicMoveState.Recalling("beefbeef", "living", "Living Area", t0)))
    assertNull(MusicMoveMachine.banner(MusicMoveState.Idle))
  }
}
