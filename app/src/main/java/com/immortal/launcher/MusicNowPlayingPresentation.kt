/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure presentation rules for the native Now Playing screen ([MusicPlayerScreen]).
 * No Android calls here so every rule is JVM-testable; the caller supplies the clock.
 */
object MusicNowPlayingPresentation {
  /** Which layout the music screen shows. */
  enum class MusicScreenState { FIRST_RUN, NOTHING_PLAYING, PLAYING, PAUSED }

  const val UNKNOWN_TITLE = "Unknown track"
  const val FALLBACK_ROOM = "this Portal"

  private const val MS_PER_SECOND = 1_000L
  private const val SECONDS_PER_MINUTE = 60L
  private const val SECONDS_PER_HOUR = 3_600L

  /**
   * [FIRST_RUN] only when no session has ever been seen and none is active now; an active
   * track always wins (it proves a session exists even if the flag has not caught up).
   */
  fun screenState(state: NowPlayingState?, hasEverSeenSession: Boolean): MusicScreenState =
      when {
        state != null && state.active && state.state == PlaybackState.PLAYING ->
            MusicScreenState.PLAYING
        state != null && state.active -> MusicScreenState.PAUSED
        hasEverSeenSession -> MusicScreenState.NOTHING_PLAYING
        else -> MusicScreenState.FIRST_RUN
      }

  /**
   * Position extrapolated from the last session report. Only advances while [playing] and
   * when the report carries a monotonic stamp; never rewinds if the clock reads earlier
   * than the stamp. Clamped to `[0, durationMs]` (no upper clamp for unknown duration).
   */
  fun livePositionMs(
      positionMs: Long,
      positionUpdatedElapsedMs: Long,
      nowElapsedMs: Long,
      durationMs: Long,
      playing: Boolean,
  ): Long {
    val elapsed =
        if (playing && positionUpdatedElapsedMs > 0L) {
          (nowElapsedMs - positionUpdatedElapsedMs).coerceAtLeast(0L)
        } else 0L
    val raw = (positionMs + elapsed).coerceAtLeast(0L)
    return if (durationMs > 0L) raw.coerceAtMost(durationMs) else raw
  }

  fun livePositionMs(state: NowPlayingState, nowElapsedMs: Long): Long =
      livePositionMs(state.positionMs, state.positionUpdatedElapsedMs, nowElapsedMs,
          state.durationMs, playing = state.state == PlaybackState.PLAYING)

  /** `m:ss` under an hour, `h:mm:ss` from an hour; negative reads as `0:00`. */
  fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / MS_PER_SECOND
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
  }

  /** A draggable knob only when the session can seek and the track has a known length. */
  fun showSeekKnob(canSeek: Boolean, durationMs: Long): Boolean = canSeek && durationMs > 0L

  fun progressFraction(positionMs: Long, durationMs: Long): Float =
      if (durationMs <= 0L) 0f
      else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

  fun seekTargetMs(fraction: Float, durationMs: Long): Long {
    if (fraction.isNaN() || durationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong()
  }

  fun statusLine(playing: Boolean, roomName: String): String {
    val room = roomName.trim().ifEmpty { FALLBACK_ROOM }
    return if (playing) "Playing on $room" else "Paused on $room"
  }

  fun displayTitle(title: String): String = title.trim().ifEmpty { UNKNOWN_TITLE }
}
