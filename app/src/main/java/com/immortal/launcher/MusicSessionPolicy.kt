/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.session.PlaybackState as SessionPlayback

/** Pure mapping; no Binder reads, network work or playback mutation occurs here. */
internal fun musicSessionSnapshot(state: NowPlayingState?, receivedAtElapsedMs: Long): MusicPlayerSnapshot {
  if (state == null || !state.active || state.source.isBlank()) return MusicPlayerSnapshot()
  val source = "local:${state.source}"
  val actions = state.mediaActions
  fun has(mask: Long) = actions and mask != 0L
  val duration = state.durationMs.takeIf { it > 0 }
  val id = musicSessionTrackId(state.mediaId, state.title, state.artist, state.album, state.durationMs)
  return MusicPlayerSnapshot(
      connection = MusicConnection.READY,
      sourceId = source,
      track = MusicTrack(id, source, state.title, state.artist, state.album,
          state.artUrl, duration),
      playback = state.state,
      positionMs = state.positionMs,
      positionAtElapsedMs = state.positionUpdatedElapsedMs.takeIf { it > 0 }
          ?: receivedAtElapsedMs.coerceAtLeast(0),
      artBitmap = state.artBitmap,
      localSessionToken = state.sessionToken,
      capabilities = MusicCapabilities(
          play = has(SessionPlayback.ACTION_PLAY or SessionPlayback.ACTION_PLAY_PAUSE),
          pause = has(SessionPlayback.ACTION_PAUSE or SessionPlayback.ACTION_PLAY_PAUSE),
          next = has(SessionPlayback.ACTION_SKIP_TO_NEXT),
          previous = has(SessionPlayback.ACTION_SKIP_TO_PREVIOUS),
          seek = has(SessionPlayback.ACTION_SEEK_TO),
      ),
  )
}

/** Shared by presentation and worker execution, without decoding artwork to validate a command. */
internal fun musicSessionTrackId(mediaId: String, title: String, artist: String, album: String,
    durationMs: Long): String = mediaId.ifBlank {
  listOf(title, artist, album, durationMs.toString())
      .joinToString(separator = "\u001f") { "${it.length}:$it" }
}
