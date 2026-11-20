/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Phone auth and wire (untrusted), the phone mailbox, group ring/join wire, tiles and Add person. */
class CallPhoneAndGroupWireTest {
  private val portal = "11111111-1111-1111-1111-111111111111"
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val id = "0123456789abcdef"
  private val key = ByteArray(32) { it.toByte() }
  private val token = "session-token-abc"
  private val phoneId = CallPhoneId.of(token)
  private val now = 1_800_000_000_000L

  @Test fun phoneIdsAreTokenBoundAndNeverLookLikeAPortal() {
    assertTrue(CallPhoneId.isPhone(phoneId))
    assertEquals(phoneId, CallPhoneId.of(token))
    assertNotEquals(phoneId, CallPhoneId.of("another-token"))
    assertFalse(CallPhoneId.isPhone(portal))
    assertFalse(CallPhoneId.isPhone("ph0123"))
    assertTrue(DinnerWire.isDeviceId(phoneId))
  }

  private fun header(body: String, path: String = CallPhoneRoute.POLL, from: String = phoneId, ts: Long = now, nonce: String = "a".repeat(24)) =
      CallPhoneAuth.requestHeader(key, from, nonce, ts, path, body)

  @Test fun aSignedPhoneRequestVerifiesOnce() {
    val body = """{"fn":"Sam","v":1}"""
    val guard = HouseholdMessage.ReplayGuard()
    val h = header(body)
    assertEquals(CallPhoneAuth.Request(phoneId, "a".repeat(24)), CallPhoneAuth.verify(h, CallPhoneRoute.POLL, body, key, phoneId, now, guard))
    assertNull("a replay is refused", CallPhoneAuth.verify(h, CallPhoneRoute.POLL, body, key, phoneId, now, guard))
  }

  @Test fun phoneAuthRefusesEveryForgery() {
    val body = """{"fn":"Sam","v":1}"""
    fun verify(h: String?, path: String = CallPhoneRoute.POLL, b: String = body, id: String = phoneId, at: Long = now, k: ByteArray = key) =
        CallPhoneAuth.verify(h, path, b, k, id, at, HouseholdMessage.ReplayGuard())
    assertNull(verify(null))
    assertNull("other body", verify(header(body), b = """{"fn":"Eve","v":1}"""))
    assertNull("other path", verify(header(body), path = CallPhoneRoute.OFFER))
    assertNull("other phone's token", verify(header(body), id = CallPhoneId.of("stolen")))
    assertNull("claims to be a Portal", verify(header(body, from = portal), id = portal))
    assertNull("stale", verify(header(body), at = now + HouseholdMessage.MAX_SKEW_MS + 1))
    assertNull("wrong key", verify(header(body), k = ByteArray(32) { 9 }))
    val wrongType = HouseholdMessage.encodeOrNull(HouseholdMessage("call_offer", phoneId, now, "b".repeat(24),
        JSONObject().put("s", CallPhoneId.sha256Hex(body)).put("p", CallPhoneRoute.POLL)), key)
    assertNull("a Portal call_offer header is not a phone request", verify(wrongType))
  }

  @Test fun responsesAreSignedOverTheRequestNonce() {
    val env = CallPhoneAuth.envelope(key, "n1", """{"ok":true}""")
    assertEquals(CallPhoneAuth.sign(key, CallPhoneAuth.RESPONSE_DOMAIN, "n1", """{"ok":true}"""), env.getString("sig"))
    assertNotEquals(env.getString("sig"), CallPhoneAuth.sign(key, CallPhoneAuth.RESPONSE_DOMAIN, "n2", """{"ok":true}"""))
    assertEquals("the answer domain matches Portal↔Portal answers", CallSignalAuth.signResponse(key, "n1", "p"),
        CallPhoneAuth.sign(key, CallPhoneAuth.ANSWER_DOMAIN, "n1", "p"))
  }

  @Test fun phoneRingsAreCallsOnlyAndRepliesAreLimited() {
    val ring = CallPhoneWire.encodeRing(id, portal, true, "Sam’s phone")
    assertEquals(CallPhoneWire.PhoneRing(id, portal, true, "Sam’s phone"), CallPhoneWire.decodeRing(ring))
    assertNull("phones never drop in", CallPhoneWire.decodeRing(JSONObject(ring.toString()).put("k", CallKind.DROP_IN.wire)))
    assertNull(CallPhoneWire.decodeRing(JSONObject(ring.toString()).remove("v").let { JSONObject(ring.toString()).put("v", "yes") }))
    assertNull(CallPhoneWire.decodeRing(JSONObject(ring.toString()).put("i", "nothex")))
    val reply = JSONObject().put("i", id).put("to", portal).put("r", CallReply.DECLINED.wire)
    assertEquals(CallReply.DECLINED, CallPhoneWire.decodeReply(reply)!!.reply)
    assertNull("a phone can't claim quiet hours", CallPhoneWire.decodeReply(JSONObject(reply.toString()).put("r", CallReply.QUIET.wire)))
    assertNull(CallPhoneWire.decodeReply(JSONObject(reply.toString()).put("r", CallReply.COUNTDOWN.wire)))
    assertEquals("Phone", CallPhoneWire.phoneName(JSONObject()))
    assertEquals(JSONObject().put("i", id).put("to", phoneId).toString(), CallPhoneWire.encodeGo(id, phoneId).toString())
  }

  @Test fun mailboxKeepsFreshMessagesInOrderAndCaps() {
    var q = emptyList<PhoneMessage>()
    repeat(CallPhones.MAX_QUEUE + 5) { q = CallPhones.enqueue(q, PhoneMessage("t$it", "{}", now + it)) }
    assertEquals(CallPhones.MAX_QUEUE, q.size)
    assertEquals("t5", q.first().type)
    assertTrue(CallPhones.fresh(q, now + CallPhones.MESSAGE_TTL_MS + 100).isEmpty())
    val late = CallPhones.enqueue(listOf(PhoneMessage("old", "{}", now)), PhoneMessage("new", "{}", now + CallPhones.MESSAGE_TTL_MS + 1))
    assertEquals(listOf("new"), late.map { it.type })
  }

  @Test fun mailboxPollDrainsAndRecordsThePhone() {
    CallPhoneMailbox.post(phoneId, CallWire.RING, JSONObject().put("i", id), now)
    val sam = CallPhoneWire.PollInfo("Sam", emptyMap(), false, 0L)
    val got = CallPhoneMailbox.poll(phoneId, sam, now + 500).messages
    assertEquals(listOf(CallWire.RING), got.map { it.type })
    assertTrue(CallPhoneMailbox.poll(phoneId, sam, now + 600).messages.isEmpty())
    val phone = CallPhoneMailbox.phone(phoneId)!!
    assertTrue(phone.reachable(now + 600 + CallPhones.REACHABLE_MS))
    assertFalse(phone.reachable(now + 601 + CallPhones.REACHABLE_MS))
    assertEquals(CallContact.Kind.PHONE, phone.contact().kind)
  }

  @Test fun directoryKeepsNewestPhonesUpToTheCap() {
    var list = emptyList<CallPhone>()
    repeat(CallPhones.MAX_PHONES + 2) { list = CallPhones.heard(list, CallPhone("ph%016x".format(it), "P$it", now + it)) }
    assertEquals(CallPhones.MAX_PHONES, list.size)
    assertEquals("P${CallPhones.MAX_PHONES + 1}", list.first().name)
    list = CallPhones.heard(list, CallPhone(list.last().id, "Again", now + 99))
    assertEquals("Again", list.first().name)
    assertEquals(CallPhones.MAX_PHONES, list.size)
  }

  @Test fun phoneRingDecisionMapsToItsReply() {
    assertEquals(CallReply.RINGING, CallPhoneRoute.replyFor(IncomingDecision.Ring(false)))
    assertEquals(CallReply.RINGING, CallPhoneRoute.replyFor(IncomingDecision.AutoAnswer))
    assertEquals(CallReply.QUIET, CallPhoneRoute.replyFor(IncomingDecision.Reject(CallReply.QUIET)))
  }

  @Test fun groupRingCarriesMembersAndStillFitsOneDatagram() {
    val members = listOf(CallMemberRef(kitchen, "192.168.1.20", 8723), CallMemberRef(den, "192.168.1.30", 8723))
    val ring = CallWire.Ring(id, portal, CallKind.CALL, true, "Living Area Portal Plus", "192.168.1.10", 8723, members)
    assertEquals(ring, CallWire.decodeRing(CallWire.encode(ring)))
    val wire = HouseholdMessage.encodeOrNull(HouseholdMessage(CallWire.RING, portal, now, "n".repeat(24), CallWire.encode(ring)), key)
    assertNotNull(wire)
  }

  @Test fun groupRingRefusesBadMemberLists() {
    val base = CallWire.encode(CallWire.Ring(id, portal, CallKind.CALL, true, "Den", "10.0.0.2", 8723))
    fun with(g: JSONArray) = CallWire.decodeRing(JSONObject(base.toString()).put("g", g))
    val m = { d: String -> JSONObject().put("d", d).put("fn", "X") }
    assertNull("over the cap", with(JSONArray(listOf(m("a"), m("b"), m("c"), m("d")))))
    assertNull("a phone in the mesh", with(JSONArray(listOf(m(CallPhoneId.of("t"))))))
    assertNull("duplicates", with(JSONArray(listOf(m("a"), m("a")))))
    assertNull("no name", with(JSONArray(listOf(JSONObject().put("d", "a")))))
    val publicHost = with(JSONArray(listOf(m("a").put("h", "8.8.8.8").put("p", 8723))))!!
    assertNull("a public address is dropped", publicHost.members.single().host)
    val drop = CallWire.decodeRing(JSONObject(CallWire.encode(CallWire.Ring(id, portal, CallKind.DROP_IN, true, "Den", "10.0.0.2", 8723))
        .toString()).put("g", JSONArray(listOf(m("a")))))!!
    assertTrue("drop-ins never carry a group", drop.members.isEmpty())
  }

  @Test fun joinRoundTripsAndMarksPhones() {
    val join = CallWire.Join(id, portal, den)
    assertEquals(join, CallWire.decodeJoin(CallWire.encode(join)))
    // Phase 2b: a phone newcomer is allowed and decoded as a phone.
    assertTrue(CallWire.decodeJoin(CallWire.encode(CallWire.Join(id, portal, CallContact(CallPhoneId.of("t"), "Phone"))))!!.newcomer.phone)
    assertNull(CallWire.decodeJoin(JSONObject().put("i", id).put("to", portal)))
  }

  @Test fun helloCarriesAnAddressOnlyWhenValid() {
    val hello = CallWire.Hello("Den", false, false, false, true, false, emptyMap(), "192.168.1.30", 8723)
    assertEquals(hello, CallWire.decodeHello(CallWire.encode(hello)))
    val public = CallWire.decodeHello(CallWire.encode(hello).put("h", "8.8.8.8"))!!
    assertNull(public.host)
    assertEquals(0, public.port)
    assertNull(CallWire.decodeHello(CallWire.encode(hello).put("p", 0))!!.host)
  }

  private fun call(legs: List<CallLeg> = emptyList()) = ActiveCall(id, kitchen, CallDirection.OUTGOING, CallKind.CALL, true,
      CallPhase.LIVE, now, now, cameraOn = true, legs = legs)

  @Test fun tilesPutTheFirstMemberBigAndThisPortalLast() {
    val ringing = CallLeg(den, LegPhase.RINGING, now, offerer = false)
    val tiles = CallTiles.of(call(listOf(ringing)))
    assertEquals(3, tiles.size)
    assertEquals(kitchen, (tiles[0] as CallTile.Member).contact)
    assertEquals(CallTile.State.RINGING, (tiles[1] as CallTile.Member).state)
    assertTrue(tiles[2] is CallTile.Self)
    assertEquals("Camera on · Mic on · 3 of 4", CallTiles.liveBar(call(listOf(ringing))))
  }

  @Test fun arrangementFollowsPeopleAndOrientation() {
    assertEquals(TileArrangement.SOLO, CallTiles.arrangement(2, LayoutClass.LARGE))
    assertEquals(TileArrangement.THREE_SIDE, CallTiles.arrangement(3, LayoutClass.LARGE))
    assertEquals(TileArrangement.THREE_SIDE, CallTiles.arrangement(3, LayoutClass.COMPACT_LANDSCAPE))
    assertEquals(TileArrangement.THREE_STACKED, CallTiles.arrangement(3, LayoutClass.COMPACT_PORTRAIT))
    assertEquals(TileArrangement.FOUR_GRID, CallTiles.arrangement(4, LayoutClass.COMPACT_PORTRAIT))
  }

  @Test fun addPeopleListsWhyARoomCannotBeRung() {
    val fresh = now - 1_000
    val peers = listOf(CallPeer(den.deviceId, "Den", fresh), CallPeer("q", "Quiet", fresh, quiet = true),
        CallPeer("o", "Old", now - CallTimings.OFFLINE_AFTER_MS - 1), CallPeer("b", "Busy", fresh, inCall = true),
        CallPeer("x", "Off", fresh, acceptsCalls = false), CallPeer(kitchen.deviceId, "Kitchen", fresh), CallPeer(portal, "Me", fresh))
    val rows = CallAddPeople.rows(call(), peers, portal, now).associate { it.peer.name to it.option }
    assertEquals(CallAddPeople.Option.ADD, rows["Den"])
    assertEquals(CallAddPeople.Option.QUIET, rows["Quiet"])
    assertEquals(CallAddPeople.Option.OFFLINE, rows["Old"])
    assertEquals(CallAddPeople.Option.BUSY, rows["Busy"])
    assertEquals(CallAddPeople.Option.CALLS_OFF, rows["Off"])
    assertEquals(CallAddPeople.Option.IN_CALL, rows["Kitchen"])
    assertFalse("never this Portal", "Me" in rows)
    assertEquals("addable first", "Den", CallAddPeople.rows(call(), peers, portal, now).first().peer.name)
    val full = call(listOf(CallLeg(den, LegPhase.LIVE, now, false), CallLeg(CallContact("k2", "K2"), LegPhase.LIVE, now, false)))
    assertEquals(CallAddPeople.Option.FULL, CallAddPeople.rows(full, peers, portal, now).first { it.peer.name == "Quiet" }.option)
    assertEquals("4 of 4 · this call is full", CallAddPeople.subtitle(full))
    assertEquals("Up to 4 people · 2 in the call now", CallAddPeople.subtitle(call()))
  }

  @Test fun noticesSayWhatHappened() {
    assertEquals("Den is in quiet hours · we didn’t ring", CallAddPeople.notice(CallNotice("Den", CallEndReason.QUIET, now)))
    assertEquals("Den left the call", CallAddPeople.notice(CallNotice("Den", CallEndReason.HUNG_UP, now)))
    assertEquals("Den is offline", CallAddPeople.notice(CallNotice("Den", CallEndReason.OFFLINE, now)))
  }
}
