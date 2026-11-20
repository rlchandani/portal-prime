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

/** The chores request/response auth edges not covered by the round-trip tests. */
class ChorePeerAuthCoverageTest {
  private val key = ByteArray(32) { it.toByte() }
  private val now = 1_800_000_000_000L

  @Test fun missingHeaderIsRejected() {
    assertNull(ChorePeerAuth.verifyRequest(null, "{}", key, now, HouseholdMessage.ReplayGuard(), "living"))
  }

  @Test fun bodyPinThatIsNotAStringOrMissingIsRejected() {
    for ((nonce, body) in listOf("n1" to JSONObject().put("s", 12), "n2" to JSONObject(), "n3" to JSONObject().put("s", JSONObject()))) {
      val header = HouseholdMessage.encode(HouseholdMessage(ChorePeerAuth.REQUEST_TYPE, "kitchen", now, nonce, body), key)
      assertNull(ChorePeerAuth.verifyRequest(header, "{}", key, now, HouseholdMessage.ReplayGuard(), "living"))
    }
  }

  @Test fun selfNullStillAcceptsAPeer() {
    val header = ChorePeerAuth.requestHeader(key, "kitchen", "n4", now, "{}")
    assertEquals(ChorePeerAuth.Request("kitchen", "n4"),
        ChorePeerAuth.verifyRequest(header, "{}", key, now, HouseholdMessage.ReplayGuard(), null))
  }

  @Test fun responseVerificationRejectsNullAndTruncatedSignatures() {
    val sig = ChorePeerAuth.signResponse(key, "n1", "body")
    assertTrue(ChorePeerAuth.verifyResponse(key, "n1", "body", sig))
    assertFalse(ChorePeerAuth.verifyResponse(key, "n1", "body", null))
    assertFalse(ChorePeerAuth.verifyResponse(key, "n1", "body", ""))
    assertFalse(ChorePeerAuth.verifyResponse(key, "n1", "body2", sig))
    assertFalse("list-domain signature is not a chores signature",
        ChorePeerAuth.verifyResponse(key, "n1", "body", HouseholdListPeerAuth.signResponse(key, "n1", "body")))
  }

  @Test fun signalRoundTripsAndRejectsPublicHosts() {
    val signal = HouseholdListPeerAuth.Signal("0123456789ab", "192.168.1.9", 8723)
    assertEquals(signal, ChorePeerAuth.parseSignal(ChorePeerAuth.signalBody(signal)))
    assertNull(ChorePeerAuth.parseSignal(ChorePeerAuth.signalBody(signal.copy(host = "192.0.2.9"))))
    assertNull(ChorePeerAuth.parseSignal(JSONObject()))
  }
}
