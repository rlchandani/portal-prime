/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What the user chose in the music pane. Lists themselves come from [YtMusicWebHost.lists]. */
data class YtMusicPaneState(
    val tab: YtMusicTab = YtMusicTab.QUEUE,
    /** Last search the user submitted from our own search box. */
    val submittedQuery: String? = null,
    /** The "Show YouTube page" escape hatch (e.g. to sign in) is revealing the real page. */
    val showPage: Boolean = false,
)

sealed interface YtMusicPaneEvent {
  data class SelectTab(val tab: YtMusicTab) : YtMusicPaneEvent
  data class SubmitSearch(val raw: String) : YtMusicPaneEvent
  data object TogglePage : YtMusicPaneEvent
}

/** What the native list area shows for the selected tab. */
sealed interface YtMusicListView {
  data object Loading : YtMusicListView
  data object SignedOut : YtMusicListView
  data object SearchPrompt : YtMusicListView
  data class Empty(val message: String) : YtMusicListView
  data class Items(val list: YtMusicList, val header: String?) : YtMusicListView
}

/** Pure reducer + view selection for the pane (JVM-tested). */
object YtMusicPaneReducer {
  const val QUEUE_EMPTY = "Play something to see Up next"
  const val QUEUE_HEADER = "Up next"
  const val LIBRARY_EMPTY = "Nothing in your library yet"

  fun reduce(state: YtMusicPaneState, event: YtMusicPaneEvent): YtMusicPaneState = when (event) {
    is YtMusicPaneEvent.SelectTab -> state.copy(tab = event.tab, showPage = false)
    is YtMusicPaneEvent.TogglePage -> state.copy(showPage = !state.showPage)
    is YtMusicPaneEvent.SubmitSearch -> YtMusicSearch.normalize(event.raw)
        ?.let { state.copy(tab = YtMusicTab.SEARCH, submittedQuery = it, showPage = false) }
        ?: state
  }

  /** [pageReady] is true once the hidden page has finished loading. */
  fun view(state: YtMusicPaneState, lists: Map<YtMusicTab, YtMusicList>, pageReady: Boolean): YtMusicListView {
    val list = lists[state.tab]
    return when (state.tab) {
      YtMusicTab.QUEUE -> when {
        list == null -> if (pageReady) YtMusicListView.Empty(QUEUE_EMPTY) else YtMusicListView.Loading
        list.items.isEmpty() -> YtMusicListView.Empty(QUEUE_EMPTY)
        else -> YtMusicListView.Items(list, QUEUE_HEADER)
      }
      YtMusicTab.LIBRARY -> when {
        list == null || !list.onPage -> YtMusicListView.Loading
        list.signedOut -> YtMusicListView.SignedOut
        list.items.isEmpty() -> YtMusicListView.Empty(LIBRARY_EMPTY)
        else -> YtMusicListView.Items(list, null)
      }
      YtMusicTab.SEARCH -> {
        val query = state.submittedQuery
        when {
          query == null -> YtMusicListView.SearchPrompt
          list == null || !list.onPage || !YtMusicSearch.sameQuery(list.query, query) -> YtMusicListView.Loading
          list.items.isEmpty() -> YtMusicListView.Empty("No results for “$query”")
          else -> YtMusicListView.Items(list, null)
        }
      }
    }
  }
}
