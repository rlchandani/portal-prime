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

/**
 * Linking two homes over an untrusted mailbox: commit → reveal → open → compare pictures.
 * The "relay" here is the test itself, so it can drop, replay, forge and swap messages.
 */
class HomeLinkPairingTest {
  private val random = SecureRandom()
  private val maple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
  private val lake = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
  private val code = HomeLinkCode.parse("MP7Q4K2PA")!!
  private val invite = HomeLinkInvite(code, createdAt = 0L)
  private val now = 60_000L

  private fun commit(self: HomeLinkSelf = lake, c: HomeLinkCode = code) = HomeLinkJoiner.start(c, self, random)

  /** Runs the three messages and returns both sides comparing. */
  private fun toComparing(): Pair<HomeLinkInviterState, HomeLinkJoinerState> {
    val j1 = commit()
    val i1 = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, j1.outgoing!!, now)
    val j2 = HomeLinkJoiner.receive(j1.state, lake, i1.outgoing!!)
    val i2 = HomeLinkInviter.receive(i1.state, maple, j2.outgoing!!, now)
    return i2.state to j2.state
  }

  @Test fun happyPathBothScreensShowTheSamePictures() {
    val (i, j) = toComparing()
    i as HomeLinkInviterState.Comparing
    j as HomeLinkJoinerState.Comparing
    assertEquals(i.safety, j.safety)
    assertEquals("Lakeview", i.peer.name)
    assertEquals(lake.identity.publicKey, i.peer.publicKey)
    assertEquals("Maple House", j.peer.name)
    assertEquals(maple.identity.publicKey, j.peer.publicKey)
  }

  @Test fun theyMatchLinksBothHomesWithPinnedKeys() {
    val (i, j) = toComparing()
    val linkedI = HomeLinkInviter.confirm(i, matched = true)
    val linkedJ = HomeLinkJoiner.confirm(j, matched = true)
    assertEquals(lake.identity.publicKey, (linkedI as HomeLinkInviterState.Linked).peer.publicKey)
    assertEquals(maple.identity.publicKey, (linkedJ as HomeLinkJoinerState.Linked).peer.publicKey)
  }

  @Test fun theyDontMatchStopsAndKeepsNothing() {
    val (i, j) = toComparing()
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.MISMATCH), HomeLinkInviter.confirm(i, matched = false))
    assertEquals(HomeLinkJoinerState.Failed(HomeLinkFailure.MISMATCH), HomeLinkJoiner.confirm(j, matched = false))
  }

  @Test fun confirmOutsideComparingChangesNothing() {
    val waiting = HomeLinkInviter.start(invite)
    assertSame(waiting, HomeLinkInviter.confirm(waiting, matched = true))
    val committing = commit().state
    assertSame(committing, HomeLinkJoiner.confirm(committing, matched = true))
  }

  @Test fun commitSaysNothingAboutTheJoiner() {
    val wire = HomeLinkWire.encode(commit().outgoing!!)
    assertFalse(wire.contains("Lakeview"))
    assertFalse(wire.contains(lake.identity.publicKey))
  }

  @Test fun aWrongCodeIsIgnoredAndTheInviteStaysOpen() {
    val stranger = commit(c = HomeLinkCode.parse("MP7Q4K2PB")!!).outgoing!!
    val waiting = HomeLinkInviter.start(invite)
    val step = HomeLinkInviter.receive(waiting, maple, stranger, now)
    assertSame(waiting, step.state)
    assertNull(step.outgoing)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
  }

  @Test fun theCodeWorksOnce() {
    val first = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, commit().outgoing!!, now)
    val second = HomeLinkInviter.receive(first.state, maple, commit(HomeLinkSelf(HomeLinkIdentity.generate(random), "Eve")).outgoing!!, now)
    assertSame(first.state, second.state)
    assertNull(second.outgoing)
    assertEquals(HomeLinkIgnored.ALREADY_USED, second.ignored)
  }

  @Test fun anExpiredInviteFails() {
    val step = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, commit().outgoing!!, invite.expiresAt)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), step.state)
    assertNull(step.outgoing)
  }

  @Test fun anOpenThatBreaksItsCommitmentFails() {
    val j1 = commit()
    val i1 = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, j1.outgoing!!, now)
    // A different joiner (same code) answers the reveal: its key was never committed.
    val impostor = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
    val other = commit(impostor)
    val open = HomeLinkJoiner.receive(other.state, impostor, i1.outgoing!!).outgoing!!
    val i2 = HomeLinkInviter.receive(i1.state, maple, open, now)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TAMPERED), i2.state)
  }

  @Test fun aForgedRevealSignatureFails() {
    val j1 = commit()
    val reveal = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, j1.outgoing!!, now).outgoing as HomeLinkMessage.Reveal
    val forged = HomeLinkPairingProof.withMac(code, reveal.copy(name = "Evil House"))
    val j2 = HomeLinkJoiner.receive(j1.state, lake, forged)
    assertEquals(HomeLinkJoinerState.Failed(HomeLinkFailure.TAMPERED), j2.state)
    assertNull(j2.outgoing)
  }

  @Test fun aRevealWithoutTheCodeIsIgnored() {
    val j1 = commit()
    val reveal = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, j1.outgoing!!, now).outgoing as HomeLinkMessage.Reveal
    val step = HomeLinkJoiner.receive(j1.state, lake, reveal.copy(mac = "AAAA"))
    assertSame(j1.state, step.state)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
  }

  @Test fun outOfOrderMessagesAreIgnored() {
    val j1 = commit()
    val waiting = HomeLinkInviter.start(invite)
    val i1 = HomeLinkInviter.receive(waiting, maple, j1.outgoing!!, now)
    // The joiner's own commit echoed back, and a replayed commit to the inviter.
    assertEquals(HomeLinkIgnored.UNEXPECTED, HomeLinkJoiner.receive(j1.state, lake, j1.outgoing!!).ignored)
    val (i, _) = toComparing()
    assertEquals(HomeLinkIgnored.ALREADY_USED, HomeLinkInviter.receive(i, maple, j1.outgoing!!, now).ignored)
    assertEquals(HomeLinkIgnored.UNEXPECTED, HomeLinkInviter.receive(waiting, maple, i1.outgoing!!, now).ignored)
  }

  @Test fun aRelayInTheMiddleShowsDifferentPictures() {
    // Mallory runs both halves: she joins Maple's invite as "Lakeview" and invites Lakeview herself.
    val mallory = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
    val malloryAsMaple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
    val m1 = commit(mallory)
    val i1 = HomeLinkInviter.receive(HomeLinkInviter.start(invite), maple, m1.outgoing!!, now)
    val m2 = HomeLinkJoiner.receive(m1.state, mallory, i1.outgoing!!)
    val atMaple = HomeLinkInviter.receive(i1.state, maple, m2.outgoing!!, now).state as HomeLinkInviterState.Comparing

    val j1 = commit()
    val mi1 = HomeLinkInviter.receive(HomeLinkInviter.start(invite), malloryAsMaple, j1.outgoing!!, now)
    val atLake = HomeLinkJoiner.receive(j1.state, lake, mi1.outgoing!!).state as HomeLinkJoinerState.Comparing
    assertNotEquals(atMaple.safety, atLake.safety)
  }

  @Test fun failedStatesIgnoreEverything() {
    val failed = HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED)
    assertEquals(HomeLinkIgnored.UNEXPECTED, HomeLinkInviter.receive(failed, maple, commit().outgoing!!, now).ignored)
  }

  @Test fun tickExpiresOnlyAnOpenInvite() {
    val waiting = HomeLinkInviter.start(invite)
    assertSame(waiting, HomeLinkInviter.tick(waiting, invite.expiresAt - 1))
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), HomeLinkInviter.tick(waiting, invite.expiresAt))
    val (comparing, _) = toComparing()
    assertSame(comparing, HomeLinkInviter.tick(comparing, invite.expiresAt + 1))
  }

  @Test fun bothSidesDeriveTheSameLinkKeyFromThePinnedKeys() {
    val (i, j) = toComparing()
    val atMaple = maple.identity.linkKey((i as HomeLinkInviterState.Comparing).peer.publicKey, maple.identity.publicKey)
    val atLake = lake.identity.linkKey((j as HomeLinkJoinerState.Comparing).peer.publicKey, maple.identity.publicKey)
    assertArrayEquals(atMaple, atLake)
  }

  @Test fun homeNamesMustBeShowable() {
    assertThrows(IllegalArgumentException::class.java) { HomeLinkSelf(maple.identity, "") }
    assertThrows(IllegalArgumentException::class.java) { HomeLinkSelf(maple.identity, "x".repeat(HomeLinkWire.MAX_NAME + 1)) }
  }
}
