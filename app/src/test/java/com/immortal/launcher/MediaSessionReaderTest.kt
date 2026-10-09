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
}
