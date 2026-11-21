/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.MemorySecureStore
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * Two Portals pin each other over the LAN mailbox ([PortalPinMailbox] behind [PortalPinRoute]),
 * and hostile household members (a phone with the household key, a Portal at the wrong address,
 * a third Portal butting in, a host lying about its id) get nothing or only burn the code.
 */
class PortalPinTransportTest {
  private val random = SecureRandom()
  private val key = ByteArray(32) { (it * 3).toByte() }
  private var now = 1_700_000_000_000L
  private val addresses = mutableMapOf("family-1" to "10.0.0.1", "office-1" to "10.0.0.2", "kitchen-1" to "10.0.0.3")
  private val logs = ArrayList<String>()
  private val mailbox = PortalPinMailbox { now }
  private val route = PortalPinRoute({ key }, { "family-1" }, { now }, HouseholdMessage.ReplayGuard(), { addresses[it] }, mailbox,
      { logs += it })
  private val familyKey = HomeLinkIdentity.generate(random)
  private val officeKey = HomeLinkIdentity.generate(random)
  private val family = HomeLinkSelf(familyKey, "Family Area", "family-1")
  private val office = HomeLinkSelf(officeKey, "Office", "office-1")
  private val familyPeer = PortalPinPeer("family-1", "10.0.0.1", 8080)
  private val peerChains = ArrayList<Pair<String, List<ByteArray>>>()
  private val ownChain = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))

  private fun nonce() = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)

  /** Calls the family route as [from] at [addr]; only the family Portal answers. */
  private fun transport(from: String = "office-1", addr: String? = null, signKey: ByteArray = key) = PortalPinTransport { peer, body ->
    if (peer.id != "family-1") return@PortalPinTransport null
    val resp = route.handle(request(body, PortalPinWire.requestHeader(signKey, from, nonce(), now, body), addr ?: addresses[from] ?: ""))
    resp.status to FleetReq.text(resp)
  }

  private fun request(body: String, header: String?, addr: String, length: Int = body.length) =
      FleetHttpServer.Request("POST", PortalPinWire.PATH, "", header?.let { mapOf(PortalPinWire.HEADER to it) } ?: emptyMap(),
          ByteArrayInputStream(body.toByteArray()), length, remoteAddress = addr)

  private fun host(code: HomeLinkCode = HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN)): HomeLinkInviterSession {
    val invite = HomeLinkInvite(code, now, HomeLinkInvite.PIN_LIFETIME_MS)
    val session = HomeLinkInviterSession.start(invite, family, random)
    assertTrue(mailbox.open(PortalPinMailbox.Host(session, { ownChain }, { from, c -> peerChains += from to c }), invite.expiresAt))
    return session
  }

  private fun code(session: HomeLinkInviterSession) = (session.state as HomeLinkInviterState.Waiting).invite.code

  @Test fun twoPortalsPinEachOtherOverTheLan() {
    val session = host()
    val code = code(session)
    val join = PortalPinJoin(transport(), office, random)
    val r = join.run(code, listOf(familyPeer)) as PortalPinJoin.Result.Comparing
    val i = session.state as HomeLinkInviterState.Comparing
    assertEquals(i.safety, r.safety)
    assertEquals(HomeLinkPeer("Family Area", familyKey.publicKey, "family-1"), r.peer)
    assertEquals(HomeLinkPeer("Office", officeKey.publicKey, "office-1"), i.peer)
    val familyRoster = PortalRosterStore(MemorySecureStore()) {}
    val officeRoster = PortalRosterStore(MemorySecureStore()) {}
    assertEquals(PortalPinOutcome.PINNED, PortalPinning.confirm(familyRoster, HomeLinkInviter.confirm(i, true).let {
      (it as HomeLinkInviterState.Linked).peer }, family, now))
    assertEquals(PortalPinOutcome.PINNED, PortalPinning.confirm(officeRoster, r.peer, office, now))
    // Then attestation chains are swapped once, by the joiner only.
    val theirs = join.attest(r.host, r.mailbox, listOf(byteArrayOf(9)))
    assertEquals(ownChain.map { it.toList() }, theirs.map { it.toList() })
    assertEquals("office-1", peerChains.single().first)
    assertTrue(join.attest(r.host, r.mailbox, listOf(byteArrayOf(9))).isEmpty())
    assertFalse(logs.any { it.contains(code.value) || it.contains(code.secret) })
  }

  @Test fun aWrongCodeIsNoAnswerAndTheThirdBurnsTheCodeAndClosesTheMailbox() {
    val right = HomeLinkCode.parse("PK7M2Q9X4A", HomeLinkDomain.PORTAL_PIN)!!
    val session = host(right)
    val wrong = HomeLinkCode.parse("PK7M2Q9X4B", HomeLinkDomain.PORTAL_PIN)!!
    val join = PortalPinJoin(transport(), office, random)
    assertEquals(PortalPinJoin.Result.NoAnswer, join.run(wrong, listOf(familyPeer)))
    assertEquals(PortalPinJoin.Result.NoAnswer, join.run(wrong, listOf(familyPeer)))
    assertEquals(PortalPinJoin.Result.NoAnswer, join.run(wrong, listOf(familyPeer)))
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES), session.state)
    assertFalse(mailbox.isOpen(HomeLinkCode.mailboxId(right)))
    // Even the right code now finds nothing.
    assertEquals(PortalPinJoin.Result.NotFound, join.run(right, listOf(familyPeer)))
  }

  /** The mailbox's own limit: at most 3 commits per nameplate, whatever the inviter makes of them. */
  @Test fun theMailboxTakesAtMostThreeCommits() {
    val session = host()
    val box = HomeLinkCode.mailboxId(code(session))
    val t = transport()
    repeat(PortalPinMailbox.MAX_JOINS) {
      val commit = HomeLinkMessage.Commit(box, HomeLinkPake.start("0000000", box, random, HomeLinkDomain.PORTAL_PIN).share, "c", "bWFj")
      assertEquals(403, t.post(familyPeer, PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.SEND, box, HomeLinkWire.encode(commit))))!!.first)
    }
    assertFalse(mailbox.isOpen(box))
  }

  @Test fun aPhoneWithTheHouseholdKeyIsRefusedBeforeTheMailbox() {
    val session = host()
    val phone = CallPhoneId.of("pixel")
    addresses[phone] = "10.0.0.9"
    val r = PortalPinJoin(transport(from = phone), HomeLinkSelf(HomeLinkIdentity.generate(random), "Phone", "phone-ish"), random)
        .run(code(session), listOf(familyPeer))
    assertEquals(PortalPinJoin.Result.NotFound, r)
    assertTrue(session.state is HomeLinkInviterState.Waiting)
    assertTrue(logs.any { it.contains("forbidden") })
  }

  @Test fun aPortalOffItsAnnouncedAddressOrWithoutTheHouseholdKeyIsRefused() {
    val session = host()
    assertEquals(PortalPinJoin.Result.NotFound, PortalPinJoin(transport(addr = "10.0.0.77"), office, random).run(code(session), listOf(familyPeer)))
    assertEquals(PortalPinJoin.Result.NotFound,
        PortalPinJoin(transport(signKey = ByteArray(32) { 1 }), office, random).run(code(session), listOf(familyPeer)))
    addresses.remove("office-1")
    assertEquals(PortalPinJoin.Result.NotFound, PortalPinJoin(transport(addr = "10.0.0.2"), office, random).run(code(session), listOf(familyPeer)))
    assertTrue(session.state is HomeLinkInviterState.Waiting)
  }

  @Test fun headersAreCheckedForReplaySwappedBodiesAndSize() {
    val session = host()
    val body = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.HELLO, HomeLinkCode.mailboxId(code(session))))
    val header = PortalPinWire.requestHeader(key, "office-1", nonce(), now, body)
    assertEquals(200, route.handle(request(body, header, "10.0.0.2")).status)
    assertEquals(401, route.handle(request(body, header, "10.0.0.2")).status)
    val other = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.HELLO, "0".repeat(32)))
    assertEquals(401, route.handle(request(other, PortalPinWire.requestHeader(key, "office-1", nonce(), now, body), "10.0.0.2")).status)
    assertEquals(401, route.handle(request(body, null, "10.0.0.2")).status)
    assertEquals(413, route.handle(request(body, header, "10.0.0.2", length = PortalPinWire.MAX_BODY + 1)).status)
    assertEquals(405, route.handle(FleetHttpServer.Request("GET", PortalPinWire.PATH, "", emptyMap(), ByteArrayInputStream(ByteArray(0)), 0)).status)
    // Our own id calling us is not a peer.
    val selfHeader = PortalPinWire.requestHeader(key, "family-1", nonce(), now, body)
    assertEquals(401, route.handle(request(body, selfHeader, "10.0.0.1")).status)
  }

  /** Once one Portal's commit was answered, nobody else can send the open or take the attestation swap. */
  @Test fun aThirdPortalCantButtIntoAPinning() {
    val session = host()
    val code = code(session)
    val box = HomeLinkCode.mailboxId(code)
    val j0 = HomeLinkJoiner.start(code, random)
    val s1 = HomeLinkJoiner.receive(j0, office, session.hello, random)
    val t = transport()
    val revealText = PortalPinWire.parseReply(t.post(familyPeer, PortalPinWire.encode(
        PortalPinWire.Request(PortalPinWire.Op.SEND, box, HomeLinkWire.encode(s1.outgoing!!))))!!.second)!!.message!!
    val s2 = HomeLinkJoiner.receive(s1.state, office, HomeLinkWire.parse(revealText)!!, random)
    val openBody = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.SEND, box, HomeLinkWire.encode(s2.outgoing!!)))
    val kitchen = transport(from = "kitchen-1")
    assertEquals(403, kitchen.post(familyPeer, openBody)!!.first)
    val commitAgain = HomeLinkJoiner.receive(HomeLinkJoiner.start(code, random), office, session.hello, random).outgoing!!
    assertEquals(409, kitchen.post(familyPeer, PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.SEND, box, HomeLinkWire.encode(commitAgain))))!!.first)
    assertEquals(403, kitchen.post(familyPeer, PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.ATTEST, box, chain = listOf(byteArrayOf(1)))))!!.first)
    assertEquals(200, t.post(familyPeer, openBody)!!.first)
    assertTrue(session.state is HomeLinkInviterState.Comparing)
  }

  /** The device id a Portal signs must be the household id it calls from, both ways. */
  @Test fun signedDeviceIdsMustMatchTheHouseholdIds() {
    val session = host()
    val first = code(session)
    // Office signs "kitchen-1" but calls as office-1: its open is refused.
    val liar = HomeLinkSelf(officeKey, "Office", "kitchen-1")
    assertEquals(PortalPinJoin.Result.NoAnswer, PortalPinJoin(transport(), liar, random).run(first, listOf(familyPeer)))
    assertFalse(session.state is HomeLinkInviterState.Comparing)
    // A host whose reveal names another id than the one we reached it under is tampering.
    mailbox.close(HomeLinkCode.mailboxId(first))
    val fake = HomeLinkInviterSession.start(HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), now),
        HomeLinkSelf(familyKey, "Family Area", "living-1"), random)
    mailbox.open(PortalPinMailbox.Host(fake, { emptyList() }, { _, _ -> }), now + 60_000L)
    val code = (fake.state as HomeLinkInviterState.Waiting).invite.code
    assertEquals(PortalPinJoin.Result.Tampered, PortalPinJoin(transport(), office, random).run(code, listOf(familyPeer)))
  }

  @Test fun mailboxesExpireAndMissesAreRateLimited() {
    val session = host()
    now += PortalPinMailbox.TTL_MS
    assertEquals(PortalPinJoin.Result.NotFound, PortalPinJoin(transport(), office, random).run(code(session), listOf(familyPeer)))
    val t = transport()
    val miss = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.HELLO, "1".repeat(32)))
    val statuses = (1..PortalPinMailbox.MAX_MISSES + 1).map { t.post(familyPeer, miss)!!.first }
    assertEquals(404, statuses.first())
    assertEquals(429, statuses.last())
  }

  @Test fun onlyOurOwnInviterOpensABoxAndAtMostTwoAtOnce() {
    host(); host()
    val third = HomeLinkInviterSession.start(HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), now), family, random)
    assertFalse(mailbox.open(PortalPinMailbox.Host(third, { emptyList() }, { _, _ -> }), now + 60_000L))
    // A hello posted by a peer is not a way to open one.
    val box = HomeLinkCode.mailboxId(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN))
    val hello = HomeLinkWire.encode(HomeLinkMessage.Hello(box, HomeLinkPake.start("0000000", box, random).share))
    assertEquals(404, transport().post(familyPeer, PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.SEND, box, hello)))!!.first)
  }

  @Test fun theWireIsStrict() {
    val box = "a".repeat(32)
    val ok = PortalPinWire.Request(PortalPinWire.Op.SEND, box, "{}")
    assertEquals(ok, PortalPinWire.parse(PortalPinWire.encode(ok)))
    assertNull(PortalPinWire.parse("""{"v":1,"op":"drop","b":"$box"}"""))
    assertNull(PortalPinWire.parse("""{"v":2,"op":"hello","b":"$box"}"""))
    assertNull(PortalPinWire.parse("""{"v":1,"op":"hello","b":"XYZ"}"""))
    assertNull(PortalPinWire.parse("""{"v":1,"op":"send","b":"$box"}"""))
    assertNull(PortalPinWire.parse("""{"v":1,"op":"attest","b":"$box","c":[]}"""))
    val tooLong = (1..PortalAttestation.MAX_CHAIN + 1).joinToString(",") { "\"AQ==\"" }
    assertNull(PortalPinWire.parse("""{"v":1,"op":"attest","b":"$box","c":[$tooLong]}"""))
    assertNull(PortalPinWire.parse("""{"v":1,"op":"attest","b":"$box","c":["!!"]}"""))
    assertNull(PortalPinWire.parse("not json"))
    assertNull(PortalPinWire.parseReply("""{"v":1,"m":5}"""))
    assertEquals(PortalPinWire.Reply(), PortalPinWire.parseReply("""{"v":1}"""))
  }
}
