/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Sender-side per-room results: immutable transitions only. */
class DinnerBookTest {
  private val dinner = DinnerMessage.preset("dinner")!!
  private fun send(id: String = "s1", rows: List<DinnerRow> = listOf(DinnerRow("la", "Living Area", DinnerStatus.SENDING))) =
      DinnerSend(id, dinner, "Family Area", false, 100L, rows)

  @Test fun acksResolveRowsAndUnknownAckersAreAdded() {
    val start = DinnerBook().start(send())
    val acked = start.ack("s1", "la", "Living Area", DinnerStatus.SPOKEN)
    assertEquals(DinnerStatus.SENDING, start.find("s1")!!.rows.single().status) // original untouched
    assertEquals(DinnerStatus.SPOKEN, acked.find("s1")!!.rows.single().status)
    assertFalse(acked.find("s1")!!.pending)
    val extra = acked.ack("s1", "den", "Den", DinnerStatus.QUIET)
    assertEquals(listOf("la", "den"), extra.find("s1")!!.rows.map { it.roomId })
    assertSame(extra, extra.ack("missing", "la", "Living Area", DinnerStatus.SPOKEN))
  }

  @Test fun timeoutsOnlyTouchRowsStillSendingAndLateAcksStillCount() {
    val book = DinnerBook().start(send(rows = listOf(
        DinnerRow("la", "Living Area", DinnerStatus.SENDING), DinnerRow("den", "Den", DinnerStatus.SENDING))))
        .ack("s1", "la", "Living Area", DinnerStatus.SPOKEN)
        .timeout("s1")
    val rows = book.find("s1")!!.rows
    assertEquals(DinnerStatus.SPOKEN, rows[0].status)
    assertEquals(DinnerStatus.NO_ANSWER, rows[1].status)
    assertEquals("Sent “Dinner’s ready”", DinnerCopy.resultTitle(book.find("s1")!!))
    val late = book.ack("s1", "den", "Den", DinnerStatus.CHIMED)
    assertEquals(DinnerStatus.CHIMED, late.find("s1")!!.rows[1].status)
  }

  @Test fun repliesRetriesAndTitles() {
    val book = DinnerBook().start(send()).ack("s1", "la", "Living Area", DinnerStatus.SPOKEN)
        .reply("s1", "la", DinnerReply.ON_MY_WAY)
    assertEquals(DinnerReply.ON_MY_WAY, book.find("s1")!!.rows.single().reply)
    assertEquals("Sending “Dinner’s ready”", DinnerCopy.resultTitle(DinnerBook().start(send()).find("s1")!!))
    val failed = DinnerBook().start(send()).timeout("s1")
    assertEquals("Couldn’t reach Living Area", DinnerCopy.resultTitle(failed.find("s1")!!))
    assertTrue(failed.find("s1")!!.allFailed)
    val retried = failed.retrying("s1", "la")
    assertEquals(DinnerStatus.SENDING, retried.find("s1")!!.rows.single().status)
    // A reply for a room that never had a row is ignored.
    assertSame(book, book.reply("s1", "ghost", DinnerReply.COMING))
  }

  @Test fun keepsOnlyRecentSends() {
    var book = DinnerBook()
    repeat(DinnerBook.CAPACITY + 3) { book = book.start(send(id = "s$it")) }
    assertEquals(DinnerBook.CAPACITY, book.sends.size)
    assertNull(book.find("s0"))
    assertNotNull(book.find("s${DinnerBook.CAPACITY + 2}"))
  }
}
