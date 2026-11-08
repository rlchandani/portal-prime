/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtQueueShuffleTest {
  @Test fun shuffle_keepsCurrentFirst_andAllEntries() {
    val q = ytQueueOf(10, start = 4)
    val s = q.shuffled(seed = 42L)
    assertTrue(s.isShuffled)
    assertEquals(0, s.currentIndex)
    assertEquals(q.current?.id, s.current?.id)
    assertEquals(q.entries.map { it.id }.toSet(), s.entries.map { it.id }.toSet())
  }

  @Test fun shuffle_isStableForSameSeed_andIdempotent() {
    val q = ytQueueOf(10, start = 4)
    assertEquals(q.shuffled(7L).entries, q.shuffled(7L).entries)
    val s = q.shuffled(7L)
    assertSame(s, s.shuffled(99L))
  }

  @Test fun unshuffle_restoresOriginalOrder_andCurrentFollows() {
    val q = ytQueueOf(6, start = 2)
    val s = q.shuffled(3L)
    val moved = s.next()!!.next()!!
    val u = moved.unshuffled()
    assertFalse(u.isShuffled)
    assertEquals(q.entries.map { it.id }, u.entries.map { it.id })
    assertEquals(moved.current?.id, u.current?.id)
    assertSame(q, q.unshuffled())
  }

  @Test fun unshuffle_keepsEntriesAddedWhileShuffled_andDropsRemoved() {
    val q = ytQueueOf(4, start = 0)
    val s = q.shuffled(5L).addToEnd(ytTrack(9)).playNext(ytTrack(8))
    val removedId = q.entries[3].id
    val u = s.remove(removedId).unshuffled()
    assertEquals(listOf("Song 1", "Song 8", "Song 2", "Song 3", "Song 9"),
        u.entries.map { it.track.title })
  }

  @Test fun unshuffle_keepsUnplayableMarks() {
    val q = ytQueueOf(3)
    val id = q.entries[2].id
    val u = q.shuffled(1L).markUnplayable(id, "x").unshuffled()
    assertFalse(u.entries.first { it.id == id }.playable)
  }

  @Test fun playNow_resetsShuffle_clearResetsShuffle() {
    val s = ytQueueOf(4).shuffled(2L)
    assertFalse(s.playNow(listOf(ytTrack(1)), 0, "").isShuffled)
    assertFalse(s.clear().isShuffled)
  }
}
