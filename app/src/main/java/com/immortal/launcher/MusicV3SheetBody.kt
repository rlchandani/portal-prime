/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtAccount
import com.immortal.launcher.youtube.YtResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the sheet's tabs need; the stage owns all of it. */
internal data class MusicV3BodyInputs(
    val library: YouTubeMusicLibrary,
    val active: YtAccount?,
    val guests: Boolean,
    val queue: YtQueue,
    val actions: MusicV3Actions,
    val nav: MusicV3LibraryNav,
    val onNav: (MusicV3LibraryNav) -> Unit,
    /** Hoisted so switching tabs keeps the shown results (a new search would cost budget). */
    val search: MutableState<MusicV3SearchState>,
    val onSignInAgain: () -> Unit,
    val onMove: (() -> Unit)?,
    val onOpenLibrary: () -> Unit,
    val onSearchFocus: (Boolean) -> Unit,
    /** The sheet is open on Search: focus the field. */
    val searchAutoFocus: Boolean,
)

private const val GUEST_NOTE = "Your music library is hidden while guests are here. Playback controls still work."

/** The current tab's content. Guests and no-account still get Up next (playback stays). */
@Composable
internal fun MusicV3SheetBody(tab: MusicSheetTab, inputs: MusicV3BodyInputs) {
  val active = inputs.active
  when {
    tab == MusicSheetTab.UP_NEXT -> MusicV3QueuePane(inputs.queue, inputs.onMove, inputs.onOpenLibrary)
    inputs.guests -> GuestHiddenNote(GUEST_NOTE)
    active == null -> MusicV3GuestPane(inputs.library.isConfigured, inputs.onSignInAgain)
    tab == MusicSheetTab.LIBRARY -> MusicV3LibraryPane(inputs.library, active.id, inputs.nav, inputs.onNav, inputs.actions,
        inputs.onSignInAgain)
    tab == MusicSheetTab.RECENT -> MusicV3RecentTab(inputs.actions)
    else -> SearchTab(inputs)
  }
}

@Composable
private fun SearchTab(inputs: MusicV3BodyInputs) {
  val library = inputs.library
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val context = LocalContext.current
  var search by inputs.search
  LaunchedEffect(Unit) {
    val (left, recent) = withContext(Dispatchers.IO) {
      library.searchesRemainingToday() to MusicLocalSearch.recent(context)
    }
    search = search.copy(remaining = left, recent = recent.take(MusicV3Rules.MAX_RECENT_SEARCHES).map { it.query })
  }
  val suggestions = rememberSuggestions(search)
  MusicV3SearchPane(search, suggestions, onQuery = { search = search.copy(query = it) }, actions = inputs.actions,
      onFocus = inputs.onSearchFocus, autoFocus = inputs.searchAutoFocus, onSubmit = { q ->
        keyboard?.hide()
        search = search.searching(q)
        scope.launch {
          val result = library.search(q)
          val left = withContext(Dispatchers.IO) { library.searchesRemainingToday() }
          search = search.answered(q, result, left)
          if (result is YtResult.Ok) MusicLocalSearch.remember(context, q)
        }
      })
}

/** Local suggestions for what is being typed, debounced ([MusicSuggest.DEBOUNCE_MS]); no quota. */
@Composable
private fun rememberSuggestions(search: MusicV3SearchState): List<MusicSuggestion> {
  val context = LocalContext.current
  var suggestions by remember { mutableStateOf(emptyList<MusicSuggestion>()) }
  val query = search.query.takeIf { search.showsSuggestions }
  LaunchedEffect(query) {
    if (query == null) return@LaunchedEffect
    delay(MusicSuggest.DEBOUNCE_MS)
    suggestions = withContext(Dispatchers.IO) { MusicLocalSearch.suggest(context, query) }
  }
  return if (query == null) emptyList() else suggestions
}
