/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Recipient store rules: limits, dedupe, 30-day pruning, codec, inbox merge and copy. */
class CallMessageListTest {
  private val now = 1_800_000_000_000L
  private val day = 24 * 60 * 60_000L
  private val free = 2L * 1024 * 1024 * 1024
  private fun msg(i: Int, at: Long = now, from: String = "11111111-1111-1111-1111-111111111111") =
      CallMessage("%016x".format(i), from, "Kitchen", at, 48_000, 1_000_000)

  @Test fun acceptsAnOrdinaryMessage() {
    assertNull(CallMessageList.canAccept(emptyList(), "0123456789abcdef", 25_000_000, 120_000, free, on = true))
  }

  @Test fun refusesForEveryLimit() {
    val id = "0123456789abcdef"
    assertEquals(CallMessageRefusal.MESSAGES_OFF, CallMessageList.canAccept(emptyList(), id, 1, 1_000, free, on = false))
    assertEquals(CallMessageRefusal.TOO_LARGE, CallMessageList.canAccept(emptyList(), id, CallMessageLimits.MAX_BYTES + 1, 1_000, free, true))
    assertEquals(CallMessageRefusal.TOO_LARGE, CallMessageList.canAccept(emptyList(), id, 0, 1_000, free, true))
    assertEquals(CallMessageRefusal.TOO_LONG, CallMessageList.canAccept(emptyList(), id, 10, 200_000, free, true))
    assertEquals(CallMessageRefusal.TOO_LONG, CallMessageList.canAccept(emptyList(), id, 10, 0, free, true))
    val full = (1..CallMessageLimits.MAX_STORED).map { msg(it) }
    assertEquals(CallMessageRefusal.FULL, CallMessageList.canAccept(full, id, 10, 1_000, free, true))
    assertEquals(CallMessageRefusal.NO_SPACE, CallMessageList.canAccept(emptyList(), id, 10_000_000, 1_000, CallMessageLimits.MIN_FREE_BYTES, true))
    val one = "11111111-1111-1111-1111-111111111111"
    val many = (1..CallMessageLimits.MAX_PER_SENDER).map { msg(it, from = one) }
    assertEquals(CallMessageRefusal.FULL, CallMessageList.canAccept(many, id, 10, 1_000, free, true, one))
    assertNull(CallMessageList.canAccept(many, id, 10, 1_000, free, true, "22222222-2222-2222-2222-222222222222"))
    assertEquals(CallMessageRefusal.DUPLICATE, CallMessageList.canAccept(listOf(msg(1)), "%016x".format(1), 10, 1_000, free, true))
  }

  @Test fun idsAreSixteenLowercaseHexOnly() {
    assertTrue(CallMessageList.isId("0123456789abcdef"))
    for (bad in listOf(null, "", "../0123456789ab", "0123456789ABCDEF", "0123456789abcde", "0123456789abcdef0", "0123456789abcde/"))
      assertFalse(CallMessageList.isId(bad))
  }

  @Test fun prunesAfterThirtyDaysNewestFirst() {
    val list = listOf(msg(1, now - 31 * day), msg(2, now - day), msg(3, now))
    assertEquals(listOf(msg(3, now), msg(2, now - day)), CallMessageList.prune(list, now))
  }

  @Test fun addReplacesTheSameIdAndMarksSeen() {
    val list = CallMessageList.add(listOf(msg(1)), msg(1).copy(fromName = "Den"), now)
    assertEquals(1, list.size)
    assertEquals("Den", list[0].fromName)
    assertEquals(1, CallMessageList.unseen(list))
    assertEquals(0, CallMessageList.unseen(CallMessageList.markSeen(list, list[0].id)))
  }

  @Test fun codecRoundTripsAndDropsJunk() {
    val list = listOf(msg(1), msg(2).copy(audioOnly = true, seen = true))
    assertEquals(list, CallMessageList.decode(CallMessageList.encode(list)))
    val junk = JSONArray(CallMessageList.encode(list)).apply { getJSONObject(0).put("i", "../x") }.toString()
    assertEquals(1, CallMessageList.decode(junk).size)
    assertTrue(CallMessageList.decode("not json").isEmpty())
    assertTrue(CallMessageList.decode(null).isEmpty())
  }

  @Test fun inboxShowsAMissAndItsMessageOnceAsTheMessage() {
    val from = "11111111-1111-1111-1111-111111111111"
    val miss = CallHistoryEntry("aaaaaaaaaaaaaaaa", from, "Kitchen", CallDirection.INCOMING, CallKind.CALL, CallEndReason.MISSED, now - 60_000, 0)
    val lonely = miss.copy(id = "bbbbbbbbbbbbbbbb", atMs = now - day)
    val answered = miss.copy(id = "cccccccccccccccc", reason = CallEndReason.HUNG_UP, durationMs = 5_000)
    val m = msg(1, now, from)
    val items = CallInbox.items(listOf(m), listOf(miss, lonely, answered))
    assertEquals(listOf("m-${m.id}", "c-${lonely.id}"), items.map { it.key })
    assertEquals(m, CallInbox.latestUnseen(listOf(m, msg(2, now - 5).copy(seen = true))))
    assertNull(CallInbox.latestUnseen(listOf(m.copy(seen = true))))
  }

  @Test fun copyReadsLikeTheMock() {
    val m = msg(1)
    assertEquals("Video message · 0:48", CallMessageCopy.kind(m))
    assertEquals("2:14 PM · left a 0:48 video message", CallMessageCopy.line(m, "2:14 PM"))
    assertEquals("Audio message · 0:48", CallMessageCopy.kind(m.copy(audioOnly = true)))
    assertEquals("Stop · 0:42 of 2:00", CallMessageCopy.stopLabel(42_000))
    assertEquals("Only you have seen this", CallMessageCopy.sub(ComposePhase.REVIEW, null))
    assertEquals("They’ve turned video messages off on that Portal", CallMessageCopy.failure("messages_off"))
    assertEquals("Couldn’t reach that Portal. Is it on the home Wi-Fi?", CallMessageCopy.failure("unreachable"))
    assertEquals("It didn’t send. Your message is still here", CallMessageCopy.failure("http_500"))
    assertEquals("Recording · Camera on · Mic on", CallMessageCopy.liveBar(ComposePhase.RECORDING, true))
    assertEquals("Camera off · Mic off", CallMessageCopy.liveBar(ComposePhase.READY, false))
    assertEquals("None right now", CallMessageCopy.storedLine(0, 0))
    assertEquals("2 messages · 24.0 MB", CallMessageCopy.storedLine(2, 24L * 1024 * 1024))
    assertEquals("1 message · 1 KB", CallMessageCopy.storedLine(1, 10))
    assertEquals("That Portal is taking another message. Try again in a moment", CallMessageCopy.failure("busy"))
  }
}
