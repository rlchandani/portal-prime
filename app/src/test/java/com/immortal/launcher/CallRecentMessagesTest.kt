/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video messages in Recent, the missed-call ↔ message link, sent copies and the wire's call id. */
class CallRecentMessagesTest {
  private val now = 1_800_000_000_000L
  private val min = 60_000L
  private val rose = "rose-portal"
  private val walt = "walt-portal"
  private val call1 = "00000000000000a1"
  private val call2 = "00000000000000a2"

  private fun missed(id: String, from: String = rose, at: Long = now) =
      CallHistoryEntry(id, from, if (from == rose) "Rose" else "Walt", CallDirection.INCOMING, CallKind.CALL, CallEndReason.MISSED, at, 0L)

  private fun noAnswer(id: String, to: String = walt, at: Long = now) =
      CallHistoryEntry(id, to, "Walt", CallDirection.OUTGOING, CallKind.CALL, CallEndReason.NO_ANSWER, at, 0L, seen = true)

  private fun talked(id: String, at: Long) =
      CallHistoryEntry(id, rose, "Rose", CallDirection.OUTGOING, CallKind.CALL, CallEndReason.HUNG_UP, at, 24 * min, seen = true)

  private fun msg(n: Int, from: String = rose, at: Long = now + min, callId: String? = null, outgoing: Boolean = false,
                  seen: Boolean = outgoing, audio: Boolean = false) =
      CallMessage("%016x".format(n), from, if (from == rose) "Rose" else "Walt", at, 48_000, 1_000_000, audio, seen, outgoing, callId)

  @Test fun aMissedCallLinksToTheMessageThatNamesIt() {
    val e = missed(call1)
    val named = msg(1, at = now + 20 * min, callId = call1) // well outside the old 10-minute guess
    val other = msg(2, at = now + min, callId = call2)       // left after a different call
    assertSame(named, CallInbox.messageFor(e, listOf(other, named)))
  }

  @Test fun olderSendersStillPairByTime() {
    val e = missed(call1)
    assertEquals("%016x".format(3), CallInbox.messageFor(e, listOf(msg(3, at = now + 2 * min)))?.id)
    assertNull("too late", CallInbox.messageFor(e, listOf(msg(4, at = now + 11 * min))))
    assertNull("someone else", CallInbox.messageFor(e, listOf(msg(5, from = walt))))
    assertNull("a sent copy is never a miss's message", CallInbox.messageFor(e, listOf(msg(6, outgoing = true))))
    assertNull("not a miss", CallInbox.messageFor(talked(call2, now), listOf(msg(7))))
  }

  @Test fun recentShowsMessagesWithPlayAndAMissOnceWithItsMessage() {
    val history = listOf(missed(call1, at = now), talked(call2, now - 60 * min))
    val linked = msg(1, at = now + min, callId = call1)
    val alone = msg(2, from = walt, at = now - 30 * min)
    val sent = msg(3, from = walt, at = now - 90 * min, outgoing = true)
    val rows = CallRecent.items(history, listOf(linked, alone, sent), CallHistoryFilter.ALL)
    assertEquals(listOf("c-$call1", "m-${alone.id}", "c-$call2", "m-${sent.id}"), rows.map { it.key })
    assertSame(linked, (rows[0] as RecentItem.Call).message)
    assertNull((rows[2] as RecentItem.Call).message)
    assertEquals(listOf(true, true, false, true), rows.map { it.message != null })
  }

  @Test fun filters() {
    val history = listOf(missed(call1), talked(call2, now - 60 * min),
        CallHistoryEntry("00000000000000a3", rose, "Rose", CallDirection.INCOMING, CallKind.DROP_IN, CallEndReason.HUNG_UP, now - 5 * min, 3 * min))
    val messages = listOf(msg(1, callId = call1), msg(2, from = walt, at = now - 2 * min))
    assertEquals(listOf("c-$call1"), CallRecent.items(history, messages, CallHistoryFilter.MISSED).map { it.key })
    assertEquals(listOf("c-00000000000000a3"), CallRecent.items(history, messages, CallHistoryFilter.DROP_INS).map { it.key })
    assertEquals(listOf("c-$call1", "m-${"%016x".format(2)}"), CallRecent.items(history, messages, CallHistoryFilter.MESSAGES).map { it.key })
    assertTrue(CallHistory.filter(history, CallHistoryFilter.MESSAGES).isEmpty())
  }

  @Test fun anUnansweredCallShowsTheMessageThisPortalLeft() {
    val e = noAnswer(call1)
    val sent = msg(1, from = walt, outgoing = true, callId = call1)
    val rows = CallRecent.items(listOf(e), listOf(sent), CallHistoryFilter.ALL)
    assertEquals(1, rows.size)
    assertSame(sent, rows.single().message)
    assertEquals("4:12 PM · didn’t connect · you left a 0:48 video message", CallRecent.line(rows.single(), "4:12 PM"))
  }

  @Test fun oneMessageLinksToOneRowOnly() {
    val history = listOf(missed(call1, at = now), missed(call2, at = now - 2 * min))
    val m = msg(1, at = now + min) // no call id: pairs with the newest miss within the window only once
    val rows = CallRecent.items(history, listOf(m), CallHistoryFilter.ALL)
    assertEquals(1, rows.count { it.message != null })
    assertEquals(2, rows.size)
  }

  @Test fun rowWords() {
    assertEquals("2:14 PM · missed · left a 0:48 video message",
        CallRecent.line(RecentItem.Call(missed(call1), msg(1)), "2:14 PM"))
    assertEquals("9:03 AM · 0:48 audio message", CallRecent.line(RecentItem.Message(msg(2, audio = true)), "9:03 AM"))
    assertEquals("9:03 AM · you sent a 0:48 video message", CallRecent.line(RecentItem.Message(msg(3, outgoing = true)), "9:03 AM"))
  }

  @Test fun sentCopiesNeverCountAsNewOrFillTheInbox() {
    val sent = (1..CallMessageLimits.MAX_STORED).map { msg(it, outgoing = true) }
    assertEquals(0, CallMessageList.unseen(sent))
    assertNull(CallInbox.latestUnseen(sent))
    assertTrue(CallInbox.items(sent, emptyList()).isEmpty())
    assertNull("sent copies never make this Portal refuse a message",
        CallMessageList.canAccept(sent, "0123456789abcdef", 10, 1_000, Long.MAX_VALUE / 2, on = true, from = rose))
  }

  @Test fun pruneKeepsAFewSentCopiesAndEveryReceivedOne() {
    val sent = (1..CallMessageLimits.MAX_SENT_KEPT + 5).map { msg(it, outgoing = true, at = now - it * min) }
    val received = (100..105).map { msg(it, at = now - it * min) }
    val kept = CallMessageList.prune(sent + received, now)
    assertEquals(CallMessageLimits.MAX_SENT_KEPT, kept.count { it.outgoing })
    assertEquals(6, kept.count { !it.outgoing })
    assertTrue("newest sent copies stay", kept.filter { it.outgoing }.all { it.atMs >= now - CallMessageLimits.MAX_SENT_KEPT * min })
    assertEquals(kept.sortedByDescending { it.atMs }, kept)
  }

  @Test fun codecKeepsSentAndLinkAndRefusesABadLink() {
    val list = listOf(msg(1, outgoing = true, callId = call1), msg(2, callId = call2, seen = false), msg(3))
    assertEquals(list, CallMessageList.decode(CallMessageList.encode(list)))
    val raw = CallMessageList.encode(listOf(msg(4))).replace("}", ",\"ci\":\"../etc\",\"o\":false}")
    val back = CallMessageList.decode(raw).single()
    assertNull(back.callId)
    assertFalse(back.outgoing)
    // Older index files (no "o"/"ci") still load.
    assertEquals(msg(5), CallMessageList.decode(CallMessageList.encode(listOf(msg(5)))).single())
  }

  @Test fun theUploadNamesTheCallAndOldHeadersStillWork() {
    val sealed = CallMessageCrypto.sealedLength(1_000_000)
    val put = CallMessageWire.Put("0123456789abcdef", walt, "Kitchen", 48_000, sealed, CallMessageCrypto.chunkCount(1_000_000), false, call1)
    assertEquals(put, CallMessageWire.decode(CallMessageWire.encode(put)))
    val old = put.copy(callId = null)
    val oldJson = CallMessageWire.encode(old)
    assertFalse(oldJson.has("ci"))
    assertEquals(old, CallMessageWire.decode(oldJson))
    assertNull("a malformed call id only loses the link",
        CallMessageWire.decode(CallMessageWire.encode(put).put("ci", "not-a-call"))?.callId)
  }
}
