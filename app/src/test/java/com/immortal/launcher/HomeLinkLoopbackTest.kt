/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/** The measurement-build "Link homes test": both homes on one Portal, messages through the wire codec. */
class HomeLinkLoopbackTest {
  private val random = SecureRandom()
  private val maple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
  private val lake = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
  private val invite = HomeLinkInvite(HomeLinkCode.parse("MPT7Q4K2PA")!!, createdAt = 0L)

  @Test fun theRightCodeLeavesBothSidesComparingTheSamePictures() {
    val r = HomeLinkLoopback.exchange(invite, invite.code, maple, lake, random, now = 1_000L)
    val i = r.inviter as HomeLinkInviterState.Comparing
    val j = r.joiner as HomeLinkJoinerState.Comparing
    assertEquals(i.safety, j.safety)
    assertEquals("Lakeview", i.peer.name)
    assertEquals("Maple House", j.peer.name)
  }

  @Test fun aWrongCodeGetsNoAnswerAndTheInviteStaysOpen() {
    val r = HomeLinkLoopback.exchange(invite, HomeLinkCode.parse("MPT7Q4K2PB")!!, maple, lake, random, now = 1_000L)
    val waiting = r.inviter as HomeLinkInviterState.Waiting
    assertEquals(invite, waiting.invite)
    assertEquals(1, waiting.failedTries)
    assertTrue(r.joiner is HomeLinkJoinerState.Committing)
  }

  /** The test screen keeps one inviter per invite, so wrong codes add up and the third burns it. */
  @Test fun wrongCodesAddUpAcrossJoinsOfOneInvite() {
    val session = HomeLinkInviterSession.start(invite, maple, random)
    val wrong = HomeLinkCode.parse("MPT7Q4K2PB")!!
    repeat(HomeLinkInviter.MAX_TRIES - 1) { n ->
      val r = HomeLinkLoopback.exchange(session, wrong, lake, random, now = 1_000L)
      assertEquals(n + 1, (r.inviter as HomeLinkInviterState.Waiting).failedTries)
    }
    val burned = HomeLinkLoopback.exchange(session, wrong, lake, random, now = 1_000L)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES), burned.inviter)
    val late = HomeLinkLoopback.exchange(session, invite.code, lake, random, now = 1_000L)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES), late.inviter)
  }

  @Test fun anExpiredInviteFails() {
    val r = HomeLinkLoopback.exchange(invite, invite.code, maple, lake, random, now = invite.expiresAt)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), r.inviter)
    assertTrue(r.joiner is HomeLinkJoinerState.Committing)
  }

  @Test fun connectedWordsNameTheOtherHome() {
    assertEquals("Lakeview is connected", HomeLinkCopy.connectedTitle("Lakeview"))
    assertTrue(HomeLinkCopy.connectedBody("Lakeview").startsWith("Lakeview can call you now."))
  }
}
