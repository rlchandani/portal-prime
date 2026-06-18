/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap

/**
 * Now-playing is sourced from the device's media sessions (see [MediaSessionReader]) —
 * whatever app is playing — and held by [NowPlayingHub]. The screensaver card and the
 * home-header mini-player both read from there, so they work with any media app.
 */
enum class PlaybackState {
  PLAYING,
  PAUSED,
  STOPPED,
  IDLE,
}

/** Immutable now-playing snapshot for the screensaver card and the header mini-player. */
data class NowPlayingState(
    val state: PlaybackState,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUrl: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    /** Where the track came from (a package name, or "snapcast" for the multi-room relay). */
    val source: String = "",
    /**
     * Cover art straight from the media session, already downscaled, so the screensaver
     * and header render with no decode/network hop. [artUrl] is the fallback for URI-only
     * metadata. In-process only — never make this Parcelable.
     */
    val artBitmap: Bitmap? = null,
) {
  /** True when there's an actual track to show (playing or paused, with a title). */
  val active: Boolean
    get() = (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED) && title.isNotBlank()
}
