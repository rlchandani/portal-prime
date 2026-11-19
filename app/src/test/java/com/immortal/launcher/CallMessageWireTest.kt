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

/** The household-signed `vmsg_put` header and the signed acknowledgement. */
class CallMessageWireTest {
  private val a = "11111111-1111-1111-1111-111111111111"
  private val b = "22222222-2222-2222-2222-222222222222"
  private val id = "0123456789abcdef"
  private val key = ByteArray(32) { it.toByte() }
  private val now = 1_800_000_000_000L
  private val sealed = CallMessageCrypto.sealedLength(1_000_000)
  private val put = CallMessageWire.Put(id, b, "Kitchen", 48_000, sealed, CallMessageCrypto.chunkCount(1_000_000), false)

  private fun header(p: CallMessageWire.Put = put, from: String = a, nonce: String = "n".repeat(24), ts: Long = now, k: ByteArray = key) =
      CallMessageWire.requestHeader(k, from, nonce, ts, p)

  @Test fun verifiesAFreshHeaderForThisPortal() {
    val v = CallMessageWire.verify(header(), key, now, HouseholdMessage.ReplayGuard(), b)
    assertNotNull(v)
    assertEquals(a, v!!.from)
    assertEquals(put, v.put)
  }

  @Test fun refusesForgedStaleReplayedOrMisaddressed() {
    val guard = HouseholdMessage.ReplayGuard()
    assertNull(CallMessageWire.verify(header(k = ByteArray(32)), key, now, guard, b))
    assertNull(CallMessageWire.verify(header(ts = now - 10 * 60_000), key, now, guard, b))
    assertNull(CallMessageWire.verify(header(), key, now, guard, a))
    val h = header(nonce = "m".repeat(24))
    assertNotNull(CallMessageWire.verify(h, key, now, guard, b))
    assertNull(CallMessageWire.verify(h, key, now, guard, b))
    assertNull(CallMessageWire.verify(null, key, now, guard, b))
    assertNull(CallMessageWire.verify(header(from = b), key, now, HouseholdMessage.ReplayGuard(), b))
  }

  @Test fun phonesCannotSendMessages() {
    val phone = "ph0123456789abcdef"
    assertNull(CallMessageWire.verify(header(from = phone), key, now, HouseholdMessage.ReplayGuard(), b))
  }

  @Test fun otherMessageTypesAreNotAPut() {
    val other = HouseholdMessage.encode(HouseholdMessage("call_offer", a, now, "x".repeat(24), CallMessageWire.encode(put)), key)
    assertNull(CallMessageWire.verify(other, key, now, HouseholdMessage.ReplayGuard(), b))
  }

  @Test fun decodeRefusesUnsafeIdsAndImpossibleSizes() {
    val good = CallMessageWire.encode(put)
    assertNotNull(CallMessageWire.decode(good))
    assertNull(CallMessageWire.decode(JSONObject(good.toString()).put("i", "../../etc/passwd")))
    assertNull(CallMessageWire.decode(JSONObject(good.toString()).put("i", "0123456789ABCDEF")))
    assertNull(CallMessageWire.decode(JSONObject(good.toString()).put("z", 17)))
    assertNull(CallMessageWire.decode(JSONObject(good.toString()).put("c", 99)))
    assertNull(CallMessageWire.decode(JSONObject(good.toString()).put("ms", 0)))
    assertNull(CallMessageWire.decode(JSONObject(good.toString()).put("fn", "")))
  }

  @Test fun headerFitsOneHouseholdMessage() {
    assertNotNull(header(put.copy(fromName = "x".repeat(40))))
  }

  @Test fun ackIsBoundToTheNonceAndMessage() {
    val payload = CallMessageWire.ackPayload(id)
    val sig = CallMessageWire.signAck(key, "nonce1", payload)
    assertTrue(CallMessageWire.verifyAck(key, "nonce1", payload, sig, id))
    assertFalse(CallMessageWire.verifyAck(key, "nonce2", payload, sig, id))
    assertFalse(CallMessageWire.verifyAck(key, "nonce1", payload, sig, "fedcba9876543210"))
    assertFalse(CallMessageWire.verifyAck(ByteArray(32), "nonce1", payload, sig, id))
    assertFalse(CallMessageWire.verifyAck(key, "nonce1", payload, null, id))
  }
}
