/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** YouTube-style search completions ("heart attack" → "heart attack demi lovato") merged with local matches. */
class YtQueryCompletionsTest {
  @Test fun parsesTheSuggestReply() {
    val reply = """["heart attack",["heart attack","heart attack demi lovato","heart attack lyrics"],[],{}]"""
    assertEquals(listOf("heart attack", "heart attack demi lovato", "heart attack lyrics"), YtQueryCompletions.parse(reply))
  }

  @Test fun badRepliesGiveNothing() {
    assertEquals(emptyList<String>(), YtQueryCompletions.parse("not json"))
    assertEquals(emptyList<String>(), YtQueryCompletions.parse("""{"a":1}"""))
    assertEquals(emptyList<String>(), YtQueryCompletions.parse("""["q"]"""))
  }

  @Test fun completionsAreCleanedAndCapped() {
    val many = (1..30).joinToString(",", "[\"q\",[", "]]") { "\"song $it\"" }
    assertEquals(YtQueryCompletions.MAX, YtQueryCompletions.parse(many).size)
    assertEquals(listOf("ok"), YtQueryCompletions.parse("""["q",["  ok  ", "", "${"x".repeat(200)}"]]"""))
  }

  @Test fun queryUrlEncodesTheText() {
    assertEquals("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=heart+attack%26",
        YtQueryCompletions.url("heart attack&"))
  }

  private val songs = listOf(
      MusicSuggestion("Heart Attack", MusicSuggestionKind.SONG, "vid1234567a", "Demi Lovato"),
      MusicSuggestion("Heartbeat", MusicSuggestionKind.SONG, "vid1234567b", "Childish Gambino"),
  )

  @Test fun ownSongsComeFirstThenYoutubeCompletions() {
    val merged = MusicSuggest.withCompletions(songs, listOf("heart attack", "heart attack demi lovato", "heart attack lyrics"))
    assertEquals(listOf("Heart Attack", "Heartbeat", "heart attack", "heart attack demi lovato", "heart attack lyrics"),
        merged.map { it.text })
    assertEquals(MusicSuggestionKind.SEARCH, merged[2].kind)
  }

  @Test fun noLocalMatchesStillSuggestsFromYoutube() {
    val merged = MusicSuggest.withCompletions(emptyList(), listOf("heart attack", "heart attack demi lovato"))
    assertEquals(listOf("heart attack", "heart attack demi lovato"), merged.map { it.text })
  }

  @Test fun duplicatesOfLocalSearchesAreDroppedAndTheListIsCapped() {
    val local = listOf(MusicSuggestion("heart attack", MusicSuggestionKind.SEARCH))
    val merged = MusicSuggest.withCompletions(local, listOf("Heart  Attack") + (1..20).map { "heart attack $it" })
    assertEquals(1, merged.count { MusicSuggest.normalize(it.text) == "heart attack" })
    assertEquals(MusicSuggest.MAX_SUGGESTIONS, merged.size)
  }

  @Test fun localSongsAreLimitedSoYoutubeStillShows() {
    val lots = (1..8).map { MusicSuggestion("Heart $it", MusicSuggestionKind.SONG, "vid12345678$it") }
    val merged = MusicSuggest.withCompletions(lots, listOf("heart attack"))
    assertEquals(MusicSuggest.MAX_LOCAL_WITH_COMPLETIONS, merged.count { it.kind == MusicSuggestionKind.SONG })
    assertEquals("heart attack", merged.last().text)
  }
}
