/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtLoaded
import com.immortal.launcher.youtube.YtProblem
import com.immortal.launcher.youtube.YtResult
import com.immortal.launcher.youtube.YtSearchPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

class MusicV3DataModelTest {
  private val track = YtDataTrack("aaaaaaaaaaa", "Harbour lights (Official Audio)", "Marrow & Vale", null)

  @Test fun libraryResultsMapToLoadStates() {
    assertEquals(MusicV3Load.Loading, MusicV3Load.from<List<String>>(null))
    assertEquals(MusicV3Load.Ready(listOf("a")), MusicV3Load.from(YtResult.Ok(YtLoaded(listOf("a"), 0, true))))
    assertEquals(MusicV3Load.Problem(MusicV3Load.OFFLINE), MusicV3Load.from<Int>(YtResult.Offline))
    assertEquals(MusicV3Load.Problem(MusicV3Load.SIGN_IN_AGAIN, signInAgain = true),
        MusicV3Load.from<Int>(YtResult.NeedsSignIn))
    assertEquals(MusicV3Load.Problem(MusicV3Load.RESTING), MusicV3Load.from<Int>(YtResult.Resting))
    assertEquals(MusicV3Load.Problem(MusicV3Load.FAILED), MusicV3Load.from<Int>(YtResult.Failed(500, YtProblem.SERVER)))
  }

  @Test fun resultsAreRememberedAsRecent() {
    val s = MusicV3SearchState(remaining = 50).searching("harbour")
      .answered("harbour", YtResult.Ok(YtSearchPage(listOf(track), 8, fromCache = false)), 8)
    assertEquals(MusicV3SearchOutcome.Results("harbour", listOf(track)), s.outcome)
    assertEquals(listOf("harbour"), s.recent)
    assertEquals("8 searches left today", s.hint)
    assertEquals("Songs for “harbour”", s.resultsLabel("harbour"))
  }

  @Test fun emptyResultsSayNoSongsFound() {
    val s = MusicV3SearchState().answered("zzz", YtResult.Ok(YtSearchPage(emptyList(), 90, false)), 90)
    assertEquals(MusicV3SearchOutcome.NoResults("zzz"), s.outcome)
    assertNull(s.hint)
  }

  @Test fun runningOutRestsInsteadOfFailing() {
    val s = MusicV3SearchState(recent = listOf("abba"), remaining = 1).answered("new", YtResult.Resting, 0)
    assertTrue(s.resting)
    assertEquals(MusicV3SearchOutcome.Idle, s.outcome)
    assertEquals(listOf("abba"), s.recent)
    assertNull(s.hint)
    assertEquals("Saved results for “abba”", s.resultsLabel("abba"))
  }

  @Test fun savedResultsStillOpenWhileResting() {
    val s = MusicV3SearchState(remaining = 0)
        .answered("abba", YtResult.Ok(YtSearchPage(listOf(track), 0, fromCache = true)), 0)
    assertTrue(s.resting)
    assertEquals(MusicV3SearchOutcome.Results("abba", listOf(track)), s.outcome)
  }

  @Test fun offlineHidesTheHint() {
    val s = MusicV3SearchState(remaining = 5).answered("x y", YtResult.Offline, 5)
    assertEquals(MusicV3SearchOutcome.Offline, s.outcome)
    assertNull(s.hint)
  }

  @Test fun otherFailuresSayYouTubeDidNotAnswer() {
    val s = MusicV3SearchState().answered("abc", YtResult.Failed(403, YtProblem.FORBIDDEN), 40)
    assertEquals(MusicV3SearchOutcome.Failed, s.outcome)
    assertFalse(s.resting)
  }

  @Test fun libraryNavBackLeavesTheChip() {
    val nav = MusicV3LibraryNav(MusicV3LibraryTab.ARTISTS, artist = com.immortal.launcher.youtube.YtArtist("c", "n", null))
    assertTrue(nav.inDetail)
    assertEquals(MusicV3LibraryNav(MusicV3LibraryTab.ARTISTS), nav.back())
    assertEquals(MusicV3LibraryTab.PLAYLISTS, MusicV3LibraryNav().tab)
  }

  @Test fun suggestionsShowWhileTypingSomethingNotYetSearched() {
    val empty = MusicV3SearchState()
    assertTrue(empty.fieldEmpty)
    assertFalse(empty.showsSuggestions)
    val typing = empty.copy(query = "ade")
    assertTrue(typing.showsSuggestions)
    val searched = typing.searching("adele ")
    assertFalse(searched.showsSuggestions)
    assertTrue(searched.copy(query = "adele b").showsSuggestions)
    assertFalse(searched.copy(query = "   ").showsSuggestions)
  }
}
