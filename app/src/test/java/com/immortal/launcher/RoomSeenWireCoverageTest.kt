/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A correctly sealed but hostile plaintext (from a peer holding the household key) is still
 * validated field by field; envelope junk is refused before any decryption.
 */
class RoomSeenWireCoverageTest {
  private val key = ByteArray(32) { it.toByte() }
  private val from = "portal-kitchen-1"
  private val nonce = "0011223344556677"
  private val seen = RoomSeenWire.Seen("mayaaaaa", "Maya", 3, "Kitchen Portal", "kitchen", 1_500L)

  /** Seals [plain] exactly as a peer would (derived key, sender + nonce bound as AAD). */
  private fun seal(plain: String, n: String = nonce): JSONObject {
    val iv = ByteArray(12) { (it + 9).toByte() }
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(RoomSeenWire.key(key), "AES"), GCMParameterSpec(128, iv))
      updateAAD("hearthpane-room-seen|v1|$from|$n".toByteArray(Charsets.UTF_8))
    }
    val sealed = c.doFinal(plain.toByteArray(Charsets.UTF_8))
    return JSONObject().put("v", 1).put("n", n).put("s", HouseholdKey.toHex(iv + sealed))
  }

  private fun plain() = JSONObject().put("i", "mayaaaaa").put("fn", "Kitchen Portal").put("nm", "Maya").put("c", 3)
      .put("a", 1_500L).put("rm", "kitchen")

  private fun open(o: JSONObject) = RoomSeenWire.decode(seal(o.toString()), key, from)

  @Test fun theTestSealerMatchesTheRealOne() {
    assertEquals(seen, open(plain()))
  }

  @Test fun envelopeJunkIsRefused() {
    val good = seal(plain().toString())
    assertNull(RoomSeenWire.decode(JSONObject(good.toString()).put("v", 2), key, from))
    assertNull(RoomSeenWire.decode(JSONObject(good.toString()).put("n", "XYZ"), key, from))
    assertNull(RoomSeenWire.decode(JSONObject(good.toString()).put("n", 5), key, from))
    assertNull(RoomSeenWire.decode(JSONObject(good.toString()).put("s", 5), key, from))
    assertNull(RoomSeenWire.decode(JSONObject(good.toString()).put("s", "a".repeat(901)), key, from))
    assertNull(RoomSeenWire.decode(JSONObject(good.toString()).put("s", "zz"), key, from))
    assertNull("too short for iv + tag", RoomSeenWire.decode(JSONObject(good.toString()).put("s", "00".repeat(28)), key, from))
    assertNull("other nonce breaks the AAD", RoomSeenWire.decode(JSONObject(good.toString()).put("n", "7766554433221100"), key, from))
  }

  @Test fun aBadKeyLengthIsRefusedRatherThanThrown() {
    assertNull(RoomSeenWire.decode(seal(plain().toString()), ByteArray(0), from))
    assertNull(RoomSeenWire.encode(seen, ByteArray(0), from))
  }

  @Test fun sealedNonJsonIsRefused() {
    assertNull(RoomSeenWire.decode(seal("not json at all"), key, from))
  }

  @Test fun sealedFieldsAreStillValidated() {
    val bad: List<Pair<String, Any>> = listOf("i" to "MAYA", "i" to 5, "fn" to "", "fn" to 3, "nm" to "   ", "nm" to 1,
        "c" to -1, "c" to 64, "c" to "3", "a" to -1, "a" to RoomSeenWire.MAX_AGE_MS, "a" to "1")
    for ((field, value) in bad) assertNull("$field=$value", open(plain().put(field, value)))
    for (field in listOf("i", "fn", "nm", "c", "a")) assertNull("missing $field", open(plain().apply { remove(field) }))
  }

  @Test fun roomIsOptionalAndJunkRoomsAreDropped() {
    assertNull(open(plain().put("rm", ""))!!.roomId)
    assertNull(open(plain().put("rm", "a b"))!!.roomId)
    assertNull(open(plain().put("rm", 3))!!.roomId)
    assertNull(open(plain().apply { remove("rm") })!!.roomId)
    assertEquals("r".repeat(64), open(plain().put("rm", "r".repeat(64)))!!.roomId)
    assertNull(open(plain().put("rm", "r".repeat(65)))!!.roomId)
  }

  @Test fun forgetNeedsOnlyIdAndPortal() {
    val forget = open(JSONObject().put("i", "mayaaaaa").put("fn", "Kitchen Portal").put("x", 1))!!
    assertEquals(true, forget.forget)
    assertNotNull("x other than 1 is a sighting", open(plain().put("x", 2)))
  }

  @Test fun encodeClampsColourAndAgeAndDropsJunkRoom() {
    val out = RoomSeenWire.decode(RoomSeenWire.encode(seen.copy(colorIndex = 99, ageMs = -5, roomId = "bad room"), key, from)!!, key, from)!!
    assertEquals(63, out.colorIndex)
    assertEquals(0L, out.ageMs)
    assertNull(out.roomId)
    assertNull("no room", RoomSeenWire.decode(RoomSeenWire.encode(seen.copy(roomId = null), key, from)!!, key, from)!!.roomId)
  }

  @Test fun encodeRefusesBadIdsAndBlankNames() {
    assertNull(RoomSeenWire.encode(seen.copy(personId = "x"), key, from))
    assertNull(RoomSeenWire.encode(seen.copy(name = " \u0007 "), key, from))
    assertNotNull("a forget needs no name", RoomSeenWire.encode(seen.copy(name = "", forget = true), key, from))
  }

  @Test fun maxAgeIsClampedToTheLastAcceptedValueOrRefused() {
    val atMax = RoomSeenWire.encode(seen.copy(ageMs = Long.MAX_VALUE), key, from)!!
    // Encode clamps to MAX_AGE_MS, which decode's half-open range refuses: such a sighting is too old to show.
    assertNull(RoomSeenWire.decode(atMax, key, from))
    assertEquals(RoomSeenWire.MAX_AGE_MS - 1, open(plain().put("a", RoomSeenWire.MAX_AGE_MS - 1))!!.ageMs)
  }
}
