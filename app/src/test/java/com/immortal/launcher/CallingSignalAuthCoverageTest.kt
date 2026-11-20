/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Malformed offers/answers, SDP candidate filtering and the signed request/response bindings. */
class CallingSignalAuthCoverageTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 7).toByte() }
  private val now = 1_800_000_000_000L
  private val id = "0123456789abcdef"
  private val fp = "sha-256 " + (0 until 32).joinToString(":") { "%02X".format(it) }
  private val sdp = "v=0\r\na=fingerprint:$fp\r\n"
  private val offer = CallSignalAuth.Offer(id, "den", sdp, fp)

  private fun offerJson() = JSONObject(CallSignalAuth.offerBody(offer))

  @Test fun offerRoundTrips() {
    assertEquals(offer, CallSignalAuth.parseOffer(CallSignalAuth.offerBody(offer)))
  }

  @Test fun offerRejectsJunkFieldByField() {
    assertNull(CallSignalAuth.parseOffer("not json"))
    assertNull(CallSignalAuth.parseOffer("{}"))
    assertNull("bad call id", CallSignalAuth.parseOffer(offerJson().put("i", "ABCDEF0123456789").toString()))
    assertNull("bad device", CallSignalAuth.parseOffer(offerJson().put("to", "den room").toString()))
    assertNull("missing to", CallSignalAuth.parseOffer(offerJson().apply { remove("to") }.toString()))
    assertNull("empty sdp", CallSignalAuth.parseOffer(offerJson().put("sdp", "").toString()))
    val huge = sdp + "a=x:" + "y".repeat(CallSignalAuth.MAX_SDP)
    assertNull("oversize sdp", CallSignalAuth.parseOffer(offerJson().put("sdp", huge).toString()))
    assertNull("fp mismatch", CallSignalAuth.parseOffer(offerJson().put("fp", fp.lowercase()).toString()))
    assertNull("sdp without fp", CallSignalAuth.parseOffer(offerJson().put("sdp", "v=0").put("fp", "").toString()))
  }

  @Test fun sdpAtExactlyTheCapIsAccepted() {
    val pad = CallSignalAuth.MAX_SDP - sdp.length
    val atCap = sdp + "x".repeat(pad)
    assertEquals(CallSignalAuth.MAX_SDP, atCap.length)
    assertNotNull(CallSignalAuth.parseOffer(offerJson().put("sdp", atCap).toString()))
  }

  @Test fun answerRejectsJunkAndWrongCall() {
    val answer = CallSignalAuth.Answer(id, sdp, fp)
    val payload = CallSignalAuth.answerPayload(answer)
    assertEquals(answer, CallSignalAuth.parseAnswer(payload, id))
    assertNull(CallSignalAuth.parseAnswer("[]", id))
    assertNull("other call", CallSignalAuth.parseAnswer(payload, "fedcba9876543210"))
    val j = JSONObject(payload)
    assertNull("empty sdp", CallSignalAuth.parseAnswer(JSONObject(payload).put("sdp", "").toString(), id))
    assertNull("fp swapped", CallSignalAuth.parseAnswer(j.put("fp", "sha-256 00").toString(), id))
  }

  @Test fun fingerprintNormalisesCaseAndRejectsOtherAlgorithmsOrDuplicates() {
    val lowerHex = "v=0\r\na=fingerprint:SHA-256 " + fp.removePrefix("sha-256 ").lowercase() + "  \r\n"
    assertEquals(fp, CallSignalAuth.fingerprintOf(lowerHex))
    assertEquals("same fingerprint twice is still one", fp, CallSignalAuth.fingerprintOf(sdp + "a=fingerprint:$fp\r\n"))
    val second = "sha-256 " + (1..32).joinToString(":") { "%02X".format(it) }
    assertNull(CallSignalAuth.fingerprintOf(sdp + "a=fingerprint:$second\r\n"))
    assertNull(CallSignalAuth.fingerprintOf("a=fingerprint:sha-1 " + (0 until 20).joinToString(":") { "%02X".format(it) }))
    assertNull("too short", CallSignalAuth.fingerprintOf("a=fingerprint:sha-256 AA:BB"))
    assertNull(CallSignalAuth.fingerprintOf("v=0"))
  }

  @Test fun lanOnlyDropsMalformedCandidatesAndKeepsOtherLines() {
    val input = listOf(
        "v=0",
        "a=candidate:garbage",
        "a=candidate:1 1 UDP 2122260223 10.0.0.5 50000 typ host",
        "a=candidate:2 1 udp 2122260223 172.16.0.9 50000 typ host",
        "a=candidate:3 1 udp 2122260223 172.32.0.9 50000 typ host",
        "a=candidate:4 1 udp 2122260223 192.168.1.300 50000 typ host",
        "a=candidate:5 1 udp 2122260223 192.168.1.4 50000 typ relay",
        "a=mid:0").joinToString("\r\n")
    val out = CallSignalAuth.lanOnly(input)
    assertEquals(listOf("v=0", "a=candidate:1 1 UDP 2122260223 10.0.0.5 50000 typ host",
        "a=candidate:2 1 udp 2122260223 172.16.0.9 50000 typ host", "a=mid:0"), out.split("\r\n"))
    assertTrue(CallSignalAuth.hasLanCandidate(out))
    assertFalse(CallSignalAuth.hasLanCandidate(CallSignalAuth.lanOnly("v=0\r\na=candidate:x")))
  }

  @Test fun requestVerificationRejectsNullHeaderWrongTypeAndNonStringPin() {
    val body = CallSignalAuth.offerBody(offer)
    assertNull(CallSignalAuth.verifyRequest(null, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val wrongType = HouseholdMessage.encode(HouseholdMessage("chores_sync", "kitchen", now, "n1", JSONObject()), key)
    assertNull(CallSignalAuth.verifyRequest(wrongType, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val numericPin = HouseholdMessage.encode(
        HouseholdMessage(CallSignalAuth.REQUEST_TYPE, "kitchen", now, "n2", JSONObject().put("s", 42)), key)
    assertNull(CallSignalAuth.verifyRequest(numericPin, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val noPin = HouseholdMessage.encode(HouseholdMessage(CallSignalAuth.REQUEST_TYPE, "kitchen", now, "n3", JSONObject()), key)
    assertNull(CallSignalAuth.verifyRequest(noPin, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val ok = CallSignalAuth.requestHeader(key, "kitchen", "n4", now, body)
    assertEquals(CallSignalAuth.Request("kitchen", "n4"),
        CallSignalAuth.verifyRequest(ok, body, key, now, HouseholdMessage.ReplayGuard(), null))
  }

  @Test fun requestHeaderIsNullForInvalidSenderOrKey() {
    assertNull(CallSignalAuth.requestHeader(key, "bad sender", "n", now, "{}"))
    assertNull(CallSignalAuth.requestHeader(ByteArray(8), "kitchen", "n", now, "{}"))
  }

  @Test fun responseSignatureRejectsNullOtherKeyAndOtherNonce() {
    val sig = CallSignalAuth.signResponse(key, "n1", "payload")
    assertTrue(CallSignalAuth.verifyResponse(key, "n1", "payload", sig))
    assertFalse(CallSignalAuth.verifyResponse(key, "n1", "payload", null))
    assertFalse(CallSignalAuth.verifyResponse(other, "n1", "payload", sig))
    assertFalse(CallSignalAuth.verifyResponse(key, "n2", "payload", sig))
    assertFalse(CallSignalAuth.verifyResponse(key, "n1", "payload", sig.dropLast(1)))
  }
}
