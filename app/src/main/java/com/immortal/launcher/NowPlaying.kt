/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent

/**
 * Launcher (consumer) side of the now-playing broadcast contract that the
 * ImmortalCast companion (GPL, separate app) produces. ImmortalCast plays the
 * synced Snapcast audio and relays the active track's metadata here over an
 * intent-only boundary — the mirror image of how Immortal hands the companion its
 * [PresenceHub] state. If ImmortalCast isn't installed nothing is ever sent, so
 * the launcher stays exactly as it was.
 *
 * These constants MUST match ImmortalCast's `NowPlaying.kt` verbatim.
 */
object NowPlaying {
  const val ACTION_NOW_PLAYING = "com.immortal.launcher.NOW_PLAYING"

  const val EXTRA_STATE = "state" // PlaybackState.name
  const val EXTRA_TITLE = "title"
  const val EXTRA_ARTIST = "artist"
  const val EXTRA_ALBUM = "album"
  const val EXTRA_ART_URL = "artUrl" // absolute cover-art URL (Music Assistant on the LAN)
  const val EXTRA_DURATION_MS = "durationMs"
  const val EXTRA_POSITION_MS = "positionMs"
  const val EXTRA_GROUP = "group"
  const val EXTRA_SOURCE = "source"
  const val EXTRA_AT_MS = "atMs"
}

enum class PlaybackState {
  PLAYING,
  PAUSED,
  STOPPED,
  IDLE,
}

/** Immutable now-playing snapshot, parsed from the companion's broadcast. */
data class NowPlayingState(
    val state: PlaybackState,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUrl: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val group: String = "",
    val source: String = "",
    val atMs: Long = 0L,
) {
  /** True when there's an actual track to show (playing or paused, with a title). */
  val active: Boolean
    get() = (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED) && title.isNotBlank()

  companion object {
    /** Parse a received broadcast. Tolerant of missing/garbled extras. */
    fun fromIntent(intent: Intent): NowPlayingState =
        NowPlayingState(
            state =
                runCatching {
                      PlaybackState.valueOf(intent.getStringExtra(NowPlaying.EXTRA_STATE) ?: "")
                    }
                    .getOrDefault(PlaybackState.IDLE),
            title = intent.getStringExtra(NowPlaying.EXTRA_TITLE).orEmpty(),
            artist = intent.getStringExtra(NowPlaying.EXTRA_ARTIST).orEmpty(),
            album = intent.getStringExtra(NowPlaying.EXTRA_ALBUM).orEmpty(),
            artUrl = intent.getStringExtra(NowPlaying.EXTRA_ART_URL).orEmpty(),
            durationMs = intent.getLongExtra(NowPlaying.EXTRA_DURATION_MS, 0L),
            positionMs = intent.getLongExtra(NowPlaying.EXTRA_POSITION_MS, 0L),
            group = intent.getStringExtra(NowPlaying.EXTRA_GROUP).orEmpty(),
            source = intent.getStringExtra(NowPlaying.EXTRA_SOURCE).orEmpty(),
            atMs = intent.getLongExtra(NowPlaying.EXTRA_AT_MS, 0L),
        )
  }
}
