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

/** Signed Portal↔Portal household messages (shared list, dinner call, routines). */
class HouseholdMessageTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }
  private val now = 1_800_000_000_000L
  private fun msg(type: String = "dinner_call", from: String = "portal-a", ts: Long = now, nonce: String = "n1") =
      HouseholdMessage(type, from, ts, nonce, JSONObject().put("preset", "dinner"))

  @Test fun roundTripsWhenSignedWithTheHouseholdKey() {
    val wire = HouseholdMessage.encode(msg(), key)
    val back = HouseholdMessage.decode(wire, key, now, HouseholdMessage.ReplayGuard())!!
    assertEquals("dinner_call", back.type)
    assertEquals("portal-a", back.from)
    assertEquals("dinner", back.body.getString("preset"))
  }

  @Test fun rejectsAWrongKeyTamperingOrUnsignedPackets() {
    val wire = HouseholdMessage.encode(msg(), key)
    assertNull(HouseholdMessage.decode(wire, other, now, HouseholdMessage.ReplayGuard()))
    val tampered = wire.replace("portal-a", "portal-b")
    assertNull(HouseholdMessage.decode(tampered, key, now, HouseholdMessage.ReplayGuard()))
    assertNull(HouseholdMessage.decode("IMMORTAL_PING/1|Someone", key, now, HouseholdMessage.ReplayGuard()))
    assertNull(HouseholdMessage.decode("${HouseholdMessage.MAGIC}|{}|00", key, now, HouseholdMessage.ReplayGuard()))
  }

  @Test fun rejectsStaleOrFutureMessages() {
    val old = HouseholdMessage.encode(msg(ts = now - 121_000), key)
    val future = HouseholdMessage.encode(msg(ts = now + 121_000), key)
    assertNull(HouseholdMessage.decode(old, key, now, HouseholdMessage.ReplayGuard()))
    assertNull(HouseholdMessage.decode(future, key, now, HouseholdMessage.ReplayGuard()))
    assertNotNull(HouseholdMessage.decode(HouseholdMessage.encode(msg(ts = now - 60_000), key), key, now,
        HouseholdMessage.ReplayGuard()))
  }

  @Test fun rejectsReplaysOfTheSameNonce_butAcceptsRepeatedSendsWithFreshNonces() {
    val guard = HouseholdMessage.ReplayGuard()
    val wire = HouseholdMessage.encode(msg(nonce = "abc"), key)
    assertNotNull(HouseholdMessage.decode(wire, key, now, guard))
    assertNull(HouseholdMessage.decode(wire, key, now, guard))
    assertNotNull(HouseholdMessage.decode(HouseholdMessage.encode(msg(nonce = "abd"), key), key, now, guard))
  }

  @Test fun rejectsOversizedPacketsAndBadFields() {
    val big = HouseholdMessage(type = "list", from = "a", ts = now, nonce = "n",
        body = JSONObject().put("x", "y".repeat(HouseholdMessage.MAX_WIRE_CHARS)))
    assertNull(HouseholdMessage.encodeOrNull(big, key))
    assertNull(HouseholdMessage.encodeOrNull(msg(type = "Bad Type!"), key))
    assertNull(HouseholdMessage.encodeOrNull(msg(from = ""), key))
    assertNull(HouseholdMessage.encodeOrNull(msg(nonce = ""), key))
  }

  @Test fun keyMaterialValidation() {
    assertTrue(HouseholdKey.isValidKey(ByteArray(32)))
    assertFalse(HouseholdKey.isValidKey(ByteArray(16)))
    assertEquals(HouseholdKey.fingerprint(key), HouseholdKey.fingerprint(key.copyOf()))
    assertNotEquals(HouseholdKey.fingerprint(key), HouseholdKey.fingerprint(other))
    assertEquals(8, HouseholdKey.fingerprint(key).length)
    assertEquals("9b237533", HouseholdKey.fingerprint(key)) // known answer, identical in Toolkit
  }
}
