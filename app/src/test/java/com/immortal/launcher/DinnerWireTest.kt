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

/** Bus bodies for dinner_call / dinner_ack / dinner_reply / dinner_hello; peers are untrusted. */
class DinnerWireTest {
  private val a = "11111111-1111-1111-1111-111111111111"
  private val b = "22222222-2222-2222-2222-222222222222"
  private val c = "33333333-3333-3333-3333-333333333333"

  @Test fun callRoundTripsAndFitsOneDatagram() {
    val call = DinnerWire.Call("a1b2c3d4e5f6", DinnerMessage.custom("x".repeat(40))!!, "Family Area", false, a, listOf(b, c))
    val back = DinnerWire.decodeCall(DinnerWire.encode(call))!!
    assertEquals(call, back)
    val wire = HouseholdMessage.encodeOrNull(
        HouseholdMessage(DinnerWire.CALL, a, 1L, "n".repeat(24), DinnerWire.encode(call)), ByteArray(32))
    assertNotNull("call must fit the bus limit", wire)
    val preset = DinnerWire.Call("a1b2c3d4", DinnerMessage.preset("dinner")!!, "Rohit’s Pixel", true, a, null)
    assertEquals(preset, DinnerWire.decodeCall(DinnerWire.encode(preset)))
  }

  @Test fun callDecodingRejectsJunkFromPeers() {
    val good = DinnerWire.encode(DinnerWire.Call("a1b2c3d4", DinnerMessage.preset("bed")!!, "Den", false, a, null))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("s", "../../x")))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("p", "unknown")))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("k", "z")))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("rt", "bad id with spaces")))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("fn", "")))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("to", JSONArray().put("ok-id").put(7))))
    assertNull(DinnerWire.decodeCall(JSONObject(good.toString()).put("to", JSONArray((1..9).map { "id$it" }))))
    assertNull(DinnerWire.decodeCall(JSONObject().put("k", "c").put("x", "x".repeat(41)).put("s", "a1b2c3d4").put("fn", "Den").put("rt", a)))
    assertNull(DinnerWire.decodeCall(JSONObject()))
    // A long sender name is clipped, not trusted verbatim.
    val longName = DinnerWire.decodeCall(JSONObject(good.toString()).put("fn", "N".repeat(80)))!!
    assertEquals(DinnerWire.MAX_NAME, longName.senderName.length)
  }

  @Test fun addressing() {
    val all = DinnerWire.Call("a1b2c3d4", DinnerMessage.ping(), "Den", false, a, null)
    assertTrue(all.isFor(b))
    val some = all.copy(to = listOf(b))
    assertTrue(some.isFor(b))
    assertFalse(some.isFor(c))
  }

  @Test fun ackReplyAndHelloRoundTrip() {
    val ack = DinnerWire.Ack("a1b2c3d4", a, DinnerStatus.QUIET, "Living Area")
    assertEquals(ack, DinnerWire.decodeAck(DinnerWire.encode(ack)))
    assertNull(DinnerWire.decodeAck(JSONObject(DinnerWire.encode(ack).toString()).put("st", "sending")))
    val reply = DinnerWire.Reply("a1b2c3d4", a, DinnerReply.ON_MY_WAY, "Living Area")
    assertEquals(reply, DinnerWire.decodeReply(DinnerWire.encode(reply)))
    assertNull(DinnerWire.decodeReply(JSONObject(DinnerWire.encode(reply).toString()).put("r", "lol")))
    val hello = DinnerWire.Hello("Den", ask = true, quiet = false, receive = true)
    assertEquals(hello, DinnerWire.decodeHello(DinnerWire.encode(hello)))
    assertNull(DinnerWire.decodeHello(JSONObject().put("fn", " ")))
  }
}
