/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Maps the engine state to the shared [NowPlayingState] that Home, the screensaver card and the
 * Now Playing column read. Metadata comes from our queue (the official player exposes no track
 * metadata); times come from the player. While the player shows an unknown item (typically an
 * ad, see [YtEngineState.isUnknownItem]) progress and seek are hidden rather than misreported.
 */
object YtNowPlaying {
  fun map(s: YtEngineState): NowPlayingState? {
    val track = s.queue.current?.track ?: return null
    if (s.halt != null || s.status == YtPlayerStatus.ENDED) return null
    val playing = s.status == YtPlayerStatus.PLAYING ||
        (s.status == YtPlayerStatus.BUFFERING && s.wantsPlay)
    val unknown = s.isUnknownItem
    val duration = if (unknown) track.durationMs ?: 0L else s.durationMs
    return NowPlayingState(
        state = if (playing) PlaybackState.PLAYING else PlaybackState.PAUSED,
        title = track.title,
        artist = track.artist,
        artUrl = track.thumbnailUrl,
        durationMs = duration,
        positionMs = if (unknown) 0L else s.positionMs,
        source = SOURCE_YOUTUBE,
        canSeek = !unknown && duration > 0,
        positionUpdatedElapsedMs = s.positionAtElapsedMs,
        mediaId = track.videoId,
    )
  }
}
