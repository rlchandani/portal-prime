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

/** Video message store rules at their edges: duration slack, sent-copy budgets, 30-day expiry and codec. */
class CallMessageModelCoverageTest {
  private val now = 1_800_000_000_000L
  private val day = 24 * 60 * 60_000L
  private val free = 2L * 1024 * 1024 * 1024
  private val kitchen = "11111111-1111-1111-1111-111111111111"
  private val den = "22222222-2222-2222-2222-222222222222"
  private val newId = "0123456789abcdef"

  private fun msg(i: Int, at: Long = now, from: String = kitchen, outgoing: Boolean = false, seen: Boolean = false) =
      CallMessage("%016x".format(i), from, "Kitchen", at, 48_000, 1_000_000, seen = seen, outgoing = outgoing)

  @Test fun idsAreSixteenLowercaseHex() {
    assertTrue(CallMessageList.isId("0123456789abcdef"))
    assertFalse(CallMessageList.isId(null))
    assertFalse(CallMessageList.isId("0123456789ABCDEF"))
    assertFalse(CallMessageList.isId("0123456789abcde"))
    assertFalse(CallMessageList.isId("../../0123456789"))
  }

  @Test fun durationAllowsTheRecordersSmallOvershoot() {
    val limit = CallMessageLimits.MAX_DURATION_MS + 2_000
    assertNull(CallMessageList.canAccept(emptyList(), newId, 10, limit, free, true))
    assertEquals(CallMessageRefusal.TOO_LONG, CallMessageList.canAccept(emptyList(), newId, 10, limit + 1, free, true))
    assertEquals(CallMessageRefusal.TOO_LONG, CallMessageList.canAccept(emptyList(), newId, 10, -1, free, true))
    assertNull(CallMessageList.canAccept(emptyList(), newId, CallMessageLimits.MAX_BYTES, 1_000, free, true))
  }

  @Test fun offWinsOverEveryOtherReasonAndDuplicateOverSize() {
    val existing = listOf(msg(1))
    assertEquals(CallMessageRefusal.MESSAGES_OFF, CallMessageList.canAccept(existing, existing[0].id, -1, -1, 0, on = false))
    assertEquals(CallMessageRefusal.DUPLICATE, CallMessageList.canAccept(existing, existing[0].id, -1, -1, 0, on = true))
  }

  @Test fun sentCopiesNeverFillTheInbox() {
    val sent = (1..CallMessageLimits.MAX_STORED).map { msg(it, outgoing = true) }
    assertNull(CallMessageList.canAccept(sent, newId, 10, 1_000, free, true, kitchen))
    val mixed = (1 until CallMessageLimits.MAX_STORED).map { msg(it, from = "%08d-0000-0000-0000-000000000000".format(it)) }
    assertNull(CallMessageList.canAccept(mixed, newId, 10, 1_000, free, true, kitchen))
    assertEquals(CallMessageRefusal.FULL, CallMessageList.canAccept(mixed + msg(500, from = den), newId, 10, 1_000, free, true, kitchen))
  }

  @Test fun perSenderBudgetIgnoresThatSendersSentCopies() {
    val nine = (1 until CallMessageLimits.MAX_PER_SENDER).map { msg(it, from = kitchen) }
    val ownCopies = (100..105).map { msg(it, from = kitchen, outgoing = true) }
    assertNull(CallMessageList.canAccept(nine + ownCopies, newId, 10, 1_000, free, true, kitchen))
  }

  @Test fun freeSpaceFloorIsInclusive() {
    val floor = CallMessageLimits.MIN_FREE_BYTES
    assertNull(CallMessageList.canAccept(emptyList(), newId, 1_000, 1_000, floor + 1_000, true))
    assertEquals(CallMessageRefusal.NO_SPACE, CallMessageList.canAccept(emptyList(), newId, 1_000, 1_000, floor + 999, true))
  }

  @Test fun pruneExpiresAfterThirtyDaysKeepsNewestAndCapsSentCopiesSeparately() {
    val edge = msg(1, at = now - CallMessageLimits.KEEP_MS)
    val old = msg(2, at = now - CallMessageLimits.KEEP_MS - 1)
    assertEquals(listOf(edge), CallMessageList.prune(listOf(old, edge), now))
    val sent = (1..CallMessageLimits.MAX_SENT_KEPT + 2).map { msg(100 + it, at = now - it * 1_000L, outgoing = true) }
    val received = (1..3).map { msg(200 + it, at = now - it * 500L) }
    val pruned = CallMessageList.prune(sent + received, now)
    assertEquals(CallMessageLimits.MAX_SENT_KEPT, pruned.count { it.outgoing })
    assertEquals(3, pruned.count { !it.outgoing })
    assertEquals(pruned.sortedByDescending { it.atMs }, pruned)
    assertFalse("the oldest sent copies go first", pruned.any { it.id == sent.last().id })
  }

  @Test fun pruneCapsReceivedMessagesAtTheStoreLimit() {
    val many = (1..CallMessageLimits.MAX_STORED + 3).map { msg(it, at = now - it * day / 100) }
    val pruned = CallMessageList.prune(many, now)
    assertEquals(CallMessageLimits.MAX_STORED, pruned.size)
    assertEquals(many.take(CallMessageLimits.MAX_STORED), pruned)
  }

  @Test fun addReplacesASameIdMessageAndPutsTheNewestFirst() {
    val a = msg(1, at = now - 10)
    val replacement = a.copy(fromName = "Den", atMs = now)
    assertEquals(listOf(replacement), CallMessageList.add(listOf(a), replacement, now))
  }

  @Test fun unseenAndReceivedIgnoreSentCopies() {
    val list = listOf(msg(1), msg(2, seen = true), msg(3, outgoing = true))
    assertEquals(1, CallMessageList.unseen(list))
    assertEquals(listOf(msg(1), msg(2, seen = true)), CallMessageList.received(list))
    assertEquals(0, CallMessageList.unseen(CallMessageList.markSeen(list, msg(1).id)))
    assertEquals(list, CallMessageList.markSeen(list, "ffffffffffffffff"))
  }

  @Test fun codecRoundTripsOutgoingAndCallIdAndOutgoingIsAlwaysSeen() {
    val callId = "abcdefabcdefabcd"
    val list = listOf(msg(1).copy(audioOnly = true, callId = callId), msg(2, outgoing = true, seen = true))
    assertEquals(list, CallMessageList.decode(CallMessageList.encode(list)))
    val raw = JSONArray(CallMessageList.encode(listOf(msg(3, outgoing = true, seen = true))))
    raw.getJSONObject(0).put("s", false)
    assertTrue(CallMessageList.decode(raw.toString()).single().seen)
    assertFalse(JSONArray(CallMessageList.encode(listOf(msg(4)))).getJSONObject(0).has("o"))
  }

  @Test fun decodeDropsMalformedEntriesAndDuplicates() {
    assertEquals(emptyList<CallMessage>(), CallMessageList.decode(null))
    assertEquals(emptyList<CallMessage>(), CallMessageList.decode("{oops"))
    val good = JSONArray(CallMessageList.encode(listOf(msg(5)))).getJSONObject(0)
    fun with(key: String, value: Any?) = JSONObject(good.toString()).apply { if (value == null) remove(key) else put(key, value) }
    val arr = JSONArray().put(good).put(JSONObject(good.toString()))
        .put("not an object")
        .put(with("i", "XYZ")).put(with("f", "not-a-device")).put(with("n", ""))
        .put(with("t", 0)).put(with("t", "x")).put(with("ms", -1)).put(with("z", null))
    val decoded = CallMessageList.decode(arr.toString())
    assertEquals(listOf(msg(5)), decoded)
    assertNull(CallMessageList.decode(JSONArray().put(with("ci", "nope")).toString()).single().callId)
  }

  @Test fun refusalCodesRoundTrip() {
    for (r in CallMessageRefusal.entries) assertEquals(r, CallMessageRefusal.fromCode(r.code))
    assertNull(CallMessageRefusal.fromCode(null))
    assertNull(CallMessageRefusal.fromCode("FULL"))
  }
}
