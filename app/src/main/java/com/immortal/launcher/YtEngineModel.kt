/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Official IFrame player states (`YT.PlayerState`), plus [NOT_READY] before `onReady`. */
enum class YtPlayerStatus(val wire: Int?) {
  NOT_READY(null),
  UNSTARTED(-1),
  ENDED(0),
  PLAYING(1),
  PAUSED(2),
  BUFFERING(3),
  CUED(5);

  companion object {
    fun fromWire(code: Int): YtPlayerStatus? = entries.firstOrNull { it.wire == code }
  }
}

/** Why playback stopped on its own; the UI shows a clear message instead of looping. */
sealed interface YtHalt {
  /** The queue ran out (repeat off) or every remaining entry is unplayable. */
  data object EndOfQueue : YtHalt
  /** [count] videos in a row failed; we stop rather than churn through the queue. */
  data class TooManyFailures(val count: Int) : YtHalt
  /** A player error that is not about one video (e.g. a configuration error); retry needed. */
  data class PlayerError(val code: Int) : YtHalt
  /** The IFrame API script could not load or the renderer keeps crashing. */
  data object PlayerUnavailable : YtHalt
}

/** One auto-skip, for a toast. [seq] increases per skip so the UI can show each one once. */
data class YtSkipNotice(val seq: Int, val title: String, val reason: String)

/**
 * Whole engine snapshot (immutable). [wantsPlay] is the user's intent; [status] is what the
 * official player last reported. Positions extrapolate from [positionAtElapsedMs] while playing.
 */
data class YtEngineState(
    val queue: YtQueue = YtQueue.EMPTY,
    val status: YtPlayerStatus = YtPlayerStatus.NOT_READY,
    val wantsPlay: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionAtElapsedMs: Long = 0L,
    /** `getVideoData().video_id` as the official player reports it (may differ during ads). */
    val playerVideoId: String? = null,
    val consecutiveFailures: Int = 0,
    val halt: YtHalt? = null,
    val lastSkip: YtSkipNotice? = null,
    val autoplayBlocked: Boolean = false,
    /** The music screen is in the foreground; playback is refused otherwise (policy). */
    val foreground: Boolean = false,
    /** False when the player is laid out below the 200×200 minimum (enforced in debug builds). */
    val sizeOk: Boolean = true,
    /** A queue moved here from another Portal starts as soon as the music screen is visible. */
    val playWhenVisible: Boolean = false,
    /** The visible player's answer to the latest [YtAction.LookUpPlaylist] (not persisted). */
    val playlistLookup: YtPlaylistLookup? = null,
) {
  val ready: Boolean get() = status != YtPlayerStatus.NOT_READY
  val isPlaying: Boolean get() = status == YtPlayerStatus.PLAYING || status == YtPlayerStatus.BUFFERING

  /**
   * Heuristic "the player shows something that is not our queue item" (typically an ad), derived
   * only from official player state. Nothing acts on it beyond hiding our metadata's progress.
   */
  val isUnknownItem: Boolean
    get() {
      val reported = playerVideoId ?: return false
      val expected = queue.current?.track?.videoId ?: return false
      return status != YtPlayerStatus.NOT_READY && reported != expected
    }
}

/** Video ids the official player reported for [listId]; empty when it couldn't open the list. */
data class YtPlaylistLookup(val listId: String, val videoIds: List<String>)

/** Everything that can happen to the engine: user commands and official player events. */
sealed interface YtAction {
  data class PlayNow(val tracks: List<YtTrack>, val startIndex: Int, val label: String) : YtAction
  data class PlayNext(val track: YtTrack) : YtAction
  data class AddToEnd(val track: YtTrack) : YtAction
  data class Remove(val entryId: Long) : YtAction
  data class Move(val from: Int, val to: Int) : YtAction
  data class Select(val entryId: Long) : YtAction
  data object Next : YtAction
  data object Previous : YtAction
  data object Play : YtAction
  data object Pause : YtAction
  data class SeekTo(val positionMs: Long) : YtAction
  data class SetRepeat(val mode: YtRepeat) : YtAction
  data class SetShuffle(val on: Boolean, val seed: Long) : YtAction
  data object Clear : YtAction
  data class Restore(val queue: YtQueue, val positionMs: Long) : YtAction
  /** Replaces the queue with one moved here from another Portal and resumes it at [positionMs]. */
  data class Adopt(val queue: YtQueue, val positionMs: Long) : YtAction
  /**
   * A queue rebuilt for a song or playlist shared from a phone ([MusicShareQueue]). [restart]
   * plays its current entry from the start (like [Adopt]); otherwise only the queue changes.
   */
  data class ApplyShared(val queue: YtQueue, val restart: Boolean) : YtAction
  /** Cue a playlist on the visible player and read its ids (shared playlist, nobody signed in). */
  data class LookUpPlaylist(val listId: String) : YtAction
  data class Foreground(val visible: Boolean) : YtAction
  data class SizeChanged(val ok: Boolean) : YtAction
  /** The WebView renderer died or the host was released; the player must be rebuilt. */
  data object PlayerLost : YtAction
}

/** Events parsed from the IFrame page bridge ([YtPlayerEventParser]). */
sealed interface YtPlayerEvent : YtAction {
  data object Ready : YtPlayerEvent
  data class StateChanged(val status: YtPlayerStatus) : YtPlayerEvent
  data class Error(val code: Int) : YtPlayerEvent
  data class Progress(
      val positionMs: Long,
      val durationMs: Long,
      val videoId: String?,
      val elapsedMs: Long,
      /** `getVideoData()` title/author, used only to fill placeholder queue metadata. */
      val title: String? = null,
      val author: String? = null,
  ) : YtPlayerEvent
  data object AutoplayBlocked : YtPlayerEvent
  data object ApiUnavailable : YtPlayerEvent
  /** `getPlaylist()` after a [YtAction.LookUpPlaylist] cue. */
  data class Playlist(val listId: String, val videoIds: List<String>) : YtPlayerEvent
}

/** Side effects the Android engine executes after a reduction, in order. */
sealed interface YtEffect {
  data class Load(val videoId: String, val startMs: Long) : YtEffect
  data class Cue(val videoId: String, val startMs: Long) : YtEffect
  data object Play : YtEffect
  data object Pause : YtEffect
  data class Seek(val positionMs: Long) : YtEffect
  data object Stop : YtEffect
  data class CuePlaylist(val listId: String) : YtEffect
  /** Save queue + position so the queue survives process death. */
  data object Persist : YtEffect
}

/** Result of one reduction. */
data class YtStep(val state: YtEngineState, val effects: List<YtEffect> = emptyList())
