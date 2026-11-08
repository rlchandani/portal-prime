/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow-me's pull: the arriving room recalls music it never handed away, then gives up cleanly. */
class MusicMoveFollowTest {
  private val m = MusicMoveMachine
  private val t0 = 1_000_000L
  private val following = m.follow(MusicMoveState.Idle, "a1b2c3d4", "family", "Family Area", t0).state

  @Test fun followFromIdleRecallsTheSourceRoom() {
    assertEquals(MusicMoveState.Recalling("a1b2c3d4", "family", "Family Area", t0, follow = true), following)
    assertTrue(m.isRecallOffer(following, "family", "a1b2c3d4"))
    assertFalse(m.isRecallOffer(following, "kitchen", "a1b2c3d4"))
  }

  @Test fun followFromHandedToThatRoomIsAPlainBringBack() {
    val handed = MusicMoveState.Handed("family", "Family Area")
    assertEquals(MusicMoveState.Recalling("a1b2c3d4", "family", "Family Area", t0),
        m.follow(handed, "a1b2c3d4", "family", "Family Area", t0).state)
  }

  @Test fun followDoesNothingWhileAnotherMoveIsOut() {
    val offering = m.start(MusicMoveState.Idle, "ffff0000", "kitchen", "Kitchen", t0).state
    assertEquals(offering, m.follow(offering, "a1b2c3d4", "family", "Family Area", t0).state)
    assertEquals(following, m.follow(following, "ffff0000", "kitchen", "Kitchen", t0).state)
    val handedElsewhere = MusicMoveState.Handed("kitchen", "Kitchen")
    assertEquals(handedElsewhere, m.follow(handedElsewhere, "a1b2c3d4", "family", "Family Area", t0).state)
  }

  @Test fun followTimeoutReturnsToIdleNotHanded() {
    val step = m.tick(following, t0 + MusicMoveMachine.ANSWER_MS)
    assertEquals(MusicMoveState.Idle, step.state)
    assertEquals("Family Area didn't answer.", step.toast)
  }

  @Test fun followRefusalReturnsToIdle() {
    val ack = MusicHandoffWire.Ack("a1b2c3d4", "living", MusicHandoffStatus.FAILED, "Family Area")
    val step = m.ack(following, "family", ack)
    assertEquals(MusicMoveState.Idle, step.state)
    assertEquals("Family Area couldn't send the music.", step.toast)
    assertFalse(step.pauseLocal)
  }

  @Test fun followNothingPlayingReturnsToIdle() {
    val ack = MusicHandoffWire.Ack("a1b2c3d4", "living", MusicHandoffStatus.NOTHING, "Family Area")
    assertEquals(MusicMoveState.Idle, m.ack(following, "family", ack).state)
  }

  @Test fun followEndsWhenTheMusicPlaysHere() {
    assertEquals(MusicMoveState.Idle, m.playingHere(following))
  }

  @Test fun followBannerSaysWhereTheMusicComesFrom() {
    assertEquals("Bringing the music from Family Area…", m.banner(following))
  }
}
