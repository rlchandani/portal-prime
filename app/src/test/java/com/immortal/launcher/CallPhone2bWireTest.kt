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

/** Calling 2b wire (untrusted), the long-poll mailbox, Add/Move options, the phone outbox and copy. */
class CallPhone2bWireTest {
  private val self = "11111111-1111-1111-1111-111111111111"
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val phone = CallContact("ph0123456789abcdef", "Sam’s phone", kind = CallContact.Kind.PHONE)
  private val id = "0123456789abcdef"
  private val key = ByteArray(32) { it.toByte() }
  private val now = 1_800_000_000_000L

  private fun ringWith(members: JSONArray) = JSONObject().put("i", id).put("to", self).put("k", "c").put("v", true).put("fn", "Kitchen")
      .put("h", "192.168.1.20").put("p", 8723).put("g", members)

  @Test fun groupRingsMayNameOnePhoneMarkedAsSuch() {
    val ring = CallWire.Ring(id, self, CallKind.CALL, true, "Kitchen", "192.168.1.20", 8723,
        listOf(CallMemberRef(phone, null, 0), CallMemberRef(den, "192.168.1.22", 8723)))
    val decoded = CallWire.decodeRing(CallWire.encode(ring))!!
    assertEquals(ring.members, decoded.members)
    assertTrue(decoded.members.first().contact.phone)
    assertFalse("a phone member never carries an address", CallWire.encode(ring).getJSONArray("g").getJSONObject(0).has("h"))
  }

  @Test fun phoneMembersMustBeMarkedAndAtMostOne() {
    val p1 = JSONObject().put("d", phone.deviceId).put("fn", "Sam").put("k", "p")
    val p2 = JSONObject().put("d", "phfedcba9876543210").put("fn", "Priya").put("k", "p")
    assertNull("two phones", CallWire.decodeRing(ringWith(JSONArray().put(p1).put(p2))))
    assertNull("unmarked phone id", CallWire.decodeRing(ringWith(JSONArray().put(JSONObject().put("d", phone.deviceId).put("fn", "Sam")))))
    assertNull("a Portal marked as a phone", CallWire.decodeRing(ringWith(JSONArray().put(JSONObject().put("d", den.deviceId)
        .put("fn", "Den").put("k", "p")))))
    assertNotNull(CallWire.decodeRing(ringWith(JSONArray().put(p1))))
  }

  @Test fun joinsMayNameAPhoneNewcomer() {
    val join = CallWire.decodeJoin(CallWire.encode(CallWire.Join(id, self, phone)))!!
    assertEquals(phone, join.newcomer)
    assertEquals(kitchen.copy(kind = CallContact.Kind.ROOM), CallWire.decodeJoin(CallWire.encode(CallWire.Join(id, self, kitchen)))!!.newcomer)
    // From a phone, the newcomer it vouches for is always a Portal.
    assertNull(CallPhoneWire.decodeJoin(CallWire.encode(CallWire.Join(id, self, phone))))
    assertEquals(kitchen, CallPhoneWire.decodeJoin(CallWire.encode(CallWire.Join(id, self, kitchen)))!!.newcomer)
  }

  @Test fun endsCarryWhereTheCallMovedAndMovedIsNeverAWireReason() {
    val end = CallWire.decodeEnd(CallWire.encode(CallWire.End(id, self, CallEndReason.HUNG_UP, phone.deviceId)))!!
    assertEquals(phone.deviceId, end.movedTo)
    assertNull(CallWire.decodeEnd(CallWire.encode(CallWire.End(id, self, CallEndReason.HUNG_UP)))!!.movedTo)
    assertNull(CallWire.decodeEnd(JSONObject().put("i", id).put("to", self).put("e", "mv")))
    assertNull("junk mv is dropped, the end still counts",
        CallWire.decodeEnd(JSONObject().put("i", id).put("to", self).put("e", "h").put("mv", "../x"))!!.movedTo)
  }

  @Test fun mediaCarriesTheAutoAnsweredLock() {
    assertTrue(CallWire.decodeMedia(CallWire.encode(CallWire.Media(id, self, true, true, autoAnswered = true)))!!.autoAnswered)
    val plain = CallWire.encode(CallWire.Media(id, self, true, false))
    assertFalse(plain.has("aa"))
    assertFalse(CallWire.decodeMedia(plain)!!.autoAnswered)
  }

  @Test fun phoneRingsMayCarryPortalsAndAMove() {
    val ring = CallPhoneWire.encodeRing(id, self, true, "Sam’s phone", listOf(den, phone), move = true)
    assertEquals(1, ring.getJSONArray("g").length())
    val decoded = CallPhoneWire.decodeRing(ring)!!
    assertEquals(listOf(den), decoded.members)
    assertTrue(decoded.move)
    fun withG(vararg o: JSONObject) = JSONObject(ring.toString()).put("g", JSONArray().apply { o.forEach { put(it) } })
    assertNull("phones in a phone's list", CallPhoneWire.decodeRing(withG(JSONObject().put("d", phone.deviceId).put("fn", "x"))))
    assertNull("the recipient itself", CallPhoneWire.decodeRing(withG(JSONObject().put("d", self).put("fn", "x"))))
    assertNull("duplicates", CallPhoneWire.decodeRing(withG(JSONObject().put("d", den.deviceId).put("fn", "Den"),
        JSONObject().put("d", den.deviceId).put("fn", "Den"))))
    assertNull("over the cap", CallPhoneWire.decodeRing(withG(*(1..3).map {
      JSONObject().put("d", "5555555$it-5555-5555-5555-555555555555").put("fn", "R$it") }.toTypedArray())))
    assertFalse(CallPhoneWire.decodeRing(CallPhoneWire.encodeRing(id, self, false, "Sam"))!!.move)
  }

  @Test fun movedAndPullBodiesRoundTrip() {
    assertEquals(CallPhoneWire.Moved(id, self), CallPhoneWire.decodeMoved(CallPhoneWire.encodeMoved(id, self)))
    assertNull(CallPhoneWire.decodeMoved(JSONObject().put("i", "x").put("to", self)))
    assertEquals(id, CallPhoneWire.encodePull(id, phone.deviceId).getString("i"))
    assertTrue(CallWire.JOIN in CallPhoneWire.SENDABLE && CallPhoneWire.MOVED in CallPhoneWire.SENDABLE)
    assertTrue("a phone may pull back a call it moved here", CallPhoneWire.PULL in CallPhoneWire.SENDABLE)
  }

  @Test fun pollInfoKeepsOnlyWellFormedAliasesAndClampsTheWait() {
    val body = JSONObject().put("fn", "Sam").put("bg", true).put("w", 999_999)
        .put("pa", JSONObject().put(self, phone.deviceId).put(den.deviceId, "not-a-phone").put(phone.deviceId, phone.deviceId))
    val info = CallPhoneWire.pollInfo(body)
    assertEquals(mapOf(self to phone.deviceId), info.aliases)
    assertTrue(info.background)
    assertEquals(CallPhones.MAX_WAIT_MS, info.waitMs)
    assertEquals(0L, CallPhoneWire.pollInfo(JSONObject().put("w", -5)).waitMs)
    assertEquals("Phone", CallPhoneWire.pollInfo(JSONObject()).name)
  }

  @Test fun parkingIsCappedAndAParkedPhoneIsReachable() {
    assertEquals(25_000L, CallPhones.grantWait(25_000L, 0))
    assertEquals(0L, CallPhones.grantWait(25_000L, CallPhones.MAX_PARKED))
    assertEquals(CallPhones.MAX_WAIT_MS, CallPhones.grantWait(60_000L, 1))
    val p = CallPhone(phone.deviceId, "Sam", seenMs = now - 60_000, parkedUntilMs = now + 10_000)
    assertTrue(p.reachable(now))
    assertFalse(p.copy(parkedUntilMs = 0).reachable(now))
    assertEquals("Rings now · even with Portal Toolkit closed", CallPhoneCopy.status(p, now))
    assertEquals("Portal Toolkit is open · rings now", CallPhoneCopy.status(p.copy(seenMs = now, parkedUntilMs = 0), now))
    assertEquals("Not on the home Wi-Fi right now", CallPhoneCopy.status(p.copy(parkedUntilMs = 0, background = true), now))
  }

  @Test fun aParkedPollReturnsAsSoonAsAMessageIsPosted() {
    val who = "ph" + "1".repeat(16)
    val info = CallPhoneWire.PollInfo("Sam", emptyMap(), true, 5_000L)
    var result: CallPhoneMailbox.Polled? = null
    val t = Thread { result = CallPhoneMailbox.poll(who, info, System.currentTimeMillis()) }
    t.start()
    Thread.sleep(200)
    assertTrue(CallPhoneMailbox.phone(who)!!.reachable(System.currentTimeMillis()))
    CallPhoneMailbox.post(who, CallWire.RING, JSONObject().put("i", id), System.currentTimeMillis())
    t.join(2_000)
    assertFalse(t.isAlive)
    assertEquals(1, result!!.messages.size)
    assertTrue("returned well before the wait ran out", result!!.waitedMs < 2_000)
    assertEquals(5_000L, result!!.grantedMs)
  }

  @Test fun aPollWithMailWaitingNeverParks() {
    val who = "ph" + "2".repeat(16)
    CallPhoneMailbox.post(who, CallWire.END, JSONObject().put("i", id), System.currentTimeMillis())
    val r = CallPhoneMailbox.poll(who, CallPhoneWire.PollInfo("Sam", emptyMap(), true, 5_000L), System.currentTimeMillis())
    assertEquals(0L, r.grantedMs)
    assertEquals(1, r.messages.size)
  }

  private fun liveWith(vararg legs: CallContact): ActiveCall = ActiveCall(id, kitchen, CallDirection.OUTGOING, CallKind.CALL, true,
      CallPhase.LIVE, now, now, cameraOn = true, legs = legs.map { CallLeg(it, LegPhase.LIVE, now, offerer = false) })

  @Test fun phonesInTheAddSheetSayExactlyWhyNot() {
    val sam = CallPhone(phone.deviceId, "Sam’s phone", now, aliases = mapOf(kitchen.deviceId to "phaaaaaaaaaaaaaaaa"))
    assertEquals(CallAddPeople.Option.ADD, CallAddPeople.phoneOption(liveWith(), sam, self, now))
    assertEquals(CallAddPeople.Option.PHONE_CLOSED, CallAddPeople.phoneOption(liveWith(), sam.copy(seenMs = now - 60_000), self, now))
    assertEquals(CallAddPeople.Option.NOT_PAIRED, CallAddPeople.phoneOption(liveWith(den), sam, self, now))
    val priya = CallContact("phfedcba9876543210", "Priya", kind = CallContact.Kind.PHONE)
    assertEquals(CallAddPeople.Option.ONE_PHONE, CallAddPeople.phoneOption(liveWith(priya), sam, self, now))
    assertEquals(CallAddPeople.Option.IN_CALL, CallAddPeople.phoneOption(liveWith(phone), sam, self, now))
    assertEquals(CallAddPeople.Option.LOCKED, CallAddPeople.phoneOption(liveWith().copy(autoAnswered = true), sam, self, now))
    // A Portal the phone in the call isn't paired with can't be added.
    val peers = listOf(CallPeer(den.deviceId, "Den", now))
    val rows = CallAddPeople.rows(liveWith(phone), peers, self, now, listOf(sam))
    assertEquals(CallAddPeople.Option.NOT_PAIRED, rows.single { it.peer.id == den.deviceId }.option)
  }

  @Test fun theToPhoneControlMovesStraightToTheOneReadyPhone() {
    val sam = CallPhone(phone.deviceId, "Sam’s phone", now, aliases = mapOf(kitchen.deviceId to "phaaaaaaaaaaaaaaaa"))
    val one = CallMoves.control(liveWith(), listOf(sam), self, now)
    assertTrue(one.enabled)
    assertEquals(sam, one.target)
    val two = CallMoves.control(liveWith(), listOf(sam, sam.copy(id = "phbbbbbbbbbbbbbbbb", name = "Priya")), self, now)
    assertTrue(two.pick)
    val closed = CallMoves.control(liveWith(), listOf(sam.copy(seenMs = 0)), self, now)
    assertFalse(closed.enabled)
    assertTrue(closed.why!!.contains("Ring when Portal Toolkit is closed"))
    assertTrue(CallMoves.control(liveWith().copy(remoteAutoAnswered = true), listOf(sam), self, now).why!!.contains("automatically"))
    assertEquals(CallMoves.Option.NOT_NOW, CallMoves.option(liveWith().copy(kind = CallKind.DROP_IN), sam, self, now))
  }

  private fun out(id: String, phoneId: String = phone.deviceId, at: Long = now, z: Long = 1_000) = PhoneOutMessage(id, phoneId, "Kitchen",
      12_000, z, CallMessageCrypto.chunkCount(CallMessageCrypto.plainLength(z)), false, at)

  @Test fun theOutboxHoldsAFewSealedMessagesPerPhoneForAWeek() {
    val free = 10L * 1024 * 1024 * 1024
    val five = (0 until PhoneOutbox.MAX_PER_PHONE).map { out("00000000000000a$it") }
    assertEquals(PhoneOutbox.Refusal.FULL, PhoneOutbox.canQueue(five, phone.deviceId, 1_000, free))
    assertNull("another phone has its own budget", PhoneOutbox.canQueue(five, "ph" + "3".repeat(16), 1_000, free))
    assertEquals(PhoneOutbox.Refusal.NO_SPACE, PhoneOutbox.canQueue(emptyList(), phone.deviceId, 1_000, 100))
    assertEquals(PhoneOutbox.Refusal.TOO_LARGE, PhoneOutbox.canQueue(emptyList(), phone.deviceId, 0, free))
    val old = out("00000000000000b1", at = now - PhoneOutbox.KEEP_MS - 1)
    assertEquals(listOf(five[0]), PhoneOutbox.prune(listOf(old, five[0]), now))
    val later = out("00000000000000c1", at = now + 5)
    assertEquals(listOf(five[0], later), PhoneOutbox.forPhone(listOf(later, five[0], out("00000000000000d1", "ph" + "4".repeat(16))), phone.deviceId))
    assertNull("another phone can't collect it", PhoneOutbox.find(five, "ph" + "4".repeat(16), five[0].id))
  }

  @Test fun theOutboxIndexIsValidatedOnLoad() {
    val list = listOf(out("00000000000000a1"), out("00000000000000a2").copy(callId = id))
    assertEquals(list, PhoneOutbox.decode(PhoneOutbox.encode(list)))
    val bad = JSONArray(PhoneOutbox.encode(list)).apply {
      put(JSONObject(getJSONObject(0).toString()).put("i", "../../etc"))
      put(JSONObject(getJSONObject(0).toString()).put("p", self))
      put(JSONObject(getJSONObject(0).toString()).put("c", 99))
    }
    assertEquals(list, PhoneOutbox.decode(bad.toString()))
    assertEquals(emptyList<PhoneOutMessage>(), PhoneOutbox.decode("not json"))
    val vm = PhoneOutbox.announce(list[1])
    assertFalse("the poll never names the phone's id", vm.has("p"))
    assertEquals(id, vm.getString("ci"))
  }

  @Test fun phoneUploadsMustComeFromTheSessionsOwnPhoneId() {
    val put = CallMessageWire.Put("00000000000000a1", self, "Sam’s phone", 5_000, CallMessageCrypto.sealedLength(1_000), 1, false)
    fun header(from: String, nonce: String) = CallMessageWire.requestHeader(key, from, nonce, now, put)
    val guard = HouseholdMessage.ReplayGuard()
    val ok = CallMessageWire.verifyFromPhone(header(phone.deviceId, "a".repeat(24)), key, now, guard, self, phone.deviceId)
    assertEquals(phone.deviceId, ok!!.from)
    assertNull("replay", CallMessageWire.verifyFromPhone(header(phone.deviceId, "a".repeat(24)), key, now, guard, self, phone.deviceId))
    assertNull("another phone's id", CallMessageWire.verifyFromPhone(header("phfedcba9876543210", "b".repeat(24)), key, now, guard, self,
        phone.deviceId))
    assertNull("a Portal's header on the phone route", CallMessageWire.verifyFromPhone(header(kitchen.deviceId, "c".repeat(24)), key, now,
        guard, self, phone.deviceId))
    assertNull("phones still can't use the Portal route", CallMessageWire.verify(header(phone.deviceId, "d".repeat(24)), key, now, guard, self))
  }

  @Test fun getResponsesBindIdAndLengthToTheNonce() {
    val a = CallPhoneAuth.sign(key, CallPhoneMessageRoute.GET_DOMAIN, "n1", CallPhoneMessageRoute.getBinding("00000000000000a1", 1_016))
    assertNotEquals(a, CallPhoneAuth.sign(key, CallPhoneMessageRoute.GET_DOMAIN, "n2", CallPhoneMessageRoute.getBinding("00000000000000a1", 1_016)))
    assertNotEquals(a, CallPhoneAuth.sign(key, CallPhoneMessageRoute.GET_DOMAIN, "n1", CallPhoneMessageRoute.getBinding("00000000000000a1", 1_017)))
  }

  @Test fun sealedChunksMatchPortalToolkitByteForByte() {
    // The same vector is asserted in Portal Toolkit's Calling2bWireAndMessagesTest (PhoneMessageCrypto).
    val msgId = "00000000000000a1"
    val sealed = CallMessageCrypto.sealChunk(CallMessageCrypto.transitKey(key, msgId), msgId, 0, true, "hearthpane".toByteArray())
    assertEquals("6a4d7aae7dd801d90426113708b4d946dafe7a82fc9a16f54570", sealed.joinToString("") { "%02x".format(it) })
  }

  @Test fun copyForGroupRingsMovesAndMovedCalls() {
    val ring = ActiveCall(id, kitchen, CallDirection.INCOMING, CallKind.CALL, true, CallPhase.RINGING, now, now, cameraOn = true,
        invited = listOf(CallMemberRef(den, null, 0), CallMemberRef(phone, null, 0)))
    assertEquals("Kitchen added you to a call with Den and Sam’s phone", CallingCopy.incomingLine(ring))
    val move = ring.copy(peer = phone, invited = listOf(CallMemberRef(den, null, 0)), move = CallMove(from = phone.deviceId))
    assertEquals("Moving a call here · with Den", CallingCopy.incomingLine(move))
    assertEquals("Pick up here", CallingCopy.answerLabel(move))
    assertEquals("Answer", CallingCopy.answerLabel(ring))
    assertEquals("Kitchen, Den and Kids", CallingCopy.names(listOf("Kitchen", "Den", "Kids")))
    assertEquals("Sam’s phone · phone", CallingCopy.tileName(phone))
    val moved = EndedCall(id, kitchen, CallDirection.OUTGOING, CallKind.CALL, CallEndReason.MOVED, now - 60_000, now, movedTo = phone)
    assertEquals("Call moved to Sam’s phone", CallingCopy.ended(moved, "9:00", null).title)
    assertEquals(CallingCopy.MOVED_BODY, CallingCopy.ended(moved, "9:00", null).body)
    val offline = EndedCall(id, phone, CallDirection.OUTGOING, CallKind.CALL, CallEndReason.OFFLINE, null, now)
    assertTrue(CallingCopy.ended(offline, "9:00", null).body.contains("Ring when Portal Toolkit is closed"))
    assertEquals("Up to 2 minutes · they’ll get it in Portal Toolkit", CallMessageCopy.sub(ComposePhase.READY, null, phone = true))
  }
}
