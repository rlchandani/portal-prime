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

class MusicHandoffWireTest {
  private val key = ByteArray(32) { it.toByte() }
  private val otherKey = ByteArray(32) { (it + 1).toByte() }
  private val now = 1_800_000_000_000L

  // ---- bus bodies ----

  @Test fun offerRoundTripsAndFitsOneDatagram() {
    val offer = MusicHandoffWire.Offer("a1b2c3d4e5f6", "living-portal", "192.168.1.20", 8765, "Family Area")
    assertEquals(offer, MusicHandoffWire.decodeOffer(MusicHandoffWire.encode(offer)))
    val wire = HouseholdMessage.encodeOrNull(HouseholdMessage(MusicHandoffWire.OFFER, "family-portal", now, "n1",
        MusicHandoffWire.encode(offer.copy(fromName = "W".repeat(40)))), key)
    assertNotNull(wire)
  }

  @Test fun offerRejectsPublicHostsBadPortsAndBadIds() {
    val good = MusicHandoffWire.encode(MusicHandoffWire.Offer("a1b2c3d4e5f6", "living", "10.0.0.5", 80, "Family"))
    assertNull(MusicHandoffWire.decodeOffer(JSONObject(good.toString()).put("h", "8.8.8.8")))
    assertNull(MusicHandoffWire.decodeOffer(JSONObject(good.toString()).put("h", "evil.example")))
    assertNull(MusicHandoffWire.decodeOffer(JSONObject(good.toString()).put("p", 0)))
    assertNull(MusicHandoffWire.decodeOffer(JSONObject(good.toString()).put("o", "XYZ")))
    assertNull(MusicHandoffWire.decodeOffer(JSONObject(good.toString()).put("to", "bad id!")))
    assertNull(MusicHandoffWire.decodeOffer(JSONObject(good.toString()).put("fn", "")))
  }

  @Test fun ackAndRecallRoundTripAndRejectUnknownStatus() {
    val ack = MusicHandoffWire.Ack("a1b2c3d4", "family", MusicHandoffStatus.DECLINED_QUIET, "Living Area")
    assertEquals(ack, MusicHandoffWire.decodeAck(MusicHandoffWire.encode(ack)))
    assertNull(MusicHandoffWire.decodeAck(MusicHandoffWire.encode(ack).put("st", "zz")))
    val recall = MusicHandoffWire.Recall("a1b2c3d4", "living", "Family Area")
    assertEquals(recall, MusicHandoffWire.decodeRecall(MusicHandoffWire.encode(recall)))
    assertNull(MusicHandoffWire.decodeRecall(MusicHandoffWire.encode(recall).put("o", "")))
  }

  // ---- signed fetch ----

  private fun header(body: String, from: String = "living", ts: Long = now, nonce: String = "nonce1", k: ByteArray = key) =
      MusicHandoffAuth.requestHeader(k, from, nonce, ts, body)

  private fun verify(h: String?, body: String, replay: HouseholdMessage.ReplayGuard = HouseholdMessage.ReplayGuard(),
                     self: String = "family") = MusicHandoffAuth.verifyRequest(h, body, key, now, replay, self)

  @Test fun signedFetchVerifies() {
    val body = MusicHandoffAuth.fetchBody("a1b2c3d4")
    val req = verify(header(body), body)
    assertEquals(MusicHandoffAuth.Request("living", "nonce1", "a1b2c3d4"), req)
  }

  @Test fun unsignedForgedOrSwappedRequestsAreRejected() {
    val body = MusicHandoffAuth.fetchBody("a1b2c3d4")
    assertNull(verify(null, body))
    assertNull(verify("garbage", body))
    assertNull(verify(header(body, k = otherKey), body))
    assertNull(verify(header(body), MusicHandoffAuth.fetchBody("deadbeef")))
  }

  @Test fun replayedAndStaleRequestsAreRejected() {
    val body = MusicHandoffAuth.fetchBody("a1b2c3d4")
    val replay = HouseholdMessage.ReplayGuard()
    val h = header(body)
    assertNotNull(verify(h, body, replay))
    assertNull(verify(h, body, replay))
    assertNull(verify(header(body, ts = now - HouseholdMessage.MAX_SKEW_MS - 1, nonce = "n2"), body))
  }

  @Test fun listSyncHeadersAndSelfCallsDoNotOpenTheMusicRoute() {
    val body = MusicHandoffAuth.fetchBody("a1b2c3d4")
    val listHeader = HouseholdListPeerAuth.requestHeader(key, "living", "n3", now, body)
    assertNull(verify(listHeader, body))
    assertNull(verify(header(body, from = "family", nonce = "n4"), body))
  }

  @Test fun responseSignatureIsBoundToTheNonceAndBody() {
    val sig = MusicHandoffAuth.signResponse(key, "nonce1", "payload")
    assertTrue(MusicHandoffAuth.verifyResponse(key, "nonce1", "payload", sig))
    assertFalse(MusicHandoffAuth.verifyResponse(key, "nonce2", "payload", sig))
    assertFalse(MusicHandoffAuth.verifyResponse(key, "nonce1", "payload!", sig))
    assertFalse(MusicHandoffAuth.verifyResponse(key, "nonce1", "payload", null))
    // A list-sync response signature can't be replayed as a music one.
    assertFalse(MusicHandoffAuth.verifyResponse(key, "nonce1", "payload",
        HouseholdListPeerAuth.signResponse(key, "nonce1", "payload")))
  }
}
