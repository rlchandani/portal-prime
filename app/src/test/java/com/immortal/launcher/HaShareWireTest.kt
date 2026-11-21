/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `/remote/hapeer` wire: the per-request seal, the signed request and the connection checks. */
class HaShareWireTest {
  private val key = ByteArray(32) { it.toByte() }
  private val otherKey = ByteArray(32) { (it + 1).toByte() }
  private val random = SecureRandom()
  private val token = "eyJhbGciOiJIUzI1NiJ9.secret-token-value.sig"
  private val conn = HaShareWire.Connection.of("http://homeassistant.local:8123/", token)!!

  private fun seal(requester: String = "living-1", source: String = "family-1", nonce: String = "n0nce1") =
      HaShareWire.sharePayload(conn, "Family Area", key, requester, source, nonce, random)!!

  @Test fun theSealOpensOnlyForTheSameKeyRequesterSourceAndNonce() {
    val payload = seal()
    val opened = HaShareWire.openShare(payload, key, "living-1", "family-1", "n0nce1")!!
    assertEquals("Family Area", opened.name)
    assertEquals("http://homeassistant.local:8123", opened.connection.url)
    assertEquals(token, opened.connection.token)
    assertNull(HaShareWire.openShare(payload, otherKey, "living-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare(payload, key, "mini-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare(payload, key, "living-1", "kitchen-1", "n0nce1"))
    assertNull(HaShareWire.openShare(payload, key, "living-1", "family-1", "n0nce2"))
  }

  @Test fun theTokenNeverTravelsInTheClearAndEachSealIsFresh() {
    val a = seal()
    val b = seal()
    assertFalse(a.contains(token))
    assertFalse(a.contains("homeassistant"))
    assertTrue(a != b) // random IV
  }

  @Test fun aTamperedOrMalformedSealIsRefused() {
    val o = JSONObject(seal())
    val s = o.getString("s")
    val flipped = s.substring(0, s.length - 1) + (if (s.last() == '0') '1' else '0')
    assertNull(HaShareWire.openShare(JSONObject(o.toString()).put("s", flipped).toString(), key, "living-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare(JSONObject(o.toString()).put("s", "zz").toString(), key, "living-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare(JSONObject(o.toString()).put("s", "ab".repeat(4_000)).toString(), key, "living-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare(JSONObject(o.toString()).put("v", 2).toString(), key, "living-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare("{not json", key, "living-1", "family-1", "n0nce1"))
    assertNull(HaShareWire.openShare(null, key, "living-1", "family-1", "n0nce1"))
  }

  @Test fun theSealKeyDiffersPerRequesterAndNonce() {
    val base = HaShareWire.sealKey(key, "living-1", "n1")
    assertEquals(32, base.size)
    assertFalse(base.contentEquals(HaShareWire.sealKey(key, "mini-1", "n1")))
    assertFalse(base.contentEquals(HaShareWire.sealKey(key, "living-1", "n2")))
    assertFalse(base.contentEquals(HaShareWire.sealKey(otherKey, "living-1", "n1")))
  }

  @Test fun onlyHttpAddressesAndBearerShapedTokensAreConnections() {
    assertNotNull(HaShareWire.Connection.of("https://ha.example.com", token))
    assertNull(HaShareWire.Connection.of("ftp://ha.local", token))
    assertNull(HaShareWire.Connection.of("javascript:alert(1)", token))
    assertNull(HaShareWire.Connection.of("http://user:pw@ha.local", token))
    assertNull(HaShareWire.Connection.of("http://", token))
    assertNull(HaShareWire.Connection.of("http://ha.local/" + "a".repeat(600), token))
    assertNull(HaShareWire.Connection.of("http://ha.local", "short"))
    assertNull(HaShareWire.Connection.of("http://ha.local", "has a space in the token value"))
    assertNull(HaShareWire.Connection.of("http://ha.local", "line\nbreak-in-the-token-value"))
    assertNull(HaShareWire.Connection.of("http://ha.local", "x".repeat(HaShareWire.MAX_TOKEN + 1)))
    assertNull(HaShareWire.Connection.of(null, token))
    assertNull(HaShareWire.Connection.of("http://ha.local", null))
  }

  @Test fun aConnectionNeverPrintsItsSecret() {
    assertFalse(conn.toString().contains(token))
    assertFalse(conn.toString().contains("homeassistant"))
  }

  @Test fun sameComparesAddressAndToken() {
    assertTrue(HaShareWire.same(conn, HaShareWire.Connection.of("http://homeassistant.local:8123", token)!!))
    assertFalse(HaShareWire.same(conn, HaShareWire.Connection.of("http://homeassistant.local:8123", token + "x")!!))
    assertFalse(HaShareWire.same(conn, HaShareWire.Connection.of("http://other.local:8123", token)!!))
  }

  @Test fun theRequestIsSignedPinnedAndSingleUse() {
    val body = HaShareWire.requestBody(HaShareWire.Op.SHARE)
    val header = HaShareWire.requestHeader(key, "living-1", "nonce-1", 1_000L, body)!!
    val replay = HouseholdMessage.ReplayGuard()
    val req = HaShareWire.verifyRequest(header, body, key, 1_000L, replay, "family-1")!!
    assertEquals(HaShareWire.Request("living-1", "nonce-1", HaShareWire.Op.SHARE), req)
    // Replayed, swapped body, wrong key, stale, from ourselves, missing.
    assertNull(HaShareWire.verifyRequest(header, body, key, 1_000L, replay, "family-1"))
    val fresh = { HouseholdMessage.ReplayGuard() }
    assertNull(HaShareWire.verifyRequest(header, HaShareWire.requestBody(HaShareWire.Op.PROBE), key, 1_000L, fresh(), "family-1"))
    assertNull(HaShareWire.verifyRequest(header, body, otherKey, 1_000L, fresh(), "family-1"))
    assertNull(HaShareWire.verifyRequest(header, body, key, 1_000L + HouseholdMessage.MAX_SKEW_MS + 1, fresh(), "family-1"))
    assertNull(HaShareWire.verifyRequest(header, body, key, 1_000L, fresh(), "living-1"))
    assertNull(HaShareWire.verifyRequest(null, body, key, 1_000L, fresh(), "family-1"))
  }

  @Test fun anUnknownOpOrVersionIsRefused() {
    for (body in listOf("""{"v":1,"op":"dump"}""", """{"v":2,"op":"share"}""", """{"op":"share"}""", "not json")) {
      val header = HaShareWire.requestHeader(key, "living-1", "nonce-1", 1_000L, body)!!
      assertNull(body, HaShareWire.verifyRequest(header, body, key, 1_000L, HouseholdMessage.ReplayGuard(), "family-1"))
    }
  }

  @Test fun anotherPeerRoutesSignedHeaderIsNotAnHaShareRequest() {
    val body = HaShareWire.requestBody(HaShareWire.Op.SHARE)
    val settings = SettingsSyncWire.requestHeader(key, "living-1", "nonce-1", 1_000L, body)!!
    assertNull(HaShareWire.verifyRequest(settings, body, key, 1_000L, HouseholdMessage.ReplayGuard(), "family-1"))
    assertFalse(PeerRequestGate.admits(settings, HaShareWire.REQUEST_TYPE, key, 1_000L))
  }

  @Test fun theReplySignatureIsBoundToTheRequestNonce() {
    val sig = HaShareWire.signResponse(key, "nonce-1", "payload")
    assertTrue(HaShareWire.verifyResponse(key, "nonce-1", "payload", sig))
    assertFalse(HaShareWire.verifyResponse(key, "nonce-2", "payload", sig))
    assertFalse(HaShareWire.verifyResponse(key, "nonce-1", "payload2", sig))
    assertFalse(HaShareWire.verifyResponse(otherKey, "nonce-1", "payload", sig))
    assertFalse(HaShareWire.verifyResponse(key, "nonce-1", "payload", null))
  }

  @Test fun theProbeCarriesOnlyTheNameAndConnectedFlag() {
    val probe = HaShareWire.probePayload("Family Area")
    assertEquals(setOf("v", "fn", "c"), JSONObject(probe).keys().asSequence().toSet())
    assertEquals(HaShareWire.Probe("Family Area", true), HaShareWire.parseProbe(probe))
    assertNull(HaShareWire.parseProbe("""{"v":1,"c":true}"""))
    assertNull(HaShareWire.parseProbe("""{"v":9,"fn":"x","c":true}"""))
  }
}
