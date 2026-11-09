/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A short message plus one line of detail, shown in a card or pane. */
internal data class MusicV3Message(val title: String, val detail: String)

/**
 * Everything the Now Playing card needs, derived from one [YtEngineState]. During an ad
 * ([YtEngineState.isUnknownItem]) previous/play/next are disabled and progress is replaced by a
 * note; volume always works. A blocked autoplay shows one big Play button.
 */
internal data class MusicV3NowPlaying(
    val track: YtTrack?,
    val isAd: Boolean,
    /** Show the Pause glyph. */
    val playing: Boolean,
    val transportEnabled: Boolean,
    val showBigPlay: Boolean,
    val statusLine: String,
    val showProgress: Boolean,
    val halt: MusicV3Message?,
) {
  val hasTrack: Boolean get() = track != null

  companion object {
    const val AD_LINE = "Your song starts after this"
    const val AD_NOTE = "Controls come back when the ad ends. Volume still works."

    fun from(s: YtEngineState, room: String): MusicV3NowPlaying {
      val track = s.queue.current?.track
      val ad = track != null && s.isUnknownItem
      val playing = track != null && !s.autoplayBlocked && (s.isPlaying || s.wantsPlay)
      val where = room.ifBlank { "this Portal" }
      return MusicV3NowPlaying(
          track = track,
          isAd = ad,
          playing = playing,
          transportEnabled = track != null && !ad,
          showBigPlay = track != null && !ad && s.autoplayBlocked,
          statusLine = if (track == null || ad) "" else "${if (playing) "Playing" else "Paused"} on $where",
          showProgress = track != null && !ad,
          halt = s.halt?.let(::haltMessage),
      )
    }

    /** Position extrapolated from the last player report while it plays, clamped to the length. */
    fun livePositionMs(s: YtEngineState, nowElapsedMs: Long): Long = MusicProgressSlice.of(s).liveMs(nowElapsedMs)

    /** The player's reported length, else the Data API's. */
    fun durationMs(s: YtEngineState): Long =
        s.durationMs.takeIf { it > 0L } ?: s.queue.current?.track?.durationMs ?: 0L

    fun haltMessage(halt: YtHalt): MusicV3Message = when (halt) {
      YtHalt.EndOfQueue -> MusicV3Message("That's the end of the queue",
          "Add more from Library or Search.")
      is YtHalt.TooManyFailures -> MusicV3Message("${halt.count} songs in a row couldn't play here",
          "Try a different playlist, or tap Play to try again.")
      is YtHalt.PlayerError -> MusicV3Message("YouTube's player stopped", "Tap Play to try again.")
      YtHalt.PlayerUnavailable -> MusicV3Message("YouTube's player didn't load",
          "Check the connection, then tap Play.")
    }
  }
}

/**
 * Whether a playing YouTube video owns the screen: the screensaver must not start or relaunch its
 * frame over it (recommended answer 6). Plain JVM so [DreamPolicy] can read it, like
 * [RoutineScreenState]. Kept in sync by the music screen; pausing frees the normal idle timer.
 */
internal object YtVideoScreenState {
  @Volatile var playingOnScreen = false

  fun holds(s: YtEngineState): Boolean = s.foreground && s.isPlaying

  fun ownsScreen(): Boolean = playingOnScreen
}

/** Queue pane rows: the current entry, then Up next with its source label. */
internal data class MusicV3QueueView(
    val current: YtQueueEntry?,
    /** (queue index, entry) pairs after the current entry. */
    val upNext: List<Pair<Int, YtQueueEntry>>,
    val sourceLabel: String,
) {
  companion object {
    fun from(queue: YtQueue): MusicV3QueueView {
      val start = queue.currentIndex + 1
      val rest = queue.entries.withIndex().drop(start.coerceAtLeast(0)).map { it.index to it.value }
      return MusicV3QueueView(queue.current, rest, queue.sourceLabel)
    }
  }
}
