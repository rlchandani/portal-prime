/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MusicTransportsTest {
  private class Fake : MusicTransport {
    override fun play() = Unit
    override fun pause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
  }

  private val youtube = Fake()
  private val embedded = Fake()
  private val session = Fake()

  private fun state(source: String) = NowPlayingState(PlaybackState.PLAYING, title = "T", source = source)

  @After fun reset() {
    MusicTransports.youtube = null
    MusicTransports.embedded = null
    MusicTransports.mediaSession = null
  }

  @Test fun routesEachSourceToItsOwnTransport() {
    MusicTransports.youtube = youtube
    MusicTransports.embedded = embedded
    MusicTransports.mediaSession = { session }
    assertSame(youtube, MusicTransports.forState(state(SOURCE_YOUTUBE)))
    assertSame(embedded, MusicTransports.forState(state(SOURCE_EMBEDDED_YT_MUSIC)))
    assertSame(session, MusicTransports.forState(state("org.chromium.chrome")))
    assertNull(MusicTransports.forState(null))
  }

  @Test fun youtubeSource_withoutRegisteredEngine_isNotHandedToMediaSessions() {
    MusicTransports.mediaSession = { session }
    assertNull(MusicTransports.forState(state(SOURCE_YOUTUBE)))
    assertNull(MediaSessionMusicTransport.forState(state(SOURCE_YOUTUBE)))
  }
}
