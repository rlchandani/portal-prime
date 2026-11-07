/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicPaneStateTest {
  private val song = YtMusicListItem(0, "Low tide, early", "Marrow & Vale", "4:12", "", true,
      YtMusicItemKind.SONG, playable = true)

  private fun list(tab: YtMusicTab, items: List<YtMusicListItem> = listOf(song), onPage: Boolean = true,
      signedOut: Boolean = false, query: String = "") =
    YtMusicList(tab, generation = 1, items = items, onPage = onPage, signedOut = signedOut, query = query)

  private val reducer = YtMusicPaneReducer

  @Test fun initialState_isQueueWithListShown() {
    val s = YtMusicPaneState()
    assertEquals(YtMusicTab.QUEUE, s.tab)
    assertNull(s.submittedQuery)
    assertFalse(s.showPage)
  }

  @Test fun selectTab_switchesAndHidesThePage() {
    val s = reducer.reduce(YtMusicPaneState(showPage = true), YtMusicPaneEvent.SelectTab(YtMusicTab.LIBRARY))
    assertEquals(YtMusicTab.LIBRARY, s.tab)
    assertFalse(s.showPage)
  }

  @Test fun togglePage_flips() {
    val shown = reducer.reduce(YtMusicPaneState(), YtMusicPaneEvent.TogglePage)
    assertTrue(shown.showPage)
    assertFalse(reducer.reduce(shown, YtMusicPaneEvent.TogglePage).showPage)
  }

  @Test fun submitSearch_normalizesAndSelectsSearch() {
    val s = reducer.reduce(YtMusicPaneState(showPage = true), YtMusicPaneEvent.SubmitSearch("  Björk  "))
    assertEquals(YtMusicTab.SEARCH, s.tab)
    assertEquals("Björk", s.submittedQuery)
    assertFalse(s.showPage)
  }

  @Test fun submitSearch_blankIsIgnored() {
    val before = YtMusicPaneState(tab = YtMusicTab.SEARCH, submittedQuery = "rain")
    assertSame(before, reducer.reduce(before, YtMusicPaneEvent.SubmitSearch("   ")))
  }

  @Test fun queue_views() {
    val s = YtMusicPaneState()
    assertEquals(YtMusicListView.Loading, reducer.view(s, emptyMap(), pageReady = false))
    assertEquals(YtMusicListView.Empty(YtMusicPaneReducer.QUEUE_EMPTY), reducer.view(s, emptyMap(), pageReady = true))
    assertEquals(YtMusicListView.Empty(YtMusicPaneReducer.QUEUE_EMPTY),
        reducer.view(s, mapOf(YtMusicTab.QUEUE to list(YtMusicTab.QUEUE, emptyList())), pageReady = true))
    val items = reducer.view(s, mapOf(YtMusicTab.QUEUE to list(YtMusicTab.QUEUE)), pageReady = true)
    assertEquals(YtMusicListView.Items(list(YtMusicTab.QUEUE), YtMusicPaneReducer.QUEUE_HEADER), items)
  }

  @Test fun library_views() {
    val s = YtMusicPaneState(tab = YtMusicTab.LIBRARY)
    assertEquals(YtMusicListView.Loading, reducer.view(s, emptyMap(), pageReady = true))
    assertEquals(YtMusicListView.Loading,
        reducer.view(s, mapOf(YtMusicTab.LIBRARY to list(YtMusicTab.LIBRARY, onPage = false)), true))
    assertEquals(YtMusicListView.SignedOut,
        reducer.view(s, mapOf(YtMusicTab.LIBRARY to list(YtMusicTab.LIBRARY, emptyList(), signedOut = true)), true))
    assertEquals(YtMusicListView.Empty(YtMusicPaneReducer.LIBRARY_EMPTY),
        reducer.view(s, mapOf(YtMusicTab.LIBRARY to list(YtMusicTab.LIBRARY, emptyList())), true))
    assertEquals(YtMusicListView.Items(list(YtMusicTab.LIBRARY), null),
        reducer.view(s, mapOf(YtMusicTab.LIBRARY to list(YtMusicTab.LIBRARY)), true))
  }

  @Test fun library_signedOutWins_evenWithCards() {
    // Signed-out library pages can still show promo cards; the honest state is "sign in".
    val s = YtMusicPaneState(tab = YtMusicTab.LIBRARY)
    assertEquals(YtMusicListView.SignedOut,
        reducer.view(s, mapOf(YtMusicTab.LIBRARY to list(YtMusicTab.LIBRARY, signedOut = true)), true))
  }

  @Test fun search_views() {
    val prompt = YtMusicPaneState(tab = YtMusicTab.SEARCH)
    assertEquals(YtMusicListView.SearchPrompt, reducer.view(prompt, emptyMap(), true))
    val s = prompt.copy(submittedQuery = "rain")
    assertEquals(YtMusicListView.Loading, reducer.view(s, emptyMap(), true))
    // A stale list for an earlier query never shows under the new one.
    assertEquals(YtMusicListView.Loading,
        reducer.view(s, mapOf(YtMusicTab.SEARCH to list(YtMusicTab.SEARCH, query = "roof")), true))
    assertEquals(YtMusicListView.Loading,
        reducer.view(s, mapOf(YtMusicTab.SEARCH to list(YtMusicTab.SEARCH, onPage = false, query = "rain")), true))
    assertEquals(YtMusicListView.Empty("No results for “rain”"),
        reducer.view(s, mapOf(YtMusicTab.SEARCH to list(YtMusicTab.SEARCH, emptyList(), query = "Rain")), true))
    val found = list(YtMusicTab.SEARCH, query = "rain")
    assertEquals(YtMusicListView.Items(found, null),
        reducer.view(s, mapOf(YtMusicTab.SEARCH to found), true))
  }
}
