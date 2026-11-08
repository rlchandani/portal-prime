/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicV3PlayerModelTest {
  private val one = YtTrack("aaaaaaaaaaa", "Low tide, early", "Marrow & Vale", durationMs = 252_000)
  private val two = YtTrack("bbbbbbbbbbb", "Embers", "The Quiet Assembly")
  private val queue = YtQueue().playNow(listOf(one, two), 0, "Saturday morning")
  private val playing = YtEngineState(queue = queue, status = YtPlayerStatus.PLAYING, wantsPlay = true,
      foreground = true, playerVideoId = one.videoId)

  @After fun reset() { YtVideoScreenState.playingOnScreen = false }

  @Test fun emptyQueueDisablesTransport() {
    val m = MusicV3NowPlaying.from(YtEngineState(), "Family Area")
    assertFalse(m.hasTrack)
    assertFalse(m.transportEnabled)
    assertFalse(m.showProgress)
    assertEquals("", m.statusLine)
  }

  @Test fun playingShowsRoomAndPause() {
    val m = MusicV3NowPlaying.from(playing, "Family Area")
    assertTrue(m.playing)
    assertTrue(m.transportEnabled)
    assertEquals("Playing on Family Area", m.statusLine)
  }

  @Test fun pausedSaysPaused() {
    val m = MusicV3NowPlaying.from(playing.copy(status = YtPlayerStatus.PAUSED, wantsPlay = false), "")
    assertFalse(m.playing)
    assertEquals("Paused on this Portal", m.statusLine)
  }

  @Test fun anAdDisablesTransportButNotVolume() {
    val m = MusicV3NowPlaying.from(playing.copy(playerVideoId = "zzzzzzzzzzz"), "Family Area")
    assertTrue(m.isAd)
    assertFalse(m.transportEnabled)
    assertFalse(m.showProgress)
    assertFalse(m.showBigPlay)
  }

  @Test fun blockedAutoplayShowsOneBigPlay() {
    val m = MusicV3NowPlaying.from(playing.copy(status = YtPlayerStatus.CUED, autoplayBlocked = true), "x")
    assertTrue(m.showBigPlay)
    assertFalse(m.playing)
  }

  @Test fun haltsBecomePlainMessages() {
    assertEquals("That's the end of the queue",
        MusicV3NowPlaying.from(playing.copy(halt = YtHalt.EndOfQueue), "x").halt?.title)
    assertEquals("3 songs in a row couldn't play here", MusicV3NowPlaying.haltMessage(YtHalt.TooManyFailures(3)).title)
    assertEquals("YouTube's player stopped", MusicV3NowPlaying.haltMessage(YtHalt.PlayerError(153)).title)
    assertEquals("YouTube's player didn't load", MusicV3NowPlaying.haltMessage(YtHalt.PlayerUnavailable).title)
    assertNull(MusicV3NowPlaying.from(playing, "x").halt)
  }

  @Test fun positionExtrapolatesOnlyWhilePlaying() {
    val s = playing.copy(positionMs = 10_000, positionAtElapsedMs = 1_000, durationMs = 20_000)
    assertEquals(13_000, MusicV3NowPlaying.livePositionMs(s, 4_000))
    assertEquals(20_000, MusicV3NowPlaying.livePositionMs(s, 99_000))
    assertEquals(10_000, MusicV3NowPlaying.livePositionMs(s.copy(status = YtPlayerStatus.PAUSED), 4_000))
    assertEquals(10_000, MusicV3NowPlaying.livePositionMs(s.copy(positionAtElapsedMs = 0), 4_000))
  }

  @Test fun durationFallsBackToTheDataApi() {
    assertEquals(252_000, MusicV3NowPlaying.durationMs(playing))
    assertEquals(9_000, MusicV3NowPlaying.durationMs(playing.copy(durationMs = 9_000)))
  }

  @Test fun queueViewSplitsCurrentAndUpNext() {
    val v = MusicV3QueueView.from(queue)
    assertEquals(one, v.current?.track)
    assertEquals(listOf(1), v.upNext.map { it.first })
    assertEquals("Saturday morning", v.sourceLabel)
    assertTrue(MusicV3QueueView.from(YtQueue.EMPTY).upNext.isEmpty())
  }

  @Test fun onlyAPlayingForegroundVideoHoldsTheScreen() {
    assertTrue(YtVideoScreenState.holds(playing))
    assertFalse(YtVideoScreenState.holds(playing.copy(status = YtPlayerStatus.PAUSED)))
    assertFalse(YtVideoScreenState.holds(playing.copy(foreground = false)))
    assertFalse(YtVideoScreenState.ownsScreen())
    YtVideoScreenState.playingOnScreen = true
    assertTrue(YtVideoScreenState.ownsScreen())
  }

  @Test fun homeChipSaysPausedOffThePlayer() {
    assertEquals("Paused", musicV3ChipStatus(playing.copy(status = YtPlayerStatus.PAUSED)))
    assertEquals("Playing", musicV3ChipStatus(playing))
  }

  @Test fun pausedNoteShowsOncePerProcess() {
    MusicV3Session.leftWhilePlaying()
    val first = MusicV3Session.showPausedNote.value
    MusicV3Session.pausedNoteShown()
    MusicV3Session.leftWhilePlaying()
    assertTrue(first)
    assertFalse(MusicV3Session.showPausedNote.value)
  }
}
