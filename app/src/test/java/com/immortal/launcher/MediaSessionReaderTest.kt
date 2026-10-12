/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState as SessionPlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class MediaSessionReaderTest {
  @Test fun seekCapabilityRequiresTheSeekActionMask() {
    val controller = Mockito.mock(MediaController::class.java)
    `when`(controller.packageName).thenReturn("com.synthetic.player")
    val playback = Mockito.mock(SessionPlaybackState::class.java)
    `when`(controller.playbackState).thenReturn(playback)
    `when`(playback.state).thenReturn(SessionPlaybackState.STATE_PLAYING)
    for ((actions, expected) in listOf(
        0L to false,
        SessionPlaybackState.ACTION_PLAY to false,
        (SessionPlaybackState.ACTION_PLAY or SessionPlaybackState.ACTION_SKIP_TO_NEXT) to false,
        (1L shl 40) to false,
        SessionPlaybackState.ACTION_SEEK_TO to true,
        (SessionPlaybackState.ACTION_SEEK_TO or SessionPlaybackState.ACTION_PAUSE) to true)) {
      `when`(playback.actions).thenReturn(actions)
      assertEquals("actions=$actions", expected, MediaSessionReader.snapshotFrom(controller).canSeek)
    }
  }

  @Test fun missingPlaybackStateCannotSeek() {
    val controller = Mockito.mock(MediaController::class.java)
    `when`(controller.packageName).thenReturn("com.synthetic.player")
    val snapshot = MediaSessionReader.snapshotFrom(controller)
    assertFalse(snapshot.canSeek)
    assertEquals(PlaybackState.STOPPED, snapshot.state)
  }

  @Test fun pausedSnapshotKeepsSeekCapabilityAndDoesNotFollowLaterControllerChanges() {
    val controller = Mockito.mock(MediaController::class.java)
    `when`(controller.packageName).thenReturn("com.synthetic.player")
    val playback = Mockito.mock(SessionPlaybackState::class.java)
    val metadata = Mockito.mock(MediaMetadata::class.java)
    `when`(controller.playbackState).thenReturn(playback)
    `when`(controller.metadata).thenReturn(metadata)
    `when`(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("Synthetic track")
    `when`(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(120_000L)
    `when`(playback.state).thenReturn(SessionPlaybackState.STATE_PAUSED)
    `when`(playback.actions).thenReturn(SessionPlaybackState.ACTION_SEEK_TO)
    `when`(playback.position).thenReturn(5_000L)
    val first = MediaSessionReader.snapshotFrom(controller)
    `when`(playback.actions).thenReturn(SessionPlaybackState.ACTION_PLAY)
    `when`(playback.position).thenReturn(9_000L)
    val second = MediaSessionReader.snapshotFrom(controller)
    assertEquals(PlaybackState.PAUSED, first.state)
    assertTrue(first.canSeek)
    assertEquals(5_000L, first.positionMs)
    assertEquals(120_000L, first.durationMs)
    assertFalse(second.canSeek)
    assertEquals(9_000L, second.positionMs)
    val json = RemoteMedia.stateJson(first)
    assertTrue(json.getBoolean("canSeek"))
    assertFalse(json.getBoolean("playing"))
    assertEquals("com.synthetic.player", json.getString("source"))
  }
  private fun session(pkg: String, state: Int, token: android.media.session.MediaSession.Token? = null): MediaController {
    val controller = Mockito.mock(MediaController::class.java)
    val playback = Mockito.mock(SessionPlaybackState::class.java)
    `when`(controller.packageName).thenReturn(pkg)
    `when`(controller.playbackState).thenReturn(playback)
    `when`(controller.sessionToken).thenReturn(token)
    `when`(playback.state).thenReturn(state)
    return controller
  }

  @Test fun refreshReadsEachPlayingCandidateOnceIncludingSelectedSnapshot() {
    val controllers = (0 until 10).map { session("com.synthetic.player.$it", SessionPlaybackState.STATE_PLAYING) }
    val selected = MediaSessionReader.selectSession(controllers, "com.synthetic.relay", null)!!
    assertSame(controllers.first(), selected.controller)
    MediaSessionReader.snapshotFrom(selected)
    controllers.forEach { Mockito.verify(it, Mockito.times(1)).playbackState }
  }

  @Test fun refreshReadsEachPausedCandidateOnceIncludingSelectedSnapshot() {
    val token = Mockito.mock(android.media.session.MediaSession.Token::class.java)
    val controllers = (0 until 10).map { session("com.synthetic.player.$it", SessionPlaybackState.STATE_PAUSED,
        if (it == 9) token else Mockito.mock(android.media.session.MediaSession.Token::class.java)) }
    val selected = MediaSessionReader.selectSession(controllers, "com.synthetic.relay", token)!!
    assertSame(controllers.last(), selected.controller)
    MediaSessionReader.snapshotFrom(selected)
    controllers.forEach { Mockito.verify(it, Mockito.times(1)).playbackState }
  }

  @Test fun realPlayerWinsBeforeRelayAndKeepsFirstPlayerOrder() {
    val relay = session("com.synthetic.relay", SessionPlaybackState.STATE_PLAYING)
    val first = session("com.synthetic.first", SessionPlaybackState.STATE_PLAYING)
    val second = session("com.synthetic.second", SessionPlaybackState.STATE_PLAYING)
    assertSame(first, MediaSessionReader.selectSession(listOf(relay, first, second), "com.synthetic.relay", null)!!.controller)
  }

  @Test fun relayIsSelectedWhenItIsTheOnlyPlayingSession() {
    val relay = session("com.synthetic.relay", SessionPlaybackState.STATE_PLAYING)
    val paused = session("com.synthetic.paused", SessionPlaybackState.STATE_PAUSED)
    assertSame(relay, MediaSessionReader.selectSession(listOf(paused, relay), "com.synthetic.relay", null)!!.controller)
  }

  @Test fun unrelatedPausedSessionIsNeverResurrected() {
    val token = Mockito.mock(android.media.session.MediaSession.Token::class.java)
    val paused = session("com.synthetic.paused", SessionPlaybackState.STATE_PAUSED,
        Mockito.mock(android.media.session.MediaSession.Token::class.java))
    assertNull(MediaSessionReader.selectSession(listOf(paused), "com.synthetic.relay", token))
    assertNull(MediaSessionReader.selectSession(listOf(paused), "com.synthetic.relay", null))
  }

  @Test fun playingSessionWinsOverThePreviouslyPlayingPausedSession() {
    val token = Mockito.mock(android.media.session.MediaSession.Token::class.java)
    val paused = session("com.synthetic.paused", SessionPlaybackState.STATE_PAUSED, token)
    val playing = session("com.synthetic.playing", SessionPlaybackState.STATE_PLAYING)
    assertSame(playing, MediaSessionReader.selectSession(listOf(paused, playing), "com.synthetic.relay", token)!!.controller)
  }

  @Test fun emptyAndMissingStateCannotSelectAPlayer() {
    assertNull(MediaSessionReader.selectSession(emptyList(), "com.synthetic.relay", null))
    assertNull(MediaSessionReader.selectSession(listOf(Mockito.mock(MediaController::class.java)), "com.synthetic.relay", null))
  }

  @Test fun selectedSnapshotUsesTheCapturedPlaybackStateButFreshMetadata() {
    val controller = session("com.synthetic.player", SessionPlaybackState.STATE_PLAYING)
    val playback = controller.playbackState!!
    `when`(playback.position).thenReturn(5000L)
    `when`(playback.actions).thenReturn(SessionPlaybackState.ACTION_SEEK_TO)
    val selected = MediaSessionReader.selectSession(listOf(controller), "com.synthetic.relay", null)!!
    val later = Mockito.mock(SessionPlaybackState::class.java)
    `when`(later.state).thenReturn(SessionPlaybackState.STATE_PAUSED)
    `when`(controller.playbackState).thenReturn(later)
    val metadata = Mockito.mock(MediaMetadata::class.java)
    `when`(controller.metadata).thenReturn(metadata)
    `when`(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("Fresh metadata")
    val snapshot = MediaSessionReader.snapshotFrom(selected)
    assertEquals(PlaybackState.PLAYING, snapshot.state)
    assertEquals(5000L, snapshot.positionMs)
    assertTrue(snapshot.canSeek)
    assertEquals("Fresh metadata", snapshot.title)
    assertEquals(PlaybackState.PAUSED, MediaSessionReader.snapshotFrom(controller).state)
  }

  @Test fun nextRefreshReadsTheCurrentStateAndRetainsNoSelectionCache() {
    val token = Mockito.mock(android.media.session.MediaSession.Token::class.java)
    val controller = session("com.synthetic.player", SessionPlaybackState.STATE_PLAYING, token)
    val first = MediaSessionReader.selectSession(listOf(controller), "com.synthetic.relay", null)!!
    val paused = Mockito.mock(SessionPlaybackState::class.java)
    `when`(paused.state).thenReturn(SessionPlaybackState.STATE_PAUSED)
    `when`(controller.playbackState).thenReturn(paused)
    val next = MediaSessionReader.selectSession(listOf(controller), "com.synthetic.relay", token)!!
    assertNotSame(first.playbackState, next.playbackState)
    assertEquals(SessionPlaybackState.STATE_PAUSED, next.playbackState!!.state)
  }

}
