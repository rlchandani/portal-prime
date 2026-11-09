/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The parts of [YtEngineState] the stage lays out from. Position ticks (about once a second) do not
 * change it, so the stage doesn't recompose on every tick; only [MusicProgressSlice] readers do.
 */
internal data class MusicNowSlice(
    val model: MusicV3NowPlaying,
    val queue: YtQueue,
    val sleep: MusicSleepTimer?,
    val lastSkip: YtSkipNotice?,
    /** A playing video owns the screen (keep awake, no screensaver). */
    val holdsScreen: Boolean,
    val playingOrWanted: Boolean,
) {
  val track: YtTrack? get() = model.track
  val idle: Boolean get() = model.track == null
  val next: YtTrack? get() = queue.entries.getOrNull(queue.currentIndex + 1)?.track

  companion object {
    fun of(s: YtEngineState, room: String) = MusicNowSlice(
        model = MusicV3NowPlaying.from(s, room), queue = s.queue, sleep = s.sleep, lastSkip = s.lastSkip,
        holdsScreen = YtVideoScreenState.holds(s), playingOrWanted = s.isPlaying || s.wantsPlay)
  }
}

/** What the scrubber needs; changes on every position report. */
internal data class MusicProgressSlice(
    val playing: Boolean,
    val positionMs: Long,
    val atElapsedMs: Long,
    val lengthMs: Long,
    val songId: String?,
) {
  /** Position extrapolated to [nowElapsedMs] while playing (same maths as [MusicV3NowPlaying.livePositionMs]). */
  fun liveMs(nowElapsedMs: Long): Long {
    val moving = playing && atElapsedMs > 0L
    val raw = positionMs + if (moving) (nowElapsedMs - atElapsedMs).coerceAtLeast(0L) else 0L
    return if (lengthMs > 0L) raw.coerceAtMost(lengthMs) else raw
  }

  companion object {
    fun of(s: YtEngineState) = MusicProgressSlice(s.status == YtPlayerStatus.PLAYING, s.positionMs,
        s.positionAtElapsedMs, MusicV3NowPlaying.durationMs(s), s.queue.current?.track?.videoId)
  }
}

/** A narrow, de-duplicated view of the engine state. */
internal fun <T> engineSlice(pick: (YtEngineState) -> T): Flow<T> =
    YouTubeMusicEngine.state.map(pick).distinctUntilChanged()

@Composable
internal fun rememberNowSlice(room: String): State<MusicNowSlice> {
  val flow = remember(room) { engineSlice { MusicNowSlice.of(it, room) } }
  return flow.collectAsState(initial = MusicNowSlice.of(YouTubeMusicEngine.state.value, room))
}

@Composable
internal fun rememberProgressSlice(): State<MusicProgressSlice> {
  val flow = remember { engineSlice(MusicProgressSlice::of) }
  return flow.collectAsState(initial = MusicProgressSlice.of(YouTubeMusicEngine.state.value))
}
