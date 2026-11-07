/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs

/** How the screensaver now-playing card presents across the room. */
enum class AmbientMusicCardVisibility { VISIBLE_PLAYING, VISIBLE_PAUSED_DIM, HIDDEN }

/** What a completed tap means for the card (decided from the host's touch stream). */
enum class AmbientMusicCardTap { PASS_THROUGH, REVEAL_CONTROLS, KEEP_CONTROLS, HIDE_CONTROLS }

enum class AmbientMusicCommand { PLAY, PAUSE, NEXT, PREVIOUS }

/**
 * Pure decisions for the ambient music card over the photo screensaver. All times are on the
 * monotonic elapsed-realtime clock, the same clock as [NowPlayingState.positionUpdatedElapsedMs].
 * No Android types, so it is JVM-tested in isolation; [FaceRenderer] only applies the result.
 */
object AmbientMusicCardPolicy {
  /** A paused card leaves the frame after this long without a playback/track change. */
  const val PAUSED_LEAVES_FRAME_AFTER_MS = 10 * 60_000L

  /** Walk-up controls hide this long after the last touch on them. */
  const val CONTROLS_TIMEOUT_MS = 8_000L

  /** The only live signal is the progress line; it advances once per second while playing. */
  const val PROGRESS_TICK_MS = 1_000L

  /** Same tap slop as [PhotoFrameController.onTouch], so a tap means the same thing everywhere. */
  const val TAP_SLOP_PX = 48f

  private const val PAUSED_PREFIX = "Paused"

  fun visibility(
      state: NowPlayingState?,
      nowElapsedMs: Long,
      stateChangedAtElapsedMs: Long,
      controlsRevealed: Boolean = false,
  ): AmbientMusicCardVisibility {
    if (state == null || state.title.isBlank()) return AmbientMusicCardVisibility.HIDDEN
    return when (state.state) {
      PlaybackState.PLAYING -> AmbientMusicCardVisibility.VISIBLE_PLAYING
      PlaybackState.PAUSED ->
          if (controlsRevealed || pausedFor(nowElapsedMs, stateChangedAtElapsedMs) < PAUSED_LEAVES_FRAME_AFTER_MS)
              AmbientMusicCardVisibility.VISIBLE_PAUSED_DIM
          else AmbientMusicCardVisibility.HIDDEN
      PlaybackState.STOPPED, PlaybackState.IDLE -> AmbientMusicCardVisibility.HIDDEN
    }
  }

  /**
   * When the card's playback state last changed. Position-only updates keep [previousChangedAt];
   * a new playback state or track restarts it. On first sight of an already-paused track the
   * session's own position stamp (when it is in the past) dates the pause more truthfully.
   */
  fun stateChangedAt(
      previous: NowPlayingState?,
      next: NowPlayingState?,
      previousChangedAt: Long,
      nowElapsedMs: Long,
  ): Long {
    if (previous != null && next != null && sameMoment(previous, next)) return previousChangedAt
    if (previous == null && next != null && next.state == PlaybackState.PAUSED &&
        next.positionUpdatedElapsedMs in 1..nowElapsedMs) return next.positionUpdatedElapsedMs
    return nowElapsedMs
  }

  /** Progress in [0, 1], interpolated while playing; null when the track has no known length. */
  fun progressFraction(state: NowPlayingState?, nowElapsedMs: Long): Float? {
    if (state == null || state.durationMs <= 0L) return null
    val stamp = state.positionUpdatedElapsedMs
    val advanced =
        if (state.state == PlaybackState.PLAYING && stamp > 0L && nowElapsedMs > stamp)
            nowElapsedMs - stamp
        else 0L
    val position = (state.positionMs + advanced).coerceIn(0L, state.durationMs)
    return position.toFloat() / state.durationMs.toFloat()
  }

  fun controlsVisible(revealedAtElapsedMs: Long?, nowElapsedMs: Long): Boolean =
      revealedAtElapsedMs != null && nowElapsedMs - revealedAtElapsedMs < CONTROLS_TIMEOUT_MS

  /**
   * Delay until the card next needs re-evaluating, or null when nothing will change on its own:
   * the 1s progress tick while playing a track with a length, the moment a paused card is due to
   * leave, and the moment revealed controls time out — whichever is soonest.
   */
  fun nextRefreshDelayMs(
      state: NowPlayingState?,
      nowElapsedMs: Long,
      stateChangedAtElapsedMs: Long,
      controlsRevealedAtElapsedMs: Long?,
  ): Long? {
    val revealed = controlsVisible(controlsRevealedAtElapsedMs, nowElapsedMs)
    val visibility = visibility(state, nowElapsedMs, stateChangedAtElapsedMs, revealed)
    if (visibility == AmbientMusicCardVisibility.HIDDEN) return null
    val candidates = buildList {
      if (visibility == AmbientMusicCardVisibility.VISIBLE_PLAYING && (state?.durationMs ?: 0L) > 0L)
        add(PROGRESS_TICK_MS)
      if (revealed && controlsRevealedAtElapsedMs != null)
        add(controlsRevealedAtElapsedMs + CONTROLS_TIMEOUT_MS - nowElapsedMs)
      else if (visibility == AmbientMusicCardVisibility.VISIBLE_PAUSED_DIM)
        add(PAUSED_LEAVES_FRAME_AFTER_MS - pausedFor(nowElapsedMs, stateChangedAtElapsedMs))
    }
    return candidates.minOrNull()?.coerceAtLeast(1L)
  }

  fun isTap(downX: Float, downY: Float, upX: Float, upY: Float): Boolean =
      abs(upX - downX) < TAP_SLOP_PX && abs(upY - downY) < TAP_SLOP_PX

  /** Tap on the card reveals controls; with controls up, a tap elsewhere only hides them. */
  fun routeTap(cardVisible: Boolean, controlsVisible: Boolean, onCard: Boolean): AmbientMusicCardTap =
      when {
        !cardVisible -> AmbientMusicCardTap.PASS_THROUGH
        controlsVisible -> if (onCard) AmbientMusicCardTap.KEEP_CONTROLS else AmbientMusicCardTap.HIDE_CONTROLS
        onCard -> AmbientMusicCardTap.REVEAL_CONTROLS
        else -> AmbientMusicCardTap.PASS_THROUGH
      }

  fun playPauseCommand(state: NowPlayingState?): AmbientMusicCommand =
      if (state?.state == PlaybackState.PLAYING) AmbientMusicCommand.PAUSE else AmbientMusicCommand.PLAY

  /** Send [command] to [transport]; false when there is no transport or the command failed. */
  fun send(command: AmbientMusicCommand, transport: MusicTransport?): Boolean {
    if (transport == null) return false
    return runCatching {
      when (command) {
        AmbientMusicCommand.PLAY -> transport.play()
        AmbientMusicCommand.PAUSE -> transport.pause()
        AmbientMusicCommand.NEXT -> transport.next()
        AmbientMusicCommand.PREVIOUS -> transport.previous()
      }
    }.isSuccess
  }

  /** Secondary line: the artist, prefixed with "Paused" while paused. */
  fun subtitle(state: NowPlayingState): String {
    if (state.state != PlaybackState.PAUSED) return state.artist
    return if (state.artist.isBlank()) PAUSED_PREFIX else "$PAUSED_PREFIX · ${state.artist}"
  }

  private fun pausedFor(nowElapsedMs: Long, changedAt: Long): Long = (nowElapsedMs - changedAt).coerceAtLeast(0L)

  private fun sameMoment(a: NowPlayingState, b: NowPlayingState): Boolean =
      a.state == b.state && a.mediaId == b.mediaId && a.title == b.title && a.artist == b.artist
}
