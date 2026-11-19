/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Bus bodies (untrusted) and the household-signed SDP exchange with bound DTLS fingerprints. */
class CallWireAuthTest {
  private val a = "11111111-1111-1111-1111-111111111111"
  private val b = "22222222-2222-2222-2222-222222222222"
  private val id = "0123456789abcdef"
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }
  private val fp = "sha-256 " + (0 until 32).joinToString(":") { "%02X".format(it) }
  private val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=fingerprint:$fp\r\n" +
      "a=candidate:1 1 udp 2122260223 192.168.1.20 50000 typ host generation 0\r\n" +
      "a=candidate:2 1 udp 2122194687 100.64.1.2 50001 typ host generation 0\r\n" +
      "a=candidate:3 1 udp 1686052607 81.2.3.4 50002 typ srflx raddr 192.168.1.20 rport 50000\r\n" +
      "a=candidate:4 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active\r\n" +
      "a=candidate:5 1 udp 2122262783 fe80::1 50003 typ host\r\n" +
      "a=candidate:6 1 udp 2122262783 abc.local 50004 typ host\r\n"

  @Test fun ringRoundTripsAndFitsOneDatagram() {
    val ring = CallWire.Ring(id, b, CallKind.DROP_IN, true, "Living Area", "192.168.1.20", 8723)
    assertEquals(ring, CallWire.decodeRing(CallWire.encode(ring)))
    assertNotNull(HouseholdMessage.encodeOrNull(HouseholdMessage(CallWire.RING, a, 1L, "n".repeat(24), CallWire.encode(ring)), key))
  }

  @Test fun ringRejectsJunk() {
    val good = CallWire.encode(CallWire.Ring(id, b, CallKind.CALL, false, "Den", "10.0.0.2", 8723))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("i", "short")))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("h", "8.8.8.8")))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("p", 70_000)))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("k", "z")))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("v", "yes")))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("to", "bad id")))
    assertNull(CallWire.decodeRing(JSONObject(good.toString()).put("fn", "")))
  }

  @Test fun repliesEndsKeepsMediaRoundTrip() {
    val reply = CallWire.Reply(id, a, CallReply.QUIET, 420)
    assertEquals(reply, CallWire.decodeReply(CallWire.encode(reply)))
    assertNull(CallWire.decodeReply(JSONObject(CallWire.encode(reply).toString()).put("qe", 5000))?.quietEndMin)
    assertNull(CallWire.decodeReply(JSONObject(CallWire.encode(reply).toString()).put("r", "?")))
    val end = CallWire.End(id, a, CallEndReason.CAP_REACHED)
    assertEquals(end, CallWire.decodeEnd(CallWire.encode(end)))
    assertNull(CallWire.decodeEnd(JSONObject(CallWire.encode(end).toString()).put("e", "nope")))
    val keep = CallWire.Keep(id, a, 123_456L)
    assertEquals(keep, CallWire.decodeKeep(CallWire.encode(keep)))
    assertNull(CallWire.decodeKeep(JSONObject(CallWire.encode(keep).toString()).put("cap", -1)))
    val media = CallWire.Media(id, a, cameraOn = false, micOn = true)
    assertEquals(media, CallWire.decodeMedia(CallWire.encode(media)))
    assertNull(CallWire.decodeMedia(JSONObject(CallWire.encode(media).toString()).remove("cam").let { JSONObject().put("i", id).put("to", a) }))
  }

  @Test fun helloCarriesPresenceAndTheDropInColumnInOneDatagram() {
    val policies = (1..8).associate { "%08d-1111-1111-1111-111111111111".format(it) to DropInPolicy.CHIME }
    val hello = CallWire.Hello("Living Area", ask = true, inCall = false, quiet = true, acceptsCalls = true, dropInsOn = true,
        dropInPolicies = policies)
    assertEquals(hello, CallWire.decodeHello(CallWire.encode(hello)))
    assertNotNull("hello must fit the bus limit",
        HouseholdMessage.encodeOrNull(HouseholdMessage(CallWire.HELLO, a, 1L, "n".repeat(24), CallWire.encode(hello)), key))
    assertEquals(emptyMap<String, DropInPolicy>(),
        CallWire.decodeHello(JSONObject(CallWire.encode(hello).toString()).put("dp", "garbage"))!!.dropInPolicies)
  }

  @Test fun fingerprintMustBeSingleAndSha256() {
    assertEquals(fp, CallSignalAuth.fingerprintOf(sdp))
    assertEquals(fp, CallSignalAuth.fingerprintOf(sdp.replace(fp, fp.lowercase().replace("sha-256", "SHA-256"))))
    val second = "sha-256 " + (0 until 32).joinToString(":") { "%02X".format(31 - it) }
    assertNull("two certificates is a way around the binding", CallSignalAuth.fingerprintOf("$sdp" + "a=fingerprint:$second\r\n"))
    assertNull(CallSignalAuth.fingerprintOf(sdp.replace("sha-256", "sha-1")))
    assertNull(CallSignalAuth.fingerprintOf("v=0\r\n"))
  }

  @Test fun offerAndAnswerMustRepeatTheirSdpFingerprint() {
    val body = CallSignalAuth.offerBody(CallSignalAuth.Offer(id, b, sdp, fp))
    assertEquals(CallSignalAuth.Offer(id, b, sdp, fp), CallSignalAuth.parseOffer(body))
    val lying = CallSignalAuth.offerBody(CallSignalAuth.Offer(id, b, sdp, fp.replace("00:", "FF:")))
    assertNull(CallSignalAuth.parseOffer(lying))
    assertNull(CallSignalAuth.parseOffer(JSONObject(body).put("i", "x").toString()))
    assertNull(CallSignalAuth.parseOffer(JSONObject(body).put("sdp", "x".repeat(CallSignalAuth.MAX_SDP + 1)).toString()))
    val payload = CallSignalAuth.answerPayload(CallSignalAuth.Answer(id, sdp, fp))
    assertNotNull(CallSignalAuth.parseAnswer(payload, id))
    assertNull("an answer for another call is refused", CallSignalAuth.parseAnswer(payload, "ffffffffffffffff"))
  }

  @Test fun lanOnlyKeepsPrivateUdpHostCandidates() {
    val lan = CallSignalAuth.lanOnly(sdp)
    assertTrue(lan.contains("192.168.1.20 50000 typ host"))
    assertFalse(lan.contains("100.64.1.2"))
    assertFalse(lan.contains("typ srflx"))
    assertFalse(lan.contains("tcptype"))
    assertFalse(lan.contains("fe80::1"))
    assertFalse(lan.contains(".local"))
    assertTrue(lan.contains("a=fingerprint:"))
    assertTrue(lan.endsWith("\r\n"))
    assertTrue(CallSignalAuth.hasLanCandidate(lan))
    assertFalse(CallSignalAuth.hasLanCandidate(CallSignalAuth.lanOnly(sdp.replace("192.168.1.20", "8.8.8.8"))))
  }

  @Test fun requestHeaderPinsTheBodyAndRejectsReplaysForgeriesAndSelf() {
    val body = CallSignalAuth.offerBody(CallSignalAuth.Offer(id, b, sdp, fp))
    val now = 5_000_000L
    val header = CallSignalAuth.requestHeader(key, a, "n".repeat(24), now, body)!!
    val replay = HouseholdMessage.ReplayGuard()
    assertEquals(CallSignalAuth.Request(a, "n".repeat(24)), CallSignalAuth.verifyRequest(header, body, key, now, replay, b))
    assertNull("replayed", CallSignalAuth.verifyRequest(header, body, key, now, replay, b))
    val fresh = HouseholdMessage.ReplayGuard()
    assertNull("swapped body", CallSignalAuth.verifyRequest(header, body.replace("192.168", "10.0"), key, now, fresh, b))
    assertNull("wrong key", CallSignalAuth.verifyRequest(header, body, other, now, HouseholdMessage.ReplayGuard(), b))
    assertNull("stale", CallSignalAuth.verifyRequest(header, body, key, now + 10 * 60_000, HouseholdMessage.ReplayGuard(), b))
    assertNull("from ourselves", CallSignalAuth.verifyRequest(header, body, key, now, HouseholdMessage.ReplayGuard(), a))
    assertNull(CallSignalAuth.verifyRequest(null, body, key, now, HouseholdMessage.ReplayGuard(), b))
    val listHeader = HouseholdListPeerAuth.requestHeader(key, a, "m".repeat(24), now, body)!!
    assertNull("a list request can't be replayed as a call", CallSignalAuth.verifyRequest(listHeader, body, key, now,
        HouseholdMessage.ReplayGuard(), b))
  }

  @Test fun answerSignatureIsBoundToTheNonceAndPayload() {
    val payload = CallSignalAuth.answerPayload(CallSignalAuth.Answer(id, sdp, fp))
    val sig = CallSignalAuth.signResponse(key, "nonce1", payload)
    assertTrue(CallSignalAuth.verifyResponse(key, "nonce1", payload, sig))
    assertFalse(CallSignalAuth.verifyResponse(key, "nonce2", payload, sig))
    assertFalse(CallSignalAuth.verifyResponse(key, "nonce1", payload.replace("0123", "3210"), sig))
    assertFalse(CallSignalAuth.verifyResponse(other, "nonce1", payload, sig))
    assertFalse(CallSignalAuth.verifyResponse(key, "nonce1", payload, null))
    assertNotEquals("domain-separated from the list exchange", HouseholdListPeerAuth.signResponse(key, "nonce1", payload), sig)
  }

  @Test fun peerCodecValidatesStoredRooms() {
    val peers = listOf(CallPeer(a, "Kitchen", 10, inCall = true, quiet = false, acceptsCalls = false, dropInsOn = true,
        dropInPolicies = mapOf(b to DropInPolicy.CHIME), favourite = true))
    assertEquals(peers, CallPeerCodec.decode(CallPeerCodec.encode(peers)))
    assertEquals(emptyList<CallPeer>(), CallPeerCodec.decode("not json"))
    assertEquals(emptyList<CallPeer>(), CallPeerCodec.decode("[{\"id\":\"bad id\",\"n\":\"x\"}]"))
  }
}
