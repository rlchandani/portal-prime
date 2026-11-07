/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicTabsTest {
  @Test fun libraryAndSearch_haveFixedUrls() {
    assertEquals("https://music.youtube.com/library", YtMusicTab.LIBRARY.targetUrl(null))
    assertEquals("https://music.youtube.com/search", YtMusicTab.SEARCH.targetUrl(null))
    assertEquals("https://music.youtube.com/library",
        YtMusicTab.LIBRARY.targetUrl("https://music.youtube.com/watch?v=abc"))
  }

  @Test fun queue_keepsCurrentPlayerPage() {
    val watch = "https://music.youtube.com/watch?v=abc123&list=RDAMVMabc123"
    assertEquals(watch, YtMusicTab.QUEUE.targetUrl(watch))
  }

  @Test fun queue_withoutPlayerPage_opensHome() {
    assertEquals("https://music.youtube.com/", YtMusicTab.QUEUE.targetUrl(null))
    assertEquals("https://music.youtube.com/", YtMusicTab.QUEUE.targetUrl("https://music.youtube.com/library"))
    assertEquals("https://music.youtube.com/",
        YtMusicTab.QUEUE.targetUrl("https://evil.example/watch?v=abc"))
    assertEquals("https://music.youtube.com/",
        YtMusicTab.QUEUE.targetUrl("http://music.youtube.com/watch?v=abc"))
  }

  @Test fun tabsHaveLabelsAndInPageKeys() {
    assertEquals(listOf("Queue", "Library", "Search"), YtMusicTab.entries.map { it.label })
    assertEquals(listOf("queue", "library", "search"), YtMusicTab.entries.map { it.pageKey })
  }

  @Test fun tabForUrl_reflectsThePage() {
    assertEquals(YtMusicTab.QUEUE, YtMusicTab.forUrl("https://music.youtube.com/watch?v=a"))
    assertEquals(YtMusicTab.LIBRARY, YtMusicTab.forUrl("https://music.youtube.com/library/playlists"))
    assertEquals(YtMusicTab.SEARCH, YtMusicTab.forUrl("https://music.youtube.com/search?q=tide"))
    assertEquals(null, YtMusicTab.forUrl("https://music.youtube.com/"))
    assertEquals(null, YtMusicTab.forUrl("https://evil.example/library"))
  }

  @Test fun paneAllowsOnlyHttpsYouTubeAndGoogleSignIn() {
    assertTrue(YtMusicUrls.isAllowedInPane("https://music.youtube.com/library"))
    assertTrue(YtMusicUrls.isAllowedInPane("https://accounts.google.com/v3/signin/identifier?x=1"))
    assertTrue(YtMusicUrls.isAllowedInPane("https://consent.youtube.com/m?continue=x"))
    assertTrue(YtMusicUrls.isAllowedInPane("https://www.youtube.com/signin?action=handle_signin"))
    assertTrue(YtMusicUrls.isAllowedInPane("https://accounts.youtube.com/accounts/SetSID"))
    assertFalse(YtMusicUrls.isAllowedInPane("http://music.youtube.com/"))
    assertFalse(YtMusicUrls.isAllowedInPane("https://evil.example/"))
    assertFalse(YtMusicUrls.isAllowedInPane("https://youtube.com.evil.example/"))
    assertFalse(YtMusicUrls.isAllowedInPane("intent://scan/#Intent;scheme=zxing;end"))
    assertFalse(YtMusicUrls.isAllowedInPane(null))
  }

  @Test fun hostLabel_forTheEmbeddedCaption() {
    assertEquals("music.youtube.com, player page", YtMusicUrls.caption("https://music.youtube.com/watch?v=a"))
    assertEquals("music.youtube.com", YtMusicUrls.caption("https://music.youtube.com/library"))
    assertEquals("accounts.google.com", YtMusicUrls.caption("https://accounts.google.com/signin/rejected"))
    assertEquals("music.youtube.com", YtMusicUrls.caption(null))
  }
}
