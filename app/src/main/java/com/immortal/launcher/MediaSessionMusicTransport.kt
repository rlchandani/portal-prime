/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.session.MediaSession
import android.util.Log

/**
 * [MusicTransport] for an Android media session (e.g. a browser playing YouTube Music). Every
 * command is re-checked on the reader's worker against the expected app, session token and track,
 * so a stale tap can never control a different player or the next song.
 */
internal class MediaSessionMusicTransport(
    private val state: NowPlayingState,
    private val send: (pkg: String, token: MediaSession.Token?, trackId: String, MusicCommand, Long) -> Unit =
        { pkg, token, trackId, command, position ->
          MediaSessionReader.musicCommand(pkg, token, trackId, command, position) { ok ->
            if (!ok) Log.i(TAG, "media-session $command not applied (source changed or unsupported)")
          }
        },
) : MusicTransport {
  private val trackId = musicSessionTrackId(state.mediaId, state.title, state.artist, state.album, state.durationMs)

  private fun command(command: MusicCommand, position: Long = 0) =
      send(state.source, state.sessionToken, trackId, command, position)

  override fun play() = command(MusicCommand.PLAY)
  override fun pause() = command(MusicCommand.PAUSE)
  override fun next() = command(MusicCommand.NEXT)
  override fun previous() = command(MusicCommand.PREVIOUS)
  override fun seekTo(positionMs: Long) = command(MusicCommand.SEEK, positionMs)

  companion object {
    private const val TAG = "ImmortalMusicTransport"

    /** A controllable media-session transport, or null for the embedded page / tokenless states. */
    fun forState(state: NowPlayingState): MusicTransport? = when {
      state.source.isBlank() || state.source == SOURCE_EMBEDDED_YT_MUSIC -> null
      state.sessionToken == null -> null
      else -> MediaSessionMusicTransport(state)
    }

    /** Installed once at process start so every surface (Home, screensaver) can control sessions. */
    fun install() {
      MusicTransports.mediaSession = ::forState
      NowPlayingHub.addListener { if (it != null) MusicSessionMemory.seen = true }
    }
  }
}

/**
 * Whether anything has played since the launcher process started. Drives the music screen's
 * first-run vs "nothing playing" state; in memory only, so it adds no persisted setting.
 */
internal object MusicSessionMemory {
  @Volatile var seen: Boolean = false
}
