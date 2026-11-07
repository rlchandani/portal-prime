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
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicSearchTest {
  @Test fun normalize_trimsCollapsesAndStripsControls() {
    assertEquals("rain on a tin roof", YtMusicSearch.normalize("  rain\ton\n a \u0000tin​ roof  "))
    assertEquals("a b", YtMusicSearch.normalize("a b"))
  }

  @Test fun normalize_blankIsNull() {
    assertNull(YtMusicSearch.normalize(null))
    assertNull(YtMusicSearch.normalize(""))
    assertNull(YtMusicSearch.normalize(" \t\n\u0007 "))
  }

  @Test fun normalize_capsLength() {
    val q = YtMusicSearch.normalize("x".repeat(500))!!
    assertEquals(YtMusicSearch.MAX_QUERY_CHARS, q.length)
  }

  @Test fun url_encodesQuery() {
    assertEquals("https://music.youtube.com/search?q=kids%20dance%20%26%20party%3F",
        YtMusicSearch.url("kids dance & party?"))
    assertEquals("https://music.youtube.com/search?q=Bj%C3%B6rk", YtMusicSearch.url("Björk"))
    assertNull(YtMusicSearch.url("   "))
  }

  @Test fun script_quotesQuerySafely() {
    val js = YtMusicSearch.script("a'b\"c\\d</script>")!!
    assertTrue(js.startsWith("window.__portalYtm?window.__portalYtm.search(\""))
    assertTrue(js.endsWith("):false;"))
    assertFalse("single quotes cannot break out", js.contains("search('"))
    assertTrue(js.contains("\\\"c"))
    assertTrue(js.contains("\\\\d"))
    assertNull(YtMusicSearch.script(""))
  }

  @Test fun queryFromUrl_readsOnlyMusicSearchPages() {
    assertEquals("kids dance party",
        YtMusicSearch.queryFromUrl("https://music.youtube.com/search?q=kids+dance%20party"))
    assertNull(YtMusicSearch.queryFromUrl("https://music.youtube.com/library?q=x"))
    assertNull(YtMusicSearch.queryFromUrl("https://evil.example.com/search?q=x"))
    assertNull(YtMusicSearch.queryFromUrl("https://music.youtube.com/search"))
    assertNull(YtMusicSearch.queryFromUrl(null))
    assertNull(YtMusicSearch.queryFromUrl("https://music.youtube.com/search?q=%E0%A4%A"))
  }

  @Test fun sameQuery_ignoresCaseAndSpacing() {
    assertTrue(YtMusicSearch.sameQuery("Rain  On a tin roof", "rain on a tin roof"))
    assertFalse(YtMusicSearch.sameQuery("rain", "roof"))
    assertFalse(YtMusicSearch.sameQuery(null, "rain"))
  }
}
