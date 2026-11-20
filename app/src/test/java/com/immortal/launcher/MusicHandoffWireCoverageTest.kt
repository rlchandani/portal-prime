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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Music move offers/acks/recalls and the signed queue fetch. */
class MusicHandoffWireCoverageTest {
  private val key = ByteArray(32) { it.toByte() }
  private val now = 1_800_000_000_000L
  private val offer = MusicHandoffWire.Offer("abcdef12", "den", "192.168.1.20", 8723, "Kitchen")
  private val ack = MusicHandoffWire.Ack("abcdef12", "kitchen", MusicHandoffStatus.NEEDS_TAP, "Den")
  private val recall = MusicHandoffWire.Recall("abcdef12", "den", "Kitchen")

  @Test fun allThreeRoundTrip() {
    assertEquals(offer, MusicHandoffWire.decodeOffer(MusicHandoffWire.encode(offer)))
    assertEquals(ack, MusicHandoffWire.decodeAck(MusicHandoffWire.encode(ack)))
    assertEquals(recall, MusicHandoffWire.decodeRecall(MusicHandoffWire.encode(recall)))
    for (s in MusicHandoffStatus.entries) {
      assertEquals(s, MusicHandoffWire.decodeAck(MusicHandoffWire.encode(ack.copy(status = s)))!!.status)
    }
  }

  @Test fun offerRejections() {
    val bad: List<Pair<String, Any>> = listOf("o" to "ABCDEF12", "o" to "abc", "to" to "", "h" to "8.8.8.8", "h" to 5,
        "p" to 0, "p" to 65_536, "p" to "80", "fn" to "")
    for ((f, v) in bad) assertNull("$f=$v", MusicHandoffWire.decodeOffer(MusicHandoffWire.encode(offer).put(f, v)))
    for (f in listOf("o", "to", "h", "p", "fn")) {
      assertNull("missing $f", MusicHandoffWire.decodeOffer(MusicHandoffWire.encode(offer).apply { remove(f) }))
    }
  }

  @Test fun ackAndRecallRejections() {
    for ((f, v) in listOf<Pair<String, Any>>("o" to "x", "to" to "a b", "st" to "?", "fn" to "")) {
      assertNull("ack $f", MusicHandoffWire.decodeAck(MusicHandoffWire.encode(ack).put(f, v)))
    }
    for ((f, v) in listOf<Pair<String, Any>>("o" to "x", "to" to "a b", "fn" to "")) {
      assertNull("recall $f", MusicHandoffWire.decodeRecall(MusicHandoffWire.encode(recall).put(f, v)))
    }
    assertNull(MusicHandoffStatus.fromWire(null))
  }

  @Test fun fetchRequestVerifiesOnceAndNeedsAValidOfferId() {
    val body = MusicHandoffAuth.fetchBody("abcdef12")
    val header = MusicHandoffAuth.requestHeader(key, "kitchen", "n1", now, body)
    val guard = HouseholdMessage.ReplayGuard()
    assertEquals(MusicHandoffAuth.Request("kitchen", "n1", "abcdef12"),
        MusicHandoffAuth.verifyRequest(header, body, key, now, guard, "den"))
    assertNull("replay", MusicHandoffAuth.verifyRequest(header, body, key, now, guard, "den"))
    assertNull(MusicHandoffAuth.verifyRequest(null, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
    assertNull("self", MusicHandoffAuth.verifyRequest(header, body, key, now, HouseholdMessage.ReplayGuard(), "kitchen"))
    assertNull("swapped body", MusicHandoffAuth.verifyRequest(header, MusicHandoffAuth.fetchBody("abcdef13"), key, now,
        HouseholdMessage.ReplayGuard(), "den"))
    val junk = "not json"
    assertNull("pinned junk body", MusicHandoffAuth.verifyRequest(MusicHandoffAuth.requestHeader(key, "kitchen", "n2", now, junk),
        junk, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val badId = MusicHandoffAuth.fetchBody("XYZ")
    assertNull(MusicHandoffAuth.verifyRequest(MusicHandoffAuth.requestHeader(key, "kitchen", "n3", now, badId),
        badId, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val other = HouseholdMessage.encode(HouseholdMessage("music_offer", "kitchen", now, "n4", JSONObject().put("s", "x")), key)
    assertNull("other type", MusicHandoffAuth.verifyRequest(other, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
    val numeric = HouseholdMessage.encode(HouseholdMessage(MusicHandoffAuth.FETCH_TYPE, "kitchen", now, "n5", JSONObject().put("s", 1)), key)
    assertNull("non-string pin", MusicHandoffAuth.verifyRequest(numeric, body, key, now, HouseholdMessage.ReplayGuard(), "den"))
  }

  @Test fun responseSignature() {
    val sig = MusicHandoffAuth.signResponse(key, "n1", "q")
    assertTrue(MusicHandoffAuth.verifyResponse(key, "n1", "q", sig))
    assertFalse(MusicHandoffAuth.verifyResponse(key, "n1", "q", null))
    assertFalse(MusicHandoffAuth.verifyResponse(key, "n1", "q2", sig))
  }
}
