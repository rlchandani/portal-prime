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
 * Pinning codes and home-link codes are separate namespaces: a pinning code can never complete a
 * home link and a home code can never pin a Portal, whichever screen it is typed on, and even
 * if the screen's own check is bypassed (every hash, MAC, PAKE base point and signed text is
 * domain-separated).
 */
class HomeLinkDomainTest {
  private val random = SecureRandom()
  private val now = 1_000L
  private val homeA = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
  private val homeB = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
  private val portalA = HomeLinkSelf(HomeLinkIdentity.generate(random), "Family Area", "family-1")
  private val portalB = HomeLinkSelf(HomeLinkIdentity.generate(random), "Office", "office-1")

  @Test fun pinningCodesStartWithTheirPrefixAndHomeCodesNever() {
    repeat(300) {
      val pin = HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN)
      val home = HomeLinkCode.generate(random)
      assertEquals(HomeLinkDomain.PIN_PREFIX, pin.value[0])
      assertNotEquals(HomeLinkDomain.PIN_PREFIX, home.value[0])
      assertEquals(HomeLinkDomain.PORTAL_PIN, pin.domain)
      assertEquals(HomeLinkDomain.HOME, home.domain)
    }
  }

  @Test fun eachScreenRefusesTheOtherCeremonysCode() {
    assertNull(HomeLinkCode.parse("PK7-M2Q-9X4A"))
    assertNotNull(HomeLinkCode.parse("PK7-M2Q-9X4A", HomeLinkDomain.PORTAL_PIN))
    assertNull(HomeLinkCode.parse("MPT-7Q4-K2PA", HomeLinkDomain.PORTAL_PIN))
    assertNotNull(HomeLinkCode.parse("MPT-7Q4-K2PA"))
  }

  @Test fun everyLabelDiffersBetweenTheDomains() {
    val home = HomeLinkDomain.HOME
    val pin = HomeLinkDomain.PORTAL_PIN
    assertFalse(home.nameplateSalt.contentEquals(pin.nameplateSalt))
    assertFalse(home.pakeDst.contentEquals(pin.pakeDst))
    assertFalse(home.sidLabel.contentEquals(pin.sidLabel))
    assertFalse(home.iskLabel.contentEquals(pin.iskLabel))
    assertFalse(home.commitLabel.contentEquals(pin.commitLabel))
    assertNotEquals(home.reveal, pin.reveal)
    assertNotEquals(home.open, pin.open)
    // Home linking keeps the labels it shipped with.
    assertArrayEquals(HomeLinkPake.DST, home.pakeDst)
  }

  @Test fun thePakeBasePointAndSessionKeysDependOnTheDomain() {
    val mailbox = "0".repeat(32)
    assertNotEquals(HomeLinkPake.generator("7Q4K2PA", mailbox, HomeLinkDomain.HOME),
        HomeLinkPake.generator("7Q4K2PA", mailbox, HomeLinkDomain.PORTAL_PIN))
    val a = HomeLinkPake.start("7Q4K2PA", mailbox, random, HomeLinkDomain.HOME)
    val b = HomeLinkPake.start("7Q4K2PA", mailbox, random, HomeLinkDomain.PORTAL_PIN)
    assertFalse(HomeLinkPake.finish(a, b.share)!!.contentEquals(HomeLinkPake.finish(b, a.share)!!))
    val shared = ByteArray(32) { 7 }
    val home = HomeLinkSession.derive(mailbox, a.share, b.share, shared, HomeLinkDomain.HOME)
    val pin = HomeLinkSession.derive(mailbox, a.share, b.share, shared, HomeLinkDomain.PORTAL_PIN)
    assertNotEquals(home.sid, pin.sid)
    val commit = HomeLinkPairingProof.withMac(home, HomeLinkMessage.Commit(mailbox, b.share, "c"))
    assertTrue(HomeLinkPairingProof.fromPeer(home, mailbox, commit))
    assertFalse(HomeLinkPairingProof.fromPeer(pin, mailbox, commit))
  }

  @Test fun theSameNameplateSymbolsMeetInDifferentMailboxes() {
    // Same nameplate text, different domain salt (as if the prefix rule were bypassed).
    val pin = HomeLinkCode.parse("PK7M2Q9X4A", HomeLinkDomain.PORTAL_PIN)!!
    val salted = HomeLinkHash.hex(HomeLinkHash.hmac(HomeLinkDomain.HOME.nameplateSalt, HomeLinkHash.utf8(pin.nameplate))).take(32)
    assertNotEquals(salted, HomeLinkCode.mailboxId(pin))
  }

  /** A pinning code typed into a home link (bypassing the screen's parse): the home inviter never answers. */
  @Test fun aPinningCodeCantCompleteAHomeLink() {
    val invite = HomeLinkInvite(HomeLinkCode.parse("MK7M2Q9X4A")!!, 0L)
    val typed = HomeLinkCode.parse("PK7M2Q9X4A", HomeLinkDomain.PORTAL_PIN)!!
    val r = HomeLinkLoopback.exchange(invite, typed, homeA, portalB, random, now)
    assertFalse(r.inviter is HomeLinkInviterState.Comparing)
    assertFalse(r.joiner is HomeLinkJoinerState.Comparing)
    assertCrossDomainCommitIsAWrongTry(invite, typed, portalB)
  }

  /** A home code typed into a pinning: the pinning inviter never answers. */
  @Test fun aHomeCodeCantPinAPortal() {
    val invite = HomeLinkInvite(HomeLinkCode.parse("PK7M2Q9X4A", HomeLinkDomain.PORTAL_PIN)!!, 0L)
    val typed = HomeLinkCode.parse("MK7M2Q9X4A")!!
    val r = HomeLinkLoopback.exchange(invite, typed, portalA, homeB, random, now)
    assertFalse(r.inviter is HomeLinkInviterState.Comparing)
    assertFalse(r.joiner is HomeLinkJoinerState.Comparing)
    assertCrossDomainCommitIsAWrongTry(invite, typed, homeB)
  }

  /** Even a commit relabelled to the inviter's mailbox fails the PAKE MAC and counts as a wrong try. */
  private fun assertCrossDomainCommitIsAWrongTry(invite: HomeLinkInvite, typed: HomeLinkCode, joiner: HomeLinkSelf) {
    val inviter = HomeLinkInviter.start(invite, random)
    val hello = inviter.outgoing as HomeLinkMessage.Hello
    val j = HomeLinkJoiner.start(typed, random)
    val relabelled = hello.copy(mailbox = HomeLinkCode.mailboxId(typed))
    val commit = HomeLinkJoiner.receive(j, joiner, relabelled, random).outgoing as HomeLinkMessage.Commit
    val step = HomeLinkInviter.receive(inviter.state, if (invite.code.domain == HomeLinkDomain.HOME) homeA else portalA,
        commit.copy(mailbox = hello.mailbox), now)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
    assertEquals(1, (step.state as HomeLinkInviterState.Waiting).failedTries)
  }

  @Test fun pinningBindsBothDeviceIdsAndHomeLinkingCarriesNone() {
    val invite = HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), 0L)
    val ok = HomeLinkLoopback.exchange(invite, invite.code, portalA, portalB, random, now)
    assertEquals("office-1", (ok.inviter as HomeLinkInviterState.Comparing).peer.deviceId)
    assertEquals("family-1", (ok.joiner as HomeLinkJoinerState.Comparing).peer.deviceId)
    val homeInvite = HomeLinkInvite(HomeLinkCode.generate(random), 0L)
    val home = HomeLinkLoopback.exchange(homeInvite, homeInvite.code, homeA, homeB, random, now)
    assertEquals("", (home.inviter as HomeLinkInviterState.Comparing).peer.deviceId)
  }

  /** A Portal (or anything) without a device id can't take part in a pinning, and doesn't crash it. */
  @Test fun pinningWithoutADeviceIdGoesNowhere() {
    val invite = HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), 0L)
    val r = HomeLinkLoopback.exchange(invite, invite.code, portalA, homeB, random, now)
    assertEquals(HomeLinkJoinerState.Failed(HomeLinkFailure.TAMPERED), r.joiner)
    assertTrue(r.inviter is HomeLinkInviterState.Waiting)
  }

  /** A device id is refused in a home link, and an unsigned one can't be swapped into a pinning reveal. */
  @Test fun deviceIdsAreSignedAndMacd() {
    val invite = HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), 0L)
    val inviter = HomeLinkInviterSession.start(invite, portalA, random)
    val j0 = HomeLinkJoiner.start(invite.code, random)
    val s1 = HomeLinkJoiner.receive(j0, portalB, inviter.hello, random)
    val reveal = inviter.receive(s1.outgoing!!, now).outgoing as HomeLinkMessage.Reveal
    assertEquals("family-1", reveal.device)
    val swapped = HomeLinkJoiner.receive(s1.state, portalB, reveal.copy(device = "kitchen-1"), random)
    assertEquals(HomeLinkIgnored.WRONG_CODE, swapped.ignored)
    val wire = HomeLinkWire.parse(HomeLinkWire.encode(reveal)) as HomeLinkMessage.Reveal
    assertEquals(reveal, wire)
    assertNull(HomeLinkWire.parse(HomeLinkWire.encode(reveal).replace("\"family-1\"", "\"bad id!\"")))
    assertNull(HomeLinkWire.parse(HomeLinkWire.encode(reveal).replace("\"family-1\"", "\"${CallPhoneId.of("x")}\"")))
  }
}
