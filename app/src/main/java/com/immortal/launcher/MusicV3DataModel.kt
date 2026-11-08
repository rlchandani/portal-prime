/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtLoaded
import com.immortal.launcher.youtube.YtResult
import com.immortal.launcher.youtube.YtSearchPage
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** A library list as the UI shows it. */
internal sealed interface MusicV3Load<out T> {
  data object Loading : MusicV3Load<Nothing>
  data class Ready<T>(val value: T) : MusicV3Load<T>
  data class Problem(val message: MusicV3Message, val signInAgain: Boolean = false) : MusicV3Load<Nothing>

  companion object {
    val OFFLINE = MusicV3Message("This Portal is offline",
        "Your library comes back as soon as it reconnects.")
    val SIGN_IN_AGAIN = MusicV3Message("Sign in again to see your library",
        "Google ended this Portal's access. Nothing changed in the account.")
    val RESTING = MusicV3Message("YouTube's daily limit is used up", "Your library comes back tomorrow.")
    val FAILED = MusicV3Message("YouTube didn't answer", "Try again in a minute.")

    fun <T> from(result: YtResult<YtLoaded<T>>?): MusicV3Load<T> = when (result) {
      null -> Loading
      is YtResult.Ok -> Ready(result.value.value)
      YtResult.Offline -> Problem(OFFLINE)
      YtResult.NeedsSignIn -> Problem(SIGN_IN_AGAIN, signInAgain = true)
      YtResult.Resting -> Problem(RESTING)
      is YtResult.Failed -> Problem(FAILED)
    }
  }
}

/** What the Search pane shows below the field. */
internal sealed interface MusicV3SearchOutcome {
  data object Idle : MusicV3SearchOutcome
  data class Searching(val query: String) : MusicV3SearchOutcome
  data class Results(val query: String, val tracks: List<YtDataTrack>) : MusicV3SearchOutcome
  data class NoResults(val query: String) : MusicV3SearchOutcome
  data object Offline : MusicV3SearchOutcome
  data object Failed : MusicV3SearchOutcome
}

/** Immutable Search pane state; [remaining] is today's network-search budget. */
internal data class MusicV3SearchState(
    val query: String = "",
    val recent: List<String> = emptyList(),
    val outcome: MusicV3SearchOutcome = MusicV3SearchOutcome.Idle,
    val remaining: Int = Int.MAX_VALUE,
) {
  val resting: Boolean get() = MusicV3Rules.searchResting(remaining)
  val hint: String? get() = if (outcome == MusicV3SearchOutcome.Offline) null else MusicV3Rules.searchHint(remaining)

  fun searching(q: String): MusicV3SearchState = copy(query = q, outcome = MusicV3SearchOutcome.Searching(q.trim()))

  /** Applies one search answer. Resting keeps the shown results (saved results stay usable). */
  fun answered(q: String, result: YtResult<YtSearchPage>, remainingNow: Int): MusicV3SearchState {
    val query = q.trim()
    return when (result) {
      is YtResult.Ok -> copy(
          recent = MusicV3Rules.withRecent(recent, query),
          remaining = result.value.remainingToday,
          outcome = if (result.value.tracks.isEmpty()) MusicV3SearchOutcome.NoResults(query)
          else MusicV3SearchOutcome.Results(query, result.value.tracks))
      // Not cached and the budget is spent: the resting notice shows, recent chips still work.
      YtResult.Resting -> copy(remaining = 0, outcome = MusicV3SearchOutcome.Idle)
      YtResult.Offline -> copy(remaining = remainingNow, outcome = MusicV3SearchOutcome.Offline)
      YtResult.NeedsSignIn, is YtResult.Failed -> copy(remaining = remainingNow, outcome = MusicV3SearchOutcome.Failed)
    }
  }

  /** Label above the result list ("Songs for …", or "Saved results for …" while resting). */
  fun resultsLabel(query: String): String =
      if (resting) "Saved results for “$query”" else "Songs for “$query”"
}
