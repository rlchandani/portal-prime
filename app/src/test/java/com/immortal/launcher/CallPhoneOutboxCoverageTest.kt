/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Phone outbox rules: queue limits at their edges, the 7-day window, and the stored index validation. */
class CallPhoneOutboxCoverageTest {
  private val phone = "ph0123456789abcdef"
  private val otherPhone = "phfedcba9876543210"
  private val now = 1_800_000_000_000L
  private val plenty = 10L * 1024 * 1024 * 1024
  private val sealed1k = CallMessageCrypto.sealedLength(1_000)

  private fun out(id: String, phoneId: String = phone, at: Long = now, z: Long = sealed1k) = PhoneOutMessage(id, phoneId, "Kitchen",
      12_000, z, CallMessageCrypto.chunkCount(CallMessageCrypto.plainLength(z)), false, at)

  @Test fun queueRefusesEmptyImpossibleAndOversizedBodies() {
    assertEquals(PhoneOutbox.Refusal.TOO_LARGE, PhoneOutbox.canQueue(emptyList(), phone, -5, plenty))
    assertEquals(PhoneOutbox.Refusal.TOO_LARGE, PhoneOutbox.canQueue(emptyList(), phone, 0, plenty))
    val maxSealed = CallMessageCrypto.sealedLength(CallMessageLimits.MAX_BYTES)
    assertNull(PhoneOutbox.canQueue(emptyList(), phone, maxSealed, plenty))
    assertEquals(PhoneOutbox.Refusal.TOO_LARGE, PhoneOutbox.canQueue(emptyList(), phone, CallMessageCrypto.sealedLength(CallMessageLimits.MAX_BYTES + 1), plenty))
  }

  @Test fun theWholeOutboxHasAByteBudgetAcrossPhones() {
    val big = CallMessageCrypto.sealedLength(CallMessageLimits.MAX_BYTES)
    val three = (1..3).map { out("00000000000000b$it", phoneId = if (it == 1) phone else otherPhone, z = big) }
    val total = three.sumOf { it.sealedBytes }
    val room = PhoneOutbox.MAX_TOTAL_BYTES - total
    assertTrue("fixture should leave less than one big message of room", room in 1 until big)
    assertEquals(PhoneOutbox.Refusal.FULL, PhoneOutbox.canQueue(three, "ph1111111111111111", room + 1, plenty))
    assertNotEquals(PhoneOutbox.Refusal.FULL, PhoneOutbox.canQueue(three, "ph1111111111111111", room, plenty))
  }

  @Test fun freeSpaceMustStayAboveTheFloorAfterQueueing() {
    val floor = CallMessageLimits.MIN_FREE_BYTES
    assertNull(PhoneOutbox.canQueue(emptyList(), phone, sealed1k, floor + sealed1k))
    assertEquals(PhoneOutbox.Refusal.NO_SPACE, PhoneOutbox.canQueue(emptyList(), phone, sealed1k, floor + sealed1k - 1))
  }

  @Test fun fourWaitingStillLeavesRoomForAFifth() {
    val four = (0 until PhoneOutbox.MAX_PER_PHONE - 1).map { out("00000000000000a$it") }
    assertNull(PhoneOutbox.canQueue(four, phone, sealed1k, plenty))
  }

  @Test fun pruneKeepsTheFullWeekAndDropsFutureDatedEntries() {
    val edge = out("00000000000000c1", at = now - PhoneOutbox.KEEP_MS)
    val future = out("00000000000000c2", at = now + 1)
    val fresh = out("00000000000000c3", at = now)
    assertEquals(listOf(edge, fresh), PhoneOutbox.prune(listOf(edge, future, fresh), now))
  }

  @Test fun findNeedsBothIdAndPhone() {
    val m = out("00000000000000d1")
    assertEquals(m, PhoneOutbox.find(listOf(m), phone, m.id))
    assertNull(PhoneOutbox.find(listOf(m), phone, "00000000000000d2"))
    assertTrue(PhoneOutbox.forPhone(listOf(m), otherPhone).isEmpty())
  }

  @Test fun announceCarriesEveryFieldAndOmitsAMissingCallId() {
    val m = out("00000000000000e1").copy(audioOnly = true)
    val vm = PhoneOutbox.announce(m)
    assertEquals(m.id, vm.getString("i"))
    assertEquals("Kitchen", vm.getString("fn"))
    assertEquals(12_000L, vm.getLong("ms"))
    assertEquals(m.sealedBytes, vm.getLong("z"))
    assertEquals(m.chunks, vm.getInt("c"))
    assertTrue(vm.getBoolean("a"))
    assertEquals(now, vm.getLong("at"))
    assertFalse(vm.has("ci"))
  }

  @Test fun decodeOfNothingIsEmpty() {
    assertEquals(emptyList<PhoneOutMessage>(), PhoneOutbox.decode(null))
    assertEquals(emptyList<PhoneOutMessage>(), PhoneOutbox.decode("{}"))
    assertEquals(emptyList<PhoneOutMessage>(), PhoneOutbox.decode("[1, \"x\", null]"))
  }

  @Test fun decodeDropsEachMalformedField() {
    val good = JSONObject(PhoneOutbox.encode(listOf(out("00000000000000f1")))
        .let { JSONArray(it).getJSONObject(0).toString() })
    fun with(key: String, value: Any?) = JSONObject(good.toString()).apply { if (value == null) remove(key) else put(key, value) }
    val broken = listOf(
        with("fn", null), with("fn", "   "),
        with("ms", null), with("ms", 0), with("ms", "12000"),
        with("z", null), with("z", -1),
        with("c", null), with("c", "1"),
        with("at", null), with("at", "now"),
    )
    val arr = JSONArray().put(good).apply { broken.forEach { put(it) } }
    val decoded = PhoneOutbox.decode(arr.toString())
    assertEquals(1, decoded.size)
    assertEquals("00000000000000f1", decoded.single().id)
  }

  @Test fun decodeDefaultsMissingAudioFlagAndDropsAJunkCallId() {
    val raw = JSONArray().put(JSONObject(JSONArray(PhoneOutbox.encode(listOf(out("00000000000000f2")))).getJSONObject(0).toString())
        .apply { remove("a") }.put("ci", "../not-a-call"))
    val m = PhoneOutbox.decode(raw.toString()).single()
    assertFalse(m.audioOnly)
    assertNull(m.callId)
  }
}
