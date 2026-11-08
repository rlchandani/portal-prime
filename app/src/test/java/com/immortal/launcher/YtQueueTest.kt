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

internal fun ytTrack(n: Int) = YtTrack(
    videoId = "vid%08d".format(n), title = "Song $n", artist = "Artist $n",
    thumbnailUrl = "https://i.ytimg.com/vi/$n/hq.jpg", durationMs = 200_000L)

internal fun ytQueueOf(count: Int, start: Int = 0, label: String = "Playlist · Road trip") =
    YtQueue.EMPTY.playNow((1..count).map(::ytTrack), start, label)

private fun YtQueue.titles() = entries.map { it.track.title }

class YtQueueTest {
  @Test fun empty_hasNoCurrent_andNavigationIsNull() {
    val q = YtQueue.EMPTY
    assertNull(q.current)
    assertEquals(-1, q.currentIndex)
    assertNull(q.next())
    assertNull(q.onEnded())
    assertSame(q, q.previous(0))
    assertTrue(q.isEmpty)
  }

  @Test fun playNow_setsItemsCurrentAndLabel() {
    val q = ytQueueOf(3, start = 1)
    assertEquals(listOf("Song 1", "Song 2", "Song 3"), q.titles())
    assertEquals("Song 2", q.current?.track?.title)
    assertEquals("Playlist · Road trip", q.sourceLabel)
    assertEquals(3, q.entries.map { it.id }.toSet().size)
  }

  @Test fun playNow_clampsStartIndex_andEmptyListClears() {
    assertEquals(2, ytQueueOf(3, start = 9).currentIndex)
    assertEquals(0, ytQueueOf(3, start = -4).currentIndex)
    val cleared = ytQueueOf(3).withRepeat(YtRepeat.ALL).playNow(emptyList(), 0, "x")
    assertTrue(cleared.isEmpty)
    assertEquals(YtRepeat.ALL, cleared.repeat)
  }

  @Test fun playNow_dropsInvalidVideoIds() {
    val bad = ytTrack(1).copy(videoId = "not valid!")
    val q = YtQueue.EMPTY.playNow(listOf(bad, ytTrack(2)), 0, "")
    assertEquals(listOf("Song 2"), q.titles())
  }

  @Test fun playNext_insertsAfterCurrent_andOnEmptyBecomesCurrent() {
    val q = ytQueueOf(3, start = 0).playNext(ytTrack(9))
    assertEquals(listOf("Song 1", "Song 9", "Song 2", "Song 3"), q.titles())
    assertEquals("Song 1", q.current?.track?.title)
    val fromEmpty = YtQueue.EMPTY.playNext(ytTrack(9))
    assertEquals("Song 9", fromEmpty.current?.track?.title)
  }

  @Test fun addToEnd_appends_andOnEmptyBecomesCurrent() {
    val q = ytQueueOf(2).addToEnd(ytTrack(9))
    assertEquals(listOf("Song 1", "Song 2", "Song 9"), q.titles())
    assertEquals(0, q.currentIndex)
    assertEquals(0, YtQueue.EMPTY.addToEnd(ytTrack(9)).currentIndex)
  }

  @Test fun remove_beforeCurrent_keepsSameCurrentEntry() {
    val q = ytQueueOf(4, start = 2)
    val r = q.remove(q.entries[0].id)
    assertEquals("Song 3", r.current?.track?.title)
    assertEquals(1, r.currentIndex)
  }

  @Test fun remove_current_followingEntryBecomesCurrent() {
    val q = ytQueueOf(3, start = 1)
    val r = q.remove(q.entries[1].id)
    assertEquals("Song 3", r.current?.track?.title)
  }

  @Test fun remove_currentAtEnd_fallsBackToNewLast_andLastEntryEmpties() {
    val q = ytQueueOf(3, start = 2)
    assertEquals("Song 2", q.remove(q.entries[2].id).current?.track?.title)
    val single = ytQueueOf(1)
    val emptied = single.remove(single.entries[0].id)
    assertTrue(emptied.isEmpty)
    assertEquals(-1, emptied.currentIndex)
  }

  @Test fun remove_unknownId_isNoOp() {
    val q = ytQueueOf(3)
    assertSame(q, q.remove(9_999L))
  }

  @Test fun move_currentFollowsItsEntry() {
    val q = ytQueueOf(4, start = 1) // current Song 2
    val moved = q.move(1, 3)
    assertEquals(listOf("Song 1", "Song 3", "Song 4", "Song 2"), moved.titles())
    assertEquals(3, moved.currentIndex)
    val aroundCurrent = q.move(3, 0) // Song 4 to front; current shifts right
    assertEquals(listOf("Song 4", "Song 1", "Song 2", "Song 3"), aroundCurrent.titles())
    assertEquals("Song 2", aroundCurrent.current?.track?.title)
    val acrossCurrent = q.move(0, 2) // Song 1 past current; current shifts left
    assertEquals("Song 2", acrossCurrent.current?.track?.title)
    assertEquals(0, acrossCurrent.currentIndex)
  }

  @Test fun move_outOfRangeOrSame_isNoOp() {
    val q = ytQueueOf(3)
    assertSame(q, q.move(0, 0))
    assertSame(q, q.move(-1, 2))
    assertSame(q, q.move(0, 3))
  }

  @Test fun next_advances_andStopsAtEndWhenRepeatOff() {
    val q = ytQueueOf(2)
    assertEquals(1, q.next()?.currentIndex)
    assertNull(q.next()!!.next())
  }

  @Test fun next_wrapsWhenRepeatAll_andRepeatOneManualNextAlsoWraps() {
    val last = ytQueueOf(3, start = 2)
    assertEquals(0, last.withRepeat(YtRepeat.ALL).next()?.currentIndex)
    assertEquals(0, last.withRepeat(YtRepeat.ONE).next()?.currentIndex)
  }

  @Test fun onEnded_repeatOneStaysOnSameEntry() {
    val q = ytQueueOf(3, start = 1).withRepeat(YtRepeat.ONE)
    assertEquals(q.current?.id, q.onEnded()?.current?.id)
    assertNull(ytQueueOf(2, start = 1).onEnded())
  }

  @Test fun next_skipsUnplayableEntries() {
    val q = ytQueueOf(3)
    val marked = q.markUnplayable(q.entries[1].id, "blocked")
    assertEquals("Song 3", marked.next()?.current?.track?.title)
    val allBad = marked.markUnplayable(q.entries[2].id, "x").markUnplayable(q.entries[0].id, "x")
    assertNull(allBad.withRepeat(YtRepeat.ALL).next())
  }

  @Test fun previous_restartsWhenPastThreeSeconds() {
    val q = ytQueueOf(3, start = 2)
    assertSame(q, q.previous(3_001))
    assertEquals(1, q.previous(3_000).currentIndex)
  }

  @Test fun previous_atStart_restartsUnlessRepeating() {
    val q = ytQueueOf(3, start = 0)
    assertSame(q, q.previous(0))
    assertEquals(2, q.withRepeat(YtRepeat.ALL).previous(0).currentIndex)
  }

  @Test fun select_jumpsToEntry_andClearsItsUnplayableMark() {
    val q = ytQueueOf(3)
    val id = q.entries[2].id
    val s = q.markUnplayable(id, "blocked").select(id)
    assertEquals(2, s.currentIndex)
    assertTrue(s.current!!.playable)
    assertSame(q, q.select(12_345L))
  }

  @Test fun markUnplayable_recordsReason() {
    val q = ytQueueOf(2)
    val m = q.markUnplayable(q.entries[0].id, "Removed or private")
    assertFalse(m.entries[0].playable)
    assertEquals("Removed or private", m.entries[0].unplayableReason)
  }

  @Test fun clear_keepsRepeatAndKeepsIdsUnique() {
    val q = ytQueueOf(3).withRepeat(YtRepeat.ONE)
    val c = q.clear()
    assertTrue(c.isEmpty)
    assertEquals(YtRepeat.ONE, c.repeat)
    assertFalse(q.isShuffled || c.isShuffled)
    val after = c.addToEnd(ytTrack(1))
    assertFalse(q.entries.any { it.id == after.entries[0].id })
  }

  @Test fun operations_doNotMutateOriginal() {
    val q = ytQueueOf(3)
    val before = q.entries.toList()
    q.addToEnd(ytTrack(5)); q.move(0, 2); q.remove(q.entries[0].id); q.shuffled(1L)
    assertEquals(before, q.entries)
    assertEquals(0, q.currentIndex)
  }
}
