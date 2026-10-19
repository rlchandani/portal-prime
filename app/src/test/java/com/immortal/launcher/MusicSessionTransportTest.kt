/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.session.MediaController
import android.media.session.PlaybackState as SessionPlayback
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class MusicSessionTransportTest {
  private fun player(actions: Long): Pair<MediaController, MediaController.TransportControls> {
    val controller = mock(MediaController::class.java)
    val controls = mock(MediaController.TransportControls::class.java)
    val playback = mock(SessionPlayback::class.java)
    `when`(controller.packageName).thenReturn("com.player")
    `when`(controller.transportControls).thenReturn(controls)
    `when`(controller.playbackState).thenReturn(playback)
    `when`(playback.actions).thenReturn(actions)
    return controller to controls
  }

  @Test fun commandsCannotFallThroughToANewActivePackage() {
    val (player, controls) = player(SessionPlayback.ACTION_PAUSE)
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.old.player", MusicCommand.PAUSE))
    verifyNoInteractions(controls)
  }

  @Test fun advertisedPauseIsExplicitRatherThanAToggle() {
    val (player, controls) = player(SessionPlayback.ACTION_PAUSE)
    assertTrue(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.PAUSE))
    verify(controls).pause()
    verify(controls, never()).play()
  }

  @Test fun unsupportedActionsDoNotReachTransport() {
    val (player, controls) = player(SessionPlayback.ACTION_PLAY)
    for (command in listOf(MusicCommand.PAUSE, MusicCommand.NEXT, MusicCommand.PREVIOUS, MusicCommand.SEEK))
      assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", command, 2_000))
    verifyNoInteractions(controls)
  }

  @Test fun knownSeekBoundsAreCheckedAtExecution() {
    val (player, controls) = player(SessionPlayback.ACTION_SEEK_TO)
    val metadata = mock(android.media.MediaMetadata::class.java)
    `when`(player.metadata).thenReturn(metadata)
    `when`(metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)).thenReturn(100_000)
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.SEEK, -1))
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.SEEK, 100_001))
    assertTrue(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.SEEK, 25_000))
    verify(controls).seekTo(25_000)
  }

  @Test fun missingStateOrUnknownDurationCannotSeek() {
    val (player, controls) = player(SessionPlayback.ACTION_SEEK_TO)
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.SEEK, 5_000))
    `when`(player.playbackState).thenReturn(null)
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.PLAY))
    verifyNoInteractions(controls)
  }

  @Test fun localSourceCannotExecuteServerOnlyCommands() {
    val (player, controls) = player(Long.MAX_VALUE)
    for (command in listOf(MusicCommand.CHANGE_ROOMS, MusicCommand.SELECT_QUEUE_ITEM, MusicCommand.FAVORITE))
      assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", command))
    verifyNoInteractions(controls)
  }

  @Test fun replacementSessionInSamePackageCannotReceiveAnOldCommand() {
    val (player, controls) = player(SessionPlayback.ACTION_PAUSE)
    val oldToken = mock(android.media.session.MediaSession.Token::class.java)
    val newToken = mock(android.media.session.MediaSession.Token::class.java)
    `when`(player.sessionToken).thenReturn(newToken)
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.PAUSE,
        expectedToken = oldToken))
    verifyNoInteractions(controls)
  }

  @Test fun seekCannotLandOnATrackThatAdvancedWhileTheCommandWasQueued() {
    val (player, controls) = player(SessionPlayback.ACTION_SEEK_TO)
    val metadata = mock(android.media.MediaMetadata::class.java)
    `when`(player.metadata).thenReturn(metadata)
    `when`(metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)).thenReturn("new-track")
    `when`(metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)).thenReturn(120_000)
    assertFalse(MediaSessionReader.executeMusicCommand(player, "com.player", MusicCommand.SEEK, 20_000,
        expectedTrackId = "old-track"))
    verifyNoInteractions(controls)
  }
}
