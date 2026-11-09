/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs

/** One "Recently played" entry: a queue item that actually started playing in [room]. Immutable. */
internal data class MusicPlay(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumb: String,
    val playedAtMs: Long,
    val room: String,
) {
  fun toTrack(): YtTrack = YtTrack(videoId, title, artist, thumb)
}

/**
 * Pure rules for Hearthpane's household music history (YouTube's API has no watch history).
 * Stored newest first, at most [MAX_ENTRIES]; [listing] shows each video once (newest wins).
 */
internal object MusicHistory {
  const val MAX_ENTRIES = 200
  /** Playing the same video again in the same room within this window moves its time instead of adding. */
  const val REPLAY_WINDOW_MS = 10 * 60_000L
  /** "Recently played" shown under an empty search field. */
  const val EMPTY_FIELD_ROWS = 5

  /** Adds [play] (local or from another Portal); a replay within [REPLAY_WINDOW_MS] updates in place. */
  fun record(history: List<MusicPlay>, play: MusicPlay): List<MusicPlay> {
    val replay = history.indexOfFirst { isReplay(it, play) }
    val next = if (replay < 0) history + play else history.mapIndexed { i, old ->
      if (i == replay) newer(old, play) else old
    }
    return next.sortedByDescending { it.playedAtMs }.take(MAX_ENTRIES)
  }

  /** Merges many entries, e.g. a stored list read back; same rules as [record]. */
  fun mergeAll(history: List<MusicPlay>, incoming: List<MusicPlay>): List<MusicPlay> =
      incoming.fold(history) { acc, play -> record(acc, play) }

  /** Each video once, newest play first. */
  fun listing(history: List<MusicPlay>): List<MusicPlay> =
      history.sortedByDescending { it.playedAtMs }.distinctBy { it.videoId }

  fun remove(history: List<MusicPlay>, videoId: String): List<MusicPlay> = history.filterNot { it.videoId == videoId }

  /** A household "clear" sent at [clearedAtMs] keeps anything played after it (a newer song elsewhere). */
  fun clearedAt(history: List<MusicPlay>, clearedAtMs: Long): List<MusicPlay> =
      history.filter { it.playedAtMs > clearedAtMs }

  /** "Living Area · 2 h ago". */
  fun where(play: MusicPlay, nowMs: Long): String =
      listOf(play.room, ago(play.playedAtMs, nowMs)).filter { it.isNotBlank() }.joinToString(" · ")

  /** just now, 5 min ago, 2 h ago, yesterday, 3 days ago. A future time reads as just now. */
  fun ago(atMs: Long, nowMs: Long): String {
    val minutes = (nowMs - atMs).coerceAtLeast(0L) / MINUTE_MS
    return when {
      minutes < 1 -> "just now"
      minutes < MINUTES_PER_HOUR -> "$minutes min ago"
      minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
      minutes < 2 * MINUTES_PER_DAY -> "yesterday"
      else -> "${minutes / MINUTES_PER_DAY} days ago"
    }
  }

  private fun isReplay(old: MusicPlay, play: MusicPlay): Boolean =
      old.videoId == play.videoId && old.room == play.room && abs(old.playedAtMs - play.playedAtMs) <= REPLAY_WINDOW_MS

  /** The later play's time and metadata; a blank field never wipes a known one. */
  private fun newer(old: MusicPlay, play: MusicPlay): MusicPlay {
    val (late, early) = if (play.playedAtMs >= old.playedAtMs) play to old else old to play
    return late.copy(title = late.title.ifBlank { early.title }, artist = late.artist.ifBlank { early.artist },
        thumb = late.thumb.ifBlank { early.thumb })
  }

  private const val MINUTE_MS = 60_000L
  private const val MINUTES_PER_HOUR = 60L
  private const val MINUTES_PER_DAY = 24 * 60L
}

/**
 * Detects "a queue item actually started playing": the official player reports PLAYING and its own
 * video id is the current entry's (so an ad never counts). Each queue entry is recorded once.
 */
internal object MusicPlayDetector {
  /** The entry that just started, or null; [lastEntryId] is the last one recorded. */
  fun started(s: YtEngineState, lastEntryId: Long?): YtQueueEntry? {
    val entry = s.queue.current ?: return null
    if (s.status != YtPlayerStatus.PLAYING || s.playerVideoId != entry.track.videoId) return null
    return entry.takeIf { it.id != lastEntryId }
  }
}
