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
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNowPlayingTest {
  private val queue = ytQueueOf(2)
  private val playing = YtEngineState(queue = queue, status = YtPlayerStatus.PLAYING, wantsPlay = true,
      positionMs = 5_000, durationMs = 180_000, positionAtElapsedMs = 900,
      playerVideoId = queue.current!!.track.videoId, foreground = true)

  @Test fun mapsQueueMetadataAndPlayerTimes() {
    val np = YtNowPlaying.map(playing)!!
    assertEquals(PlaybackState.PLAYING, np.state)
    assertEquals("Song 1", np.title)
    assertEquals("Artist 1", np.artist)
    assertEquals("https://i.ytimg.com/vi/1/hq.jpg", np.artUrl)
    assertEquals(180_000, np.durationMs)
    assertEquals(5_000, np.positionMs)
    assertEquals(900, np.positionUpdatedElapsedMs)
    assertEquals(SOURCE_YOUTUBE, np.source)
    assertEquals(queue.current!!.track.videoId, np.mediaId)
    assertTrue(np.canSeek)
    assertTrue(np.active)
  }

  @Test fun bufferingWhileWanted_isPlaying_cuedIsPaused() {
    assertEquals(PlaybackState.PLAYING, YtNowPlaying.map(playing.copy(status = YtPlayerStatus.BUFFERING))!!.state)
    assertEquals(PlaybackState.PAUSED, YtNowPlaying.map(playing.copy(status = YtPlayerStatus.CUED, wantsPlay = false))!!.state)
    assertEquals(PlaybackState.PAUSED, YtNowPlaying.map(playing.copy(status = YtPlayerStatus.NOT_READY, wantsPlay = false))!!.state)
  }

  @Test fun nothingToShow_whenEmptyOrHalted() {
    assertNull(YtNowPlaying.map(YtEngineState()))
    assertNull(YtNowPlaying.map(playing.copy(halt = YtHalt.EndOfQueue)))
    assertNull(YtNowPlaying.map(playing.copy(status = YtPlayerStatus.ENDED)))
  }

  @Test fun unknownItem_hidesProgressAndSeek() {
    val ad = YtNowPlaying.map(playing.copy(playerVideoId = "zzzzzzzzzzz", durationMs = 15_000))!!
    assertEquals("Song 1", ad.title)
    assertEquals(200_000, ad.durationMs) // the track's own Data API length, not the ad's
    assertEquals(0, ad.positionMs)
    assertFalse(ad.canSeek)
  }
}
