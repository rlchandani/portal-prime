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

class HouseholdListPeerAuthTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }
  private val now = 1_800_000_000_000L
  private val body = """{"v":1,"items":[]}"""

  private fun header(k: ByteArray = key, b: String = body, nonce: String = "n0000001", ts: Long = now) =
      HouseholdListPeerAuth.requestHeader(k, "portal-fa", nonce, ts, b)!!

  @Test fun signedExchange_isAccepted_onceOnly() {
    val guard = HouseholdMessage.ReplayGuard()
    val h = header()
    val req = HouseholdListPeerAuth.verifyRequest(h, body, key, now, guard, self = "portal-la")
    assertEquals("portal-fa", req?.from)
    assertEquals("n0000001", req?.nonce)
    assertNull("replay", HouseholdListPeerAuth.verifyRequest(h, body, key, now, guard, self = "portal-la"))
  }

  @Test fun wrongKeyTamperedBodyStaleSelfOrMissing_areRejected() {
    val guard = HouseholdMessage.ReplayGuard()
    assertNull(HouseholdListPeerAuth.verifyRequest(header(k = other), body, key, now, guard, "portal-la"))
    assertNull(HouseholdListPeerAuth.verifyRequest(header(nonce = "n2"), """{"v":1,"items":[1]}""", key, now, guard, "portal-la"))
    assertNull(HouseholdListPeerAuth.verifyRequest(header(nonce = "n3", ts = now - 600_000), body, key, now, guard, "portal-la"))
    assertNull(HouseholdListPeerAuth.verifyRequest(header(nonce = "n4"), body, key, now, guard, "portal-fa"))
    assertNull(HouseholdListPeerAuth.verifyRequest(null, body, key, now, guard, "portal-la"))
  }

  @Test fun response_isBoundToTheRequestNonce() {
    val sig = HouseholdListPeerAuth.signResponse(key, "n0000001", body)
    assertTrue(HouseholdListPeerAuth.verifyResponse(key, "n0000001", body, sig))
    assertFalse(HouseholdListPeerAuth.verifyResponse(key, "n0000002", body, sig))
    assertFalse(HouseholdListPeerAuth.verifyResponse(key, "n0000001", "$body ", sig))
    assertFalse(HouseholdListPeerAuth.verifyResponse(other, "n0000001", body, sig))
  }

  @Test fun signal_carriesOnlyAPrivateLanAddress() {
    val signal = HouseholdListPeerAuth.Signal("0123456789ab", "192.168.1.20", 8723)
    assertEquals(signal, HouseholdListPeerAuth.parseSignal(HouseholdListPeerAuth.signalBody(signal)))
    assertTrue(HouseholdListPeerAuth.signalBody(signal).toString().length < 120)
    listOf("8.8.8.8", "127.0.0.1", "example.com", "192.168.1.300", "169.254.1.1", "").forEach { host ->
      assertNull(host, HouseholdListPeerAuth.parseSignal(JSONObject().put("d", "0123456789ab").put("h", host).put("p", 8723)))
    }
    assertNull(HouseholdListPeerAuth.parseSignal(JSONObject().put("d", "0123456789ab").put("h", "10.0.0.4").put("p", 70000)))
    assertNull(HouseholdListPeerAuth.parseSignal(JSONObject().put("d", "zz").put("h", "10.0.0.4").put("p", 8723)))
    assertNotNull(HouseholdListPeerAuth.parseSignal(JSONObject().put("d", "0123456789ab").put("h", "172.20.3.4").put("p", 8723)))
  }
}
