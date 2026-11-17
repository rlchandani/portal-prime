/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What may be drawn over the visible YouTube player, and what that does to playback (YouTube's rule:
 * the player plays only while nothing covers, overlaps or tints it).
 *
 * - The full-screen alert cards (Call everyone / dinner, timer done, doorbell, the follow-me ask)
 *   and Home's dialogs and sheets COVER it: music pauses while one shows; when the last one closes it
 *   resumes by itself only if it was playing when covered. Any pause meanwhile (or leaving the
 *   screen) cancels that, so a later Play is the user's.
 * - Everything smaller AVOIDS it instead: Home's toasts centre in the space left of the card, and a
 *   bottom-anchored notification toast moves to the top while the card shows the player
 *   ([avoidBottom]).
 *
 * The sleep timer survives a cover (it is not the user's pause). Pure.
 */
internal object MusicCoverRules {
  /** Reduces [YtAction.Covered]. */
  fun reduce(s: YtEngineState, on: Boolean): YtStep {
    if (on == s.covered) return YtStep(s)
    return if (on) cover(s) else uncover(s)
  }

  private fun cover(s: YtEngineState): YtStep {
    val active = s.ready && (s.isPlaying || s.wantsPlay)
    val covered = s.copy(covered = true, wantsPlay = false, resumeAfterCover = s.foreground && active)
    return YtStep(covered, if (active) listOf(YtEffect.Pause, YtEffect.Persist) else emptyList())
  }

  private fun uncover(s: YtEngineState): YtStep {
    val resume = s.resumeAfterCover || s.playWhenVisible
    val open = s.copy(covered = false, resumeAfterCover = false)
    if (!resume || !open.canPlay) return YtStep(open)
    return YtEngineReducer.play(open.copy(playWhenVisible = false))
  }

  /**
   * True when a full-width bottom toast would land on the player: Home's card sits bottom-right above
   * the dock. The Music screen places its own toasts.
   */
  fun avoidBottom(shownIn: MusicPlayerHostKind?): Boolean = shownIn == MusicPlayerHostKind.HOME_CARD
}

/** Who is drawing over the player. Several can at once; the player is covered while any is. */
enum class MusicCover { DINNER, TIMER, DOORBELL, FOLLOW_ASK, HOME_DIALOG }
