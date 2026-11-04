/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class MediaSessionBufferingTest {
  private val own = "com.synthetic.relay"
  private data class Session(val controller: MediaController, val playback: SessionState, val token: MediaSession.Token)

  private fun session(pkg: String, state: Int): Session {
    val controller = Mockito.mock(MediaController::class.java)
    val playback = Mockito.mock(SessionState::class.java)
    val token = Mockito.mock(MediaSession.Token::class.java)
    `when`(controller.packageName).thenReturn(pkg)
    `when`(controller.playbackState).thenReturn(playback)
    `when`(controller.sessionToken).thenReturn(token)
    `when`(playback.state).thenReturn(state)
    return Session(controller, playback, token)
  }

  private fun previouslyPlaying(pkg: String = "com.synthetic.player"): Session {
    val s = session(pkg, SessionState.STATE_PLAYING)
    assertSame(s.controller, MediaSessionReader.selectSession(listOf(s.controller), own, null)!!.controller)
    `when`(s.playback.state).thenReturn(SessionState.STATE_BUFFERING)
    return s
  }

  @Test fun previouslyPlayingPlayerRetainsTrackThroughBufferingAndPauseThenStops() {
    val s = previouslyPlaying()
    val md = Mockito.mock(MediaMetadata::class.java)
    `when`(s.controller.metadata).thenReturn(md)
    `when`(md.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("Synthetic track")
    val buffered = MediaSessionReader.selectSession(listOf(s.controller), own, s.token)
    assertNotNull(buffered)
    assertSame(s.controller, buffered!!.controller)
    val snapshot = MediaSessionReader.snapshotFrom(buffered)
    assertEquals(PlaybackState.PLAYING, snapshot.state)
    assertEquals("Synthetic track", snapshot.title)
    assertTrue(snapshot.active)
    `when`(s.playback.state).thenReturn(SessionState.STATE_PAUSED)
    assertEquals(PlaybackState.PAUSED,
        MediaSessionReader.snapshotFrom(MediaSessionReader.selectSession(listOf(s.controller), own, s.token)!!).state)
    `when`(s.playback.state).thenReturn(SessionState.STATE_STOPPED)
    assertNull(MediaSessionReader.selectSession(listOf(s.controller), own, s.token))
  }

  @Test fun previouslyPlayingRelayRetainsBufferingFallback() {
    val s = previouslyPlaying(own)
    assertSame(s.controller, MediaSessionReader.selectSession(listOf(s.controller), own, s.token)?.controller)
  }

  @Test fun neverPlayedBufferingSessionCannotBeSelected() {
    val s = session("com.synthetic.unseen", SessionState.STATE_BUFFERING)
    assertNull(MediaSessionReader.selectSession(listOf(s.controller), own, null))
  }

  @Test fun unrelatedBufferingSessionCannotBorrowPreviousToken() {
    val previous = previouslyPlaying()
    val other = session("com.synthetic.other", SessionState.STATE_BUFFERING)
    assertNull(MediaSessionReader.selectSession(listOf(other.controller), own, previous.token))
  }

  @Test fun externalPlayingSessionWinsOverPreviousBufferingAndRelay() {
    val previous = previouslyPlaying()
    val relay = session(own, SessionState.STATE_PLAYING)
    val external = session("com.synthetic.external", SessionState.STATE_PLAYING)
    assertSame(external.controller, MediaSessionReader.selectSession(
        listOf(previous.controller, relay.controller, external.controller), own, previous.token)!!.controller)
  }

  @Test fun playingRelayWinsOverPreviousBufferingPlayer() {
    val previous = previouslyPlaying()
    val relay = session(own, SessionState.STATE_PLAYING)
    assertSame(relay.controller, MediaSessionReader.selectSession(
        listOf(previous.controller, relay.controller), own, previous.token)!!.controller)
  }

  @Test fun bufferingRefreshUsesOnePlaybackReadAndFreshMetadata() {
    val s = previouslyPlaying()
    Mockito.clearInvocations(s.controller)
    val selection = MediaSessionReader.selectSession(listOf(s.controller), own, s.token)
    assertNotNull(selection)
    val later = Mockito.mock(SessionState::class.java)
    `when`(later.state).thenReturn(SessionState.STATE_STOPPED)
    `when`(s.controller.playbackState).thenReturn(later)
    val md = Mockito.mock(MediaMetadata::class.java)
    `when`(md.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("Fresh title")
    `when`(s.controller.metadata).thenReturn(md)
    val snapshot = MediaSessionReader.snapshotFrom(selection!!)
    assertEquals(PlaybackState.PLAYING, snapshot.state)
    assertEquals("Fresh title", snapshot.title)
    Mockito.verify(s.controller, Mockito.times(1)).playbackState
  }

  private fun assertTap(state: Int, pause: Boolean) {
    val s = session("com.synthetic.player", state)
    val transport = Mockito.mock(MediaController.TransportControls::class.java)
    `when`(s.controller.transportControls).thenReturn(transport)
    val active = MediaSessionReader::class.java.getDeclaredField("active").apply { isAccessible = true }
    val previous = active.get(MediaSessionReader)
    try {
      active.set(MediaSessionReader, s.controller)
      MediaSessionReader.playPause()
      if (pause) {
        Mockito.verify(transport).pause()
        Mockito.verify(transport, Mockito.never()).play()
      } else {
        Mockito.verify(transport).play()
        Mockito.verify(transport, Mockito.never()).pause()
      }
      Mockito.verify(s.controller, Mockito.times(1)).playbackState
    } finally {
      active.set(MediaSessionReader, previous)
    }
  }

  @Test fun bufferingCardTapPausesInsteadOfRestarting() = assertTap(SessionState.STATE_BUFFERING, true)
  @Test fun playingCardTapStillPauses() = assertTap(SessionState.STATE_PLAYING, true)
  @Test fun pausedCardTapStillPlays() = assertTap(SessionState.STATE_PAUSED, false)
}
