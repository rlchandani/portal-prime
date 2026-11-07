/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MediaSessionMusicTransportTest {
  private data class Sent(val pkg: String, val trackId: String, val command: MusicCommand, val position: Long)

  private val state = NowPlayingState(PlaybackState.PLAYING, title = "Song", artist = "Band",
      album = "LP", durationMs = 200_000, source = "org.chromium.chrome", mediaId = "")

  private fun transport(sent: MutableList<Sent>) =
      MediaSessionMusicTransport(state) { pkg, _, id, command, position -> sent += Sent(pkg, id, command, position) }

  @Test fun commands_targetTheExactSourceAndTrack() {
    val sent = mutableListOf<Sent>()
    val t = transport(sent)
    t.play(); t.pause(); t.next(); t.previous(); t.seekTo(42_000)
    val id = musicSessionTrackId("", "Song", "Band", "LP", 200_000)
    assertEquals(listOf(
        Sent("org.chromium.chrome", id, MusicCommand.PLAY, 0),
        Sent("org.chromium.chrome", id, MusicCommand.PAUSE, 0),
        Sent("org.chromium.chrome", id, MusicCommand.NEXT, 0),
        Sent("org.chromium.chrome", id, MusicCommand.PREVIOUS, 0),
        Sent("org.chromium.chrome", id, MusicCommand.SEEK, 42_000)), sent)
  }

  @Test fun factory_needsASessionToken_andIgnoresTheEmbeddedPage() {
    assertNull(MediaSessionMusicTransport.forState(state)) // no token in a JVM test
    assertNull(MediaSessionMusicTransport.forState(state.copy(source = SOURCE_EMBEDDED_YT_MUSIC)))
    assertNull(MediaSessionMusicTransport.forState(state.copy(source = "")))
  }
}
