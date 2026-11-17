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

/** The chores exchange is signed with the household key and can't be confused with the list's. */
class ChorePeerAuthTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }
  private val now = 1_800_000_000_000L
  private val body = JSONObject().put("name", "Kitchen").put("state", ChoreCodec.encodeState(ChoreFixtures.state)).toString()

  @Test fun aSignedRequestVerifiesOnceAndPinsItsBody() {
    val header = ChorePeerAuth.requestHeader(key, "kitchen", "nonce1", now, body)!!
    val replay = HouseholdMessage.ReplayGuard()
    assertEquals(ChorePeerAuth.Request("kitchen", "nonce1"), ChorePeerAuth.verifyRequest(header, body, key, now, replay, "living"))
    assertNull("replayed", ChorePeerAuth.verifyRequest(header, body, key, now, replay, "living"))
    val fresh = HouseholdMessage.ReplayGuard()
    assertNull("swapped body", ChorePeerAuth.verifyRequest(header, "$body ", key, now, fresh, "living"))
    assertNull("wrong key", ChorePeerAuth.verifyRequest(header, body, other, now, HouseholdMessage.ReplayGuard(), "living"))
    assertNull("from itself", ChorePeerAuth.verifyRequest(header, body, key, now, HouseholdMessage.ReplayGuard(), "kitchen"))
    assertNull("stale", ChorePeerAuth.verifyRequest(header, body, key, now + 10 * 60_000L, HouseholdMessage.ReplayGuard(), "living"))
  }

  @Test fun aListRequestIsNotAChoresRequest() {
    val list = HouseholdListPeerAuth.requestHeader(key, "kitchen", "nonce2", now, body)!!
    assertNull(ChorePeerAuth.verifyRequest(list, body, key, now, HouseholdMessage.ReplayGuard(), "living"))
  }

  @Test fun theReplyIsBoundToTheNonceAndDomain() {
    val sig = ChorePeerAuth.signResponse(key, "nonce1", body)
    assertTrue(ChorePeerAuth.verifyResponse(key, "nonce1", body, sig))
    assertFalse(ChorePeerAuth.verifyResponse(key, "nonce9", body, sig))
    assertFalse(ChorePeerAuth.verifyResponse(other, "nonce1", body, sig))
    assertFalse("a list reply can't stand in", ChorePeerAuth.verifyResponse(key, "nonce1", body,
        HouseholdListPeerAuth.signResponse(key, "nonce1", body)))
  }

  @Test fun theSignalCarriesADigestAndAPrivateAddress() {
    val signal = HouseholdListPeerAuth.Signal(ChoreCodec.digest(ChoreFixtures.state), "192.168.1.20", 8765)
    assertEquals(signal, ChorePeerAuth.parseSignal(ChorePeerAuth.signalBody(signal)))
    assertNull(ChorePeerAuth.parseSignal(ChorePeerAuth.signalBody(signal.copy(host = "8.8.8.8"))))
  }
}
