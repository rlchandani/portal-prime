/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile wire forms that are correctly signed but structurally wrong, plus key / skew / replay edges. */
class HouseholdMessageCoverageTest {
  private val key = ByteArray(32) { it.toByte() }
  private val now = 1_800_000_000_000L
  private val magic = HouseholdMessage.MAGIC

  /** A wire whose HMAC is valid for [json], so only the structural checks can reject it. */
  private fun signed(json: String): String {
    val text = "$magic|$json"
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    return "$text|" + mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  private fun good() = JSONObject().put("t", "dinner_call").put("f", "portal-a").put("ts", now).put("n", "n1")
      .put("b", JSONObject().put("k", 1))

  @Test fun theSignedHelperProducesAnAcceptedWire() {
    assertNotNull(HouseholdMessage.authenticate(signed(good().toString()), key, now))
  }

  @Test fun encodeThrowsForAnInvalidMessageOrKey() {
    val msg = HouseholdMessage("dinner_call", "a", now, "n", JSONObject())
    assertThrows(IllegalArgumentException::class.java) { HouseholdMessage.encode(msg, ByteArray(16)) }
    assertThrows(IllegalArgumentException::class.java) { HouseholdMessage.encode(msg.copy(type = "X"), key) }
    assertNull(HouseholdMessage.encodeOrNull(msg, ByteArray(33)))
    assertNull("one-char type", HouseholdMessage.encodeOrNull(msg.copy(type = "a"), key))
    assertNull("type over 32", HouseholdMessage.encodeOrNull(msg.copy(type = "a" + "b".repeat(32)), key))
    assertNotNull(HouseholdMessage.encodeOrNull(msg.copy(type = "a" + "b".repeat(31)), key))
    assertNull("from with space", HouseholdMessage.encodeOrNull(msg.copy(from = "a b"), key))
    assertNull("nonce over 64", HouseholdMessage.encodeOrNull(msg.copy(nonce = "n".repeat(65)), key))
  }

  @Test fun authenticateRejectsWrongKeySizeOversizeAndMissingMagic() {
    val wire = signed(good().toString())
    assertNull(HouseholdMessage.authenticate(wire, ByteArray(16), now))
    assertNull(HouseholdMessage.authenticate(wire + " ".repeat(HouseholdMessage.MAX_WIRE_CHARS), key, now))
    assertNull(HouseholdMessage.authenticate("X" + wire, key, now))
    assertNull("magic alone", HouseholdMessage.authenticate("$magic|", key, now))
    assertNull("no body before signature", HouseholdMessage.authenticate("$magic|abc", key, now))
  }

  @Test fun authenticateRejectsTruncatedOrUppercaseSignatures() {
    val wire = signed(good().toString())
    assertNull(HouseholdMessage.authenticate(wire.dropLast(1), key, now))
    val sig = wire.substringAfterLast('|')
    assertNull(HouseholdMessage.authenticate(wire.substringBeforeLast('|') + "|" + sig.uppercase(), key, now))
  }

  @Test fun validSignatureOverNonJsonIsRejected() {
    assertNull(HouseholdMessage.authenticate(signed("not json"), key, now))
    assertNull(HouseholdMessage.authenticate(signed("[1,2]"), key, now))
  }

  @Test fun validSignatureOverMissingOrWrongTypedFieldsIsRejected() {
    for (field in listOf("t", "f", "ts", "n", "b")) {
      val j = good().apply { remove(field) }
      assertNull("missing $field", HouseholdMessage.authenticate(signed(j.toString()), key, now))
    }
    assertNull("ts not a number", HouseholdMessage.authenticate(signed(good().put("ts", "soon").toString()), key, now))
    assertNull("body not an object", HouseholdMessage.authenticate(signed(good().put("b", "x").toString()), key, now))
  }

  @Test fun validSignatureOverInvalidTokensIsRejected() {
    assertNull(HouseholdMessage.authenticate(signed(good().put("t", "Bad!").toString()), key, now))
    assertNull(HouseholdMessage.authenticate(signed(good().put("f", "a/b").toString()), key, now))
    assertNull(HouseholdMessage.authenticate(signed(good().put("n", "").toString()), key, now))
  }

  @Test fun skewBoundaryIsInclusive() {
    val edge = HouseholdMessage.MAX_SKEW_MS
    assertNotNull(HouseholdMessage.authenticate(signed(good().put("ts", now - edge).toString()), key, now))
    assertNotNull(HouseholdMessage.authenticate(signed(good().put("ts", now + edge).toString()), key, now))
    assertNull(HouseholdMessage.authenticate(signed(good().put("ts", now + edge + 1).toString()), key, now))
  }

  @Test fun replayGuardIsPerSenderAndForgetsTheEldestPastCapacity() {
    val guard = HouseholdMessage.ReplayGuard(capacity = 2)
    assertTrue(guard.firstSighting("a", "1"))
    assertTrue("same nonce from another sender", guard.firstSighting("b", "1"))
    assertFalse(guard.firstSighting("a", "1"))
    assertTrue(guard.firstSighting("a", "2")) // evicts a:1 (insertion order)
    assertTrue("evicted entry is forgotten", guard.firstSighting("a", "1"))
  }

  @Test fun decodeConsultsTheReplayGuardOnlyAfterAuthentication() {
    val guard = HouseholdMessage.ReplayGuard()
    val wire = signed(good().toString())
    assertNull("stale does not burn the nonce", HouseholdMessage.decode(wire, key, now + 10 * 60_000L, guard))
    assertEquals("portal-a", HouseholdMessage.decode(wire, key, now, guard)!!.from)
    assertNull(HouseholdMessage.decode(wire, key, now, guard))
  }
}
