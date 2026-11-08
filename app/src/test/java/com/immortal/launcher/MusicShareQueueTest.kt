/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue rules for music shared from a phone: what's playing is never replaced or lost. */
class MusicShareQueueTest {
  private val from = "Rohit's phone"
  private val shared = YtTrack("sharedSong1", "Saltwater hours", "Marrow & Vale")
  private fun titles(q: YtQueue) = q.entries.map { it.track.title }
  /** Songs 1–4 with song 2 current. */
  private val busy = ytQueueOf(4, start = 1)

  @Test fun nothingPlaying_everyActionJustPlays_andKeepsUpNext() {
    MusicShareAction.entries.forEach { action ->
      val out = MusicShareQueue.apply(busy, playing = false, positionMs = 0, listOf(shared), action, from, "l")!!
      assertEquals(MusicShareAction.NOW, out.effective)
      assertEquals(listOf("Saltwater hours", "Song 3", "Song 4"), titles(out.queue))
      assertEquals(0, out.queue.currentIndex)
      assertNull(out.interrupted)
      assertTrue(out.restart)
    }
  }

  @Test fun emptyQueue_playsTheShare() {
    val out = MusicShareQueue.apply(YtQueue.EMPTY, playing = true, positionMs = 0, listOf(shared), MusicShareAction.END, from, "l")!!
    assertEquals(MusicShareAction.NOW, out.effective)
    assertEquals(listOf("Saltwater hours"), titles(out.queue))
  }

  @Test fun playNow_jumpsIn_andTheInterruptedSongResumesWhereItStopped() {
    val out = MusicShareQueue.apply(busy, playing = true, positionMs = 61_000, listOf(shared), MusicShareAction.NOW, from, "l")!!
    assertEquals(listOf("Saltwater hours", "Song 2", "Song 3", "Song 4"), titles(out.queue))
    assertEquals(0, out.queue.currentIndex)
    assertEquals(61_000L, out.queue.entries[1].resumeMs)
    assertEquals("Song 2", out.interrupted)
    assertEquals("“Song 2” carries on after this", MusicShareQueue.toast(out, from, "x"))
  }

  @Test fun playNow_thenTheSharedSongEnds_reloadsTheInterruptedSongAtItsPosition() {
    val playing = YtEngineState(queue = busy, status = YtPlayerStatus.PLAYING, wantsPlay = true, foreground = true)
    val out = MusicShareQueue.apply(busy, true, 61_000, listOf(shared), MusicShareAction.NOW, from, "l")!!
    val adopted = YtEngineReducer.reduce(playing, YtAction.ApplyShared(out.queue, restart = true))
    assertEquals(YtEffect.Load("sharedSong1", 0), adopted.effects.first())
    val ended = YtEngineReducer.reduce(adopted.state, YtPlayerEvent.StateChanged(YtPlayerStatus.ENDED))
    assertEquals(YtEffect.Load(ytTrack(2).videoId, 61_000), ended.effects.first())
    assertEquals(61_000, ended.state.positionMs)
    assertNull(ended.state.queue.current?.resumeMs)
  }

  @Test fun playNext_goesRightAfterTheCurrentSong_withoutReloadingIt() {
    val out = MusicShareQueue.apply(busy, true, 5_000, listOf(shared), MusicShareAction.NEXT, from, "l")!!
    assertEquals(listOf("Song 1", "Song 2", "Saltwater hours", "Song 3", "Song 4"), titles(out.queue))
    assertEquals(1, out.queue.currentIndex)
    assertEquals("Song 2", out.before)
    val playing = YtEngineState(queue = busy, status = YtPlayerStatus.PLAYING, wantsPlay = true, foreground = true)
    val step = YtEngineReducer.reduce(playing, YtAction.ApplyShared(out.queue, out.restart))
    assertEquals(listOf(YtEffect.Persist), step.effects)
    assertEquals("Rohit's phone added “Saltwater hours” · Up next", MusicShareQueue.toast(out, from, "“Saltwater hours”"))
  }

  @Test fun addToQueue_goesToTheEnd_andCountsWhatIsAhead() {
    val out = MusicShareQueue.apply(busy, true, 5_000, listOf(shared), MusicShareAction.END, from, "l")!!
    assertEquals("Saltwater hours", titles(out.queue).last())
    assertEquals(2, out.ahead)
    assertEquals("$from added 2 songs to the end of the queue", MusicShareQueue.toast(out, from, "2 songs"))
  }

  @Test fun shuffledQueue_keepsItsOriginalOrderInStep() {
    val shuffled = busy.shuffled(7)
    val next = MusicShareQueue.apply(shuffled, true, 0, listOf(shared), MusicShareAction.NEXT, from, "l")!!
    val restored = next.queue.unshuffled()
    assertEquals(listOf("Song 1", "Song 2", "Saltwater hours", "Song 3", "Song 4"), titles(restored))
    val end = MusicShareQueue.apply(shuffled, true, 0, listOf(shared), MusicShareAction.END, from, "l")!!
    assertEquals("Saltwater hours", titles(end.queue.unshuffled()).last())
  }

  @Test fun onlyTheFirstSharedRowCarriesTheSenderTag_andIdsNeverCollide() {
    val batch = (10..13).map(::ytTrack)
    val out = MusicShareQueue.apply(busy, true, 0, batch, MusicShareAction.NEXT, from, "l")!!
    val sharedRows = out.queue.entries.filter { it.id in out.sharedIds }
    assertEquals(listOf(from, null, null, null), sharedRows.map { it.sharedBy })
    assertEquals(out.queue.entries.size, out.queue.entries.map { it.id }.distinct().size)
    assertEquals(busy.nextId + 4, out.queue.nextId)
  }

  @Test fun invalidTracksOnly_returnsNull() {
    assertNull(MusicShareQueue.apply(busy, true, 0, listOf(YtTrack("bad", "x", "y")), MusicShareAction.NOW, from, "l"))
  }

  @Test fun prepare_capsAt200_dropsInvalid_andShufflesOnlyWhenAsked() {
    val many = (1..250).map(::ytTrack) + YtTrack("bad", "x", "y")
    val inOrder = MusicShareQueue.prepare(many, shuffle = false, seed = 1)
    assertEquals(MusicShareQueue.MAX_TRACKS, inOrder.size)
    assertEquals(ytTrack(1), inOrder.first())
    val shuffled = MusicShareQueue.prepare(many, shuffle = true, seed = 1)
    assertEquals(inOrder.toSet(), shuffled.toSet())
    assertNotEquals(inOrder, shuffled)
  }

  @Test fun placeholders_useYouTubeThumbnails_andGetFilledByThePlayer() {
    val rows = MusicShareQueue.placeholders(listOf(ytTrack(1).videoId), "Sunday slow")
    assertEquals("https://i.ytimg.com/vi/${ytTrack(1).videoId}/hqdefault.jpg", rows.single().thumbnailUrl)
    val queue = YtQueue.EMPTY.playNow(rows, 0, "l").let { q -> q.copy(entries = q.entries.map { it.copy(needsMeta = true) }) }
    val s = YtEngineState(queue = queue, status = YtPlayerStatus.PLAYING)
    val filled = YtEngineReducer.reduce(s, YtPlayerEvent.Progress(1_000, 200_000, ytTrack(1).videoId, 5, "Real title", "Real artist")).state
    assertEquals("Real title", filled.queue.current?.track?.title)
    assertEquals("Real artist", filled.queue.current?.track?.artist)
    val again = YtEngineReducer.reduce(filled, YtPlayerEvent.Progress(2_000, 200_000, ytTrack(1).videoId, 6, "Other", "")).state
    assertEquals("Real title", again.queue.current?.track?.title)
  }

  @Test fun labels() {
    assertEquals("“Saltwater hours”", MusicShareQueue.label(MusicShareKind.VIDEO, "Saltwater hours", 1))
    assertEquals("24 songs from Sunday slow", MusicShareQueue.label(MusicShareKind.PLAYLIST, "Sunday slow", 24))
    assertEquals("1 song", MusicShareQueue.label(MusicShareKind.PLAYLIST, "", 1))
  }
}
