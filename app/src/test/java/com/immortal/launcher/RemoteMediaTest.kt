/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import android.media.AudioManager
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class RemoteMediaTest {

  @Test
  fun stateJson_nullIsInactive() {
    val j = RemoteMedia.stateJson(null)
    assertFalse(j.getBoolean("active"))
    assertFalse(j.getBoolean("canSeek"))
  }

  @Test
  fun stateJson_playingTrackSerialisesFields() {
    val s =
        NowPlayingState(
            state = PlaybackState.PLAYING,
            title = "Song",
            artist = "Band",
            album = "Record",
            durationMs = 240_000,
            positionMs = 30_000,
            source = "com.spotify.music")
    val j = RemoteMedia.stateJson(s)
    assertTrue(j.getBoolean("active"))
    assertTrue(j.getBoolean("playing"))
    assertFalse(j.getBoolean("canSeek"))
    assertEquals("Song", j.getString("title"))
    assertEquals("Band", j.getString("artist"))
    assertEquals("Record", j.getString("album"))
    assertEquals(240_000, j.getLong("durationMs"))
    assertEquals(30_000, j.getLong("positionMs"))
  }

  @Test
  fun stateJson_pausedIsNotPlaying() {
    val j = RemoteMedia.stateJson(NowPlayingState(state = PlaybackState.PAUSED, title = "x"))
    assertTrue(j.getBoolean("active"))
    assertFalse(j.getBoolean("playing"))
  }

  @Test
  fun stateJson_hasArtFromUriEvenWithoutBitmap() {
    // A URI-only cover (no embedded bitmap) still counts as art — the Portal resolves it.
    val withUri = RemoteMedia.stateJson(NowPlayingState(PlaybackState.PLAYING, title = "t", artUrl = "content://media/art/1"))
    assertTrue(withUri.getBoolean("hasArt"))
    val none = RemoteMedia.stateJson(NowPlayingState(PlaybackState.PLAYING, title = "t"))
    assertFalse(none.getBoolean("hasArt"))
  }

  @Test
  fun artVersion_stableForSameTrackChangesAcrossTracks() {
    fun ver(title: String) =
        RemoteMedia.stateJson(NowPlayingState(PlaybackState.PLAYING, title = title, artist = "A")).getInt("artVersion")
    // Position/playback changes shouldn't churn the cover; the title (track) changing should.
    assertEquals(ver("Track One"), ver("Track One"))
    assertNotEquals(ver("Track One"), ver("Track Two"))
  }

  @Test fun stateJson_seekCapabilityDoesNotChangeLegacyTrackFieldsOrArtVersion() {
    val original = NowPlayingState(PlaybackState.PLAYING, title = "Synthetic track", source = "com.synthetic.player")
    val before = RemoteMedia.stateJson(original)
    val after = RemoteMedia.stateJson(original.copy(canSeek = true))
    assertFalse(before.getBoolean("canSeek"))
    assertTrue(after.getBoolean("canSeek"))
    after.put("canSeek", false)
    assertEquals(before.toString(), after.toString())
  }

  @Test fun volumeSnapshotAndSerializerAreIndependentOfLaterAudioAndJsonChanges() {
    val audio = Mockito.mock(AudioManager::class.java)
    `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(7)
    `when`(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(15)
    val first = RemoteMedia.volumeState(audio)
    `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(0)
    `when`(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenReturn(true)
    val second = RemoteMedia.volumeState(audio)
    assertEquals(7, first.level)
    assertFalse(first.muted)
    assertEquals(0, second.level)
    assertTrue(second.muted)
    assertTrue(second.canSet)
    val combined = RemoteMedia.stateJson(null, first)
    combined.getJSONObject("volume").put("level", 99)
    val fresh = RemoteMedia.stateJson(null, first)
    assertEquals(7, fresh.getJSONObject("volume").getInt("level"))
    assertFalse(fresh.getBoolean("active"))
    assertFalse(fresh.getBoolean("canSeek"))
  }
}
