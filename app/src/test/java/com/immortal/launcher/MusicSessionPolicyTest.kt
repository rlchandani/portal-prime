/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.session.PlaybackState as SessionPlayback
import org.junit.Assert.*
import org.junit.Test

class MusicSessionPolicyTest {
  private fun session(
      actions: Long = SessionPlayback.ACTION_PLAY or SessionPlayback.ACTION_PAUSE,
      duration: Long = 120_000,
  ) = NowPlayingState(PlaybackState.PLAYING, title = "Track", artist = "Artist",
      durationMs = duration, positionMs = 10_000, source = "player.package",
      mediaId = "stable-track-id", mediaActions = actions, positionUpdatedElapsedMs = 3_000)

  @Test fun noSessionRemainsTruthfullyUnconfigured() {
    val s = musicSessionSnapshot(null, 5_000)
    assertEquals(MusicConnection.NOT_CONFIGURED, s.connection)
    assertNull(s.track)
    assertTrue(MusicCommand.entries.none { s.can(it) })
  }

  @Test fun metadataWithoutSourceCannotBecomeAControllablePlayer() {
    val s = musicSessionSnapshot(session().copy(source = ""), 5_000)
    assertNotEquals(MusicConnection.READY, s.connection)
    assertTrue(MusicCommand.entries.none { s.can(it) })
  }

  @Test fun capabilitiesComeOnlyFromAdvertisedSessionActions() {
    val s = musicSessionSnapshot(session(SessionPlayback.ACTION_PLAY_PAUSE or
        SessionPlayback.ACTION_SKIP_TO_NEXT), 5_000)
    assertTrue(s.can(MusicCommand.PLAY))
    assertTrue(s.can(MusicCommand.PAUSE))
    assertTrue(s.can(MusicCommand.NEXT))
    assertFalse(s.can(MusicCommand.PREVIOUS))
    assertFalse(s.can(MusicCommand.SEEK))
    assertFalse(s.can(MusicCommand.OPEN_QUEUE))
    assertFalse(s.can(MusicCommand.OPEN_LYRICS))
    assertFalse(s.can(MusicCommand.CHANGE_ROOMS))
  }

  @Test fun unadvertisedTransportIsUnavailableEvenWithATrack() {
    val s = musicSessionSnapshot(session(0), 5_000)
    assertEquals(MusicConnection.READY, s.connection)
    assertTrue(MusicCommand.entries.none { s.can(it) })
  }

  @Test fun seekRequiresBothAdvertisedActionAndKnownDuration() {
    val s = musicSessionSnapshot(session(SessionPlayback.ACTION_SEEK_TO), 5_000)
    assertTrue(s.can(MusicCommand.SEEK))
    assertFalse(musicSessionSnapshot(session(SessionPlayback.ACTION_SEEK_TO, 0), 5_000)
        .can(MusicCommand.SEEK))
  }

  @Test fun progressKeepsAuthoritativeSessionClockWithoutResettingOnMetadataRefresh() {
    val s = musicSessionSnapshot(session(), 5_000)
    assertEquals(3_000L, s.positionAtElapsedMs)
    assertEquals(12_000L, s.visiblePositionMs(5_000))
  }

  @Test fun missingSessionClockStartsFromReceiptTime() {
    val s = musicSessionSnapshot(session().copy(positionUpdatedElapsedMs = 0), 5_000)
    assertEquals(5_000L, s.positionAtElapsedMs)
    assertEquals(10_000L, s.visiblePositionMs(5_000))
  }

  @Test fun stableMediaIdSurvivesTitleChangesButSourceRemainsPartOfIdentity() {
    val first = musicSessionSnapshot(session(), 5_000).track!!
    val changed = musicSessionSnapshot(session().copy(title = "Updated title"), 6_000).track!!
    assertEquals(first.id, changed.id)
    assertEquals("local:player.package", first.sourceId)
    assertFalse(first.matches("local:other.package", first.id))
  }
}
