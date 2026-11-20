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

/** Video-message announcements: body validation, sealed-size consistency, sender rules and ack binding. */
class CallMessageWireCoverageTest {
  private val a = "kitchen"
  private val b = "den"
  private val id = "0123456789abcdef"
  private val key = ByteArray(32) { it.toByte() }
  private val now = 1_800_000_000_000L
  private val plain = 200_000L
  private val put = CallMessageWire.Put(id, b, "Kitchen", 48_000, CallMessageCrypto.sealedLength(plain),
      CallMessageCrypto.chunkCount(plain), audioOnly = true, callId = "fedcba9876543210")
  private val phone = CallPhoneId.of("token-x")

  private fun json() = CallMessageWire.encode(put)
  private fun header(from: String = a, nonce: String = "n1", p: CallMessageWire.Put = put, type: String = CallMessageWire.TYPE) =
      HouseholdMessage.encodeOrNull(HouseholdMessage(type, from, now, nonce, CallMessageWire.encode(p)), key)

  @Test fun roundTripsWithCallIdAndAudioFlag() {
    assertEquals(put, CallMessageWire.decode(json()))
    val noCall = put.copy(callId = null, audioOnly = false)
    assertFalse(CallMessageWire.encode(noCall).has("ci"))
    assertEquals(noCall, CallMessageWire.decode(CallMessageWire.encode(noCall)))
  }

  @Test fun malformedCallIdOnlyLosesTheLink() {
    assertNull(CallMessageWire.decode(json().put("ci", "NOT-A-CALL"))!!.callId)
  }

  @Test fun fieldRejections() {
    val bad: List<Pair<String, Any>> = listOf("i" to "x", "to" to "a b", "fn" to "", "ms" to 0, "ms" to "5",
        "z" to "1", "c" to "1", "z" to 16, "z" to 0, "c" to put.chunks + 1, "c" to 0)
    for ((field, value) in bad) assertNull("$field=$value", CallMessageWire.decode(json().put(field, value)))
    for (field in listOf("i", "to", "fn", "ms", "z", "c")) assertNull("missing $field", CallMessageWire.decode(json().apply { remove(field) }))
  }

  @Test fun aSealedSizeNoPlaintextCouldProduceIsRefused() {
    // One byte over a whole sealed chunk: the remainder is too small to hold a tag plus data.
    val impossible = CallMessageCrypto.SEALED_CHUNK.toLong() + 1
    assertNull(CallMessageWire.decode(json().put("z", impossible).put("c", 2)))
  }

  @Test fun verifyNeedsAHeaderAndAKnownSelf() {
    assertNull(CallMessageWire.verify(null, key, now, HouseholdMessage.ReplayGuard(), b))
    assertNull(CallMessageWire.verify(header(), key, now, HouseholdMessage.ReplayGuard(), null))
    assertNotNull(CallMessageWire.verify(header(), key, now, HouseholdMessage.ReplayGuard(), b))
  }

  @Test fun verifyRefusesOtherTypesPhonesAndBadBodies() {
    assertNull(CallMessageWire.verify(header(type = "call_ring"), key, now, HouseholdMessage.ReplayGuard(), b))
    assertNull(CallMessageWire.verify(header(from = phone), key, now, HouseholdMessage.ReplayGuard(), b))
    assertNull(CallMessageWire.verify(header(p = put.copy(durationMs = 0)), key, now, HouseholdMessage.ReplayGuard(), b))
    assertNull("addressed elsewhere", CallMessageWire.verify(header(p = put.copy(to = "attic")), key, now, HouseholdMessage.ReplayGuard(), b))
    assertNull("forged", CallMessageWire.verify(header(), ByteArray(32), now, HouseholdMessage.ReplayGuard(), b))
  }

  @Test fun verifyFromPhoneBindsTheSenderToThePairedPhone() {
    val h = header(from = phone)
    val v = CallMessageWire.verifyFromPhone(h, key, now, HouseholdMessage.ReplayGuard(), b, phone)!!
    assertEquals(phone, v.from)
    assertEquals("n1", v.nonce)
    assertNull(CallMessageWire.verifyFromPhone(null, key, now, HouseholdMessage.ReplayGuard(), b, phone))
    assertNull(CallMessageWire.verifyFromPhone(h, key, now, HouseholdMessage.ReplayGuard(), null, phone))
    assertNull("not a phone id", CallMessageWire.verifyFromPhone(header(), key, now, HouseholdMessage.ReplayGuard(), b, a))
    assertNull("other phone", CallMessageWire.verifyFromPhone(h, key, now, HouseholdMessage.ReplayGuard(), b, CallPhoneId.of("token-y")))
    assertNull("other type", CallMessageWire.verifyFromPhone(header(from = phone, type = "call_ring"), key, now,
        HouseholdMessage.ReplayGuard(), b, phone))
    assertNull("bad key", CallMessageWire.verifyFromPhone(h, ByteArray(32), now, HouseholdMessage.ReplayGuard(), b, phone))
    assertNull("misaddressed", CallMessageWire.verifyFromPhone(header(from = phone, p = put.copy(to = "attic")), key, now,
        HouseholdMessage.ReplayGuard(), b, phone))
    assertNull("bad body", CallMessageWire.verifyFromPhone(header(from = phone, p = put.copy(chunks = 99)), key, now,
        HouseholdMessage.ReplayGuard(), b, phone))
  }

  @Test fun ackIsBoundToNonceAndMessageId() {
    val payload = CallMessageWire.ackPayload(id)
    val sig = CallMessageWire.signAck(key, "n1", payload)
    assertTrue(CallMessageWire.verifyAck(key, "n1", payload, sig, id))
    assertFalse(CallMessageWire.verifyAck(key, "n1", payload, null, id))
    assertFalse(CallMessageWire.verifyAck(key, "n2", payload, sig, id))
    assertFalse("other message id", CallMessageWire.verifyAck(key, "n1", payload, sig, "aaaaaaaaaaaaaaaa"))
    val notOk = JSONObject().put("ok", false).put("i", id).toString()
    assertFalse(CallMessageWire.verifyAck(key, "n1", notOk, CallMessageWire.signAck(key, "n1", notOk), id))
    assertFalse("signed junk", CallMessageWire.verifyAck(key, "n1", "junk", CallMessageWire.signAck(key, "n1", "junk"), id))
  }
}
