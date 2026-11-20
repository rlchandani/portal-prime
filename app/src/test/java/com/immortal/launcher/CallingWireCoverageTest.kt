/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every call control decoder treats each field as untrusted; malformed messages decode to null. */
class CallingWireCoverageTest {
  private val id = "0123456789abcdef"
  private val to = "den"
  private val phone = CallPhoneId.of("token-a")
  private val phone2 = CallPhoneId.of("token-b")

  private fun ring(kind: CallKind = CallKind.CALL, members: List<CallMemberRef> = emptyList()) =
      CallWire.Ring(id, to, kind, true, "Kitchen", "192.168.1.20", 8723, members)

  private fun member(d: String, host: String? = "192.168.1.30", port: Int = 8723) = CallMemberRef(CallContact(d, "Room $d"), host, port)

  private fun ringJson() = CallWire.encode(ring())
  private fun copy(o: JSONObject) = JSONObject(o.toString())

  @Test fun isCallIdNeedsSixteenLowerHex() {
    assertTrue(CallWire.isCallId(id))
    assertFalse(CallWire.isCallId(null))
    assertFalse(CallWire.isCallId(id.uppercase()))
    assertFalse(CallWire.isCallId(id + "0"))
  }

  @Test fun ringFieldRejections() {
    val bad: List<Pair<String, Any>> = listOf("to" to "a b", "v" to "true", "fn" to "\u0001", "fn" to "",
        "p" to 0, "p" to 65_536, "p" to "8723", "h" to "192.0.2.1")
    for ((field, value) in bad) assertNull("$field=$value", CallWire.decodeRing(copy(ringJson()).put(field, value)))
    for (field in listOf("i", "to", "k", "v", "fn", "h", "p")) {
      assertNull("missing $field", CallWire.decodeRing(copy(ringJson()).apply { remove(field) }))
    }
    assertEquals(65_535, CallWire.decodeRing(copy(ringJson()).put("p", 65_535))!!.port)
  }

  @Test fun dropInRingIgnoresAnyMemberList() {
    val dropIn = CallWire.encode(ring(CallKind.DROP_IN)).put("g", JSONArray(listOf("junk")))
    assertEquals(emptyList<CallMemberRef>(), CallWire.decodeRing(dropIn)!!.members)
  }

  @Test fun groupRingRoundTripsRoomsWithAndWithoutAddressAndAPhone() {
    val members = listOf(member("living"), member("attic", host = null, port = 0),
        CallMemberRef(CallContact(phone, "Mum's phone", kind = CallContact.Kind.PHONE), null, 0))
    val back = CallWire.decodeRing(copy(CallWire.encode(ring(members = members))))!!
    assertEquals(members, back.members)
  }

  @Test fun memberAddressIsDroppedUnlessBothHostAndPortAreValid() {
    val members = JSONArray()
        .put(JSONObject().put("d", "a1").put("fn", "A").put("h", "192.168.1.5"))
        .put(JSONObject().put("d", "b1").put("fn", "B").put("p", 9000))
        .put(JSONObject().put("d", "c1").put("fn", "C").put("h", "8.8.8.8").put("p", 9000))
    val back = CallWire.decodeRing(copy(ringJson()).put("g", members))!!.members
    assertEquals(listOf(null to 0, null to 0, null to 0), back.map { it.host to it.port })
  }

  @Test fun encodeMemberOmitsAnInvalidPort() {
    val enc = CallWire.encode(ring(members = listOf(member("living", port = 0))))
    assertFalse(enc.getJSONArray("g").getJSONObject(0).has("h"))
  }

  @Test fun groupRingRejectsMalformedMemberLists() {
    fun withMembers(arr: JSONArray) = CallWire.decodeRing(copy(ringJson()).put("g", arr))
    val ok = { d: String -> JSONObject().put("d", d).put("fn", "N") }
    assertNull("too many", withMembers(JSONArray((1..CallGroup.MAX_LEGS + 2).map { ok("r$it") })))
    assertEquals(CallGroup.MAX_LEGS + 1, withMembers(JSONArray((1..CallGroup.MAX_LEGS + 1).map { ok("r$it") }))!!.members.size)
    assertNull("non-object", withMembers(JSONArray(listOf("x"))))
    assertNull("bad id", withMembers(JSONArray(listOf(ok("a b")))))
    assertNull("bad name", withMembers(JSONArray(listOf(ok("r1").put("fn", "")))))
    assertNull("unmarked phone", withMembers(JSONArray(listOf(ok(phone)))))
    assertNull("marked portal", withMembers(JSONArray(listOf(ok("r1").put("k", "p")))))
    assertNull("two phones", withMembers(JSONArray(listOf(ok(phone).put("k", "p"), ok(phone2).put("k", "p")))))
    assertNull("duplicate", withMembers(JSONArray(listOf(ok("r1"), ok("r1")))))
    assertEquals(emptyList<CallMemberRef>(), withMembers(JSONArray())!!.members)
  }

  @Test fun joinValidatesEachFieldAndMarksRoomsAsRooms() {
    val join = CallWire.Join(id, to, CallContact("attic", "Attic"))
    assertEquals(join, CallWire.decodeJoin(CallWire.encode(join)))
    for (field in listOf("i", "to", "n", "fn")) {
      assertNull(field, CallWire.decodeJoin(CallWire.encode(join).apply { remove(field) }))
    }
    assertNull(CallWire.decodeJoin(CallWire.encode(join).put("n", "x/y")))
  }

  @Test fun replyQuietEndMustBeAMinuteOfTheDay() {
    val reply = CallWire.Reply(id, to, CallReply.QUIET, 0)
    assertEquals(reply, CallWire.decodeReply(CallWire.encode(reply)))
    assertEquals(1439, CallWire.decodeReply(CallWire.encode(reply.copy(quietEndMin = 1439)))!!.quietEndMin)
    assertNull(CallWire.decodeReply(CallWire.encode(reply).put("qe", 1440))!!.quietEndMin)
    assertNull(CallWire.decodeReply(CallWire.encode(reply).put("qe", -1))!!.quietEndMin)
    assertNull(CallWire.decodeReply(CallWire.encode(reply).put("qe", "5"))!!.quietEndMin)
    assertFalse(CallWire.encode(reply.copy(quietEndMin = null)).has("qe"))
    assertNull(CallWire.decodeReply(CallWire.encode(reply).put("r", "zz")))
    assertNull(CallWire.decodeReply(CallWire.encode(reply).put("i", "nope")))
    assertNull(CallWire.decodeReply(CallWire.encode(reply).put("to", "")))
  }

  @Test fun endRejectsTheLocalOnlyMovedReasonAndKeepsAValidMoveTarget() {
    val end = CallWire.End(id, to, CallEndReason.HUNG_UP, movedTo = "living")
    assertEquals(end, CallWire.decodeEnd(CallWire.encode(end)))
    assertNull(CallWire.decodeEnd(CallWire.encode(end).put("e", CallEndReason.MOVED.wire)))
    assertNull(CallWire.decodeEnd(CallWire.encode(end).put("e", "??")))
    assertNull("bad move target dropped", CallWire.decodeEnd(CallWire.encode(end).put("mv", "a b"))!!.movedTo)
    assertFalse(CallWire.encode(end.copy(movedTo = null)).has("mv"))
    assertNull(CallWire.decodeEnd(CallWire.encode(end).put("i", "")))
    assertNull(CallWire.decodeEnd(CallWire.encode(end).put("to", "a b")))
  }

  @Test fun keepCapMustBePositive() {
    val keep = CallWire.Keep(id, to, 1_800_000_000_000L)
    assertEquals(keep, CallWire.decodeKeep(CallWire.encode(keep)))
    assertNull(CallWire.decodeKeep(CallWire.encode(keep).put("cap", 0)))
    assertNull(CallWire.decodeKeep(CallWire.encode(keep).put("cap", "1")))
    assertNull(CallWire.decodeKeep(CallWire.encode(keep).put("i", "x")))
    assertNull(CallWire.decodeKeep(CallWire.encode(keep).put("to", "")))
  }

  @Test fun mediaNeedsBooleanCamAndMic() {
    val media = CallWire.Media(id, to, cameraOn = true, micOn = false, autoAnswered = true)
    assertEquals(media, CallWire.decodeMedia(CallWire.encode(media)))
    assertFalse(CallWire.encode(media.copy(autoAnswered = false)).has("aa"))
    assertNull(CallWire.decodeMedia(CallWire.encode(media).put("cam", "on")))
    assertNull(CallWire.decodeMedia(CallWire.encode(media).put("mic", 1)))
    assertNull(CallWire.decodeMedia(CallWire.encode(media).put("i", "x")))
    assertNull(CallWire.decodeMedia(CallWire.encode(media).put("to", "")))
  }

  @Test fun helloDefaultsAndAddressRules() {
    val bare = CallWire.decodeHello(JSONObject().put("fn", "Den"))!!
    assertEquals(CallWire.Hello("Den", ask = false, inCall = false, quiet = false, acceptsCalls = true,
        dropInsOn = false, dropInPolicies = emptyMap()), bare)
    assertNull(CallWire.decodeHello(JSONObject()))
    val hello = CallWire.Hello("Den", true, true, true, false, true, mapOf("living" to DropInPolicy.CHIME), "192.168.1.9", 8723)
    assertEquals(hello, CallWire.decodeHello(CallWire.encode(hello)))
    assertFalse("invalid port not encoded", CallWire.encode(hello.copy(port = 0)).has("h"))
    assertFalse("no host not encoded", CallWire.encode(hello.copy(host = null)).has("p"))
    val hostOnly = CallWire.decodeHello(CallWire.encode(hello).apply { remove("p") })!!
    assertEquals(null to 0, hostOnly.host to hostOnly.port)
    val portOnly = CallWire.decodeHello(CallWire.encode(hello).apply { remove("h") })!!
    assertEquals(null to 0, portOnly.host to portOnly.port)
    assertEquals(emptyMap<String, DropInPolicy>(), CallWire.decodeHello(CallWire.encode(hello).put("dp", "%%%"))!!.dropInPolicies)
  }
}
