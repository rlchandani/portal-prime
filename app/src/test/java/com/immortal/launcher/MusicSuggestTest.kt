/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSuggestTest {
  private fun id(n: Int) = "vid" + n.toString().padStart(8, '0')
  private fun track(n: Int, title: String, artist: String = "") = YtTrack(id(n), title, artist, "https://t/$n")
  private fun played(n: Int, title: String, artist: String, at: Long) =
      MusicPlay(id(n), title, artist, "", at, "Kitchen")
  private fun texts(q: String, sources: MusicSuggestSources) = MusicSuggest.suggest(q, sources).map { it.text }

  @Test fun normalizeFoldsCaseAccentsAndSpaces() {
    assertEquals("beyonce knowles", MusicSuggest.normalize("  Beyoncé   KNOWLES "))
    assertEquals("sigur ros", MusicSuggest.normalize("Sigur Rós"))
  }

  @Test fun emptyQuerySuggestsNothing() {
    assertTrue(MusicSuggest.suggest("  ", MusicSuggestSources(artists = listOf("Adele"))).isEmpty())
  }

  @Test fun oneCharacterIsEnough() {
    assertEquals(listOf("Adele"), texts("a", MusicSuggestSources(artists = listOf("Adele", "Muse"))))
  }

  @Test fun diacriticInsensitiveBothWays() {
    val sources = MusicSuggestSources(artists = listOf("Beyoncé", "Sigur Ros"))
    assertEquals(listOf("Beyoncé"), texts("beyonce", sources))
    assertEquals(listOf("Sigur Ros"), texts("sigur rós", sources))
  }

  @Test fun prefixBeatsWordStartBeatsContains() {
    val sources = MusicSuggestSources(liked = listOf(
        track(1, "Glory Hallelujah"), track(2, "Hallelujah"), track(3, "The Hall of Fame"), track(4, "Ohallo")))
    // Word starts keep their source order (both are liked songs, no dates).
    assertEquals(listOf("Hallelujah", "Glory Hallelujah", "The Hall of Fame", "Ohallo"), texts("hall", sources))
  }

  @Test fun wordStartAfterPunctuation() {
    assertEquals(listOf("Don't Stop (Remix)"), texts("remix", MusicSuggestSources(liked = listOf(track(1, "Don't Stop (Remix)")))))
  }

  @Test fun recentBeatsOlderWithinATier() {
    val sources = MusicSuggestSources(
        searches = listOf(MusicRecentSearch("queen live", 100)),
        history = listOf(played(1, "Queen of Hearts", "Juice", 300), played(2, "Queens", "Band", 200)),
        liked = listOf(track(3, "Queen Bee")),
        artists = listOf("Queen"))
    assertEquals(listOf("Queen of Hearts", "Queens", "queen live", "Queen Bee", "Queen"), texts("queen", sources))
  }

  @Test fun kindsCarryWhatATapNeeds() {
    val sources = MusicSuggestSources(searches = listOf(MusicRecentSearch("abba", 1)),
        liked = listOf(track(1, "Abba Medley", "Abba Tribute")), artists = listOf("Abbey Road"))
    val result = MusicSuggest.suggest("abb", sources)
    val song = result.single { it.kind == MusicSuggestionKind.SONG }
    assertEquals(id(1), song.videoId)
    assertEquals("Abba Tribute", song.artist)
    assertEquals("https://t/1", song.thumb)
    assertEquals(MusicSuggestionKind.SEARCH, result.first { it.text == "abba" }.kind)
    assertEquals(MusicSuggestionKind.ARTIST, result.first { it.text == "Abbey Road" }.kind)
  }

  @Test fun historyArtistsBecomeArtistSuggestions() {
    val sources = MusicSuggestSources(history = listOf(played(1, "Hello", "Adele", 5)))
    assertEquals(listOf(MusicSuggestionKind.ARTIST), MusicSuggest.suggest("adele", sources).map { it.kind })
  }

  @Test fun dedupesSongsByVideoAndWordsAcrossSearchAndArtist() {
    val sources = MusicSuggestSources(
        searches = listOf(MusicRecentSearch("Adele", 10)),
        history = listOf(played(1, "Hello", "Adele", 5), played(1, "Hello", "Adele", 1)),
        liked = listOf(track(1, "Hello")),
        artists = listOf("ADELE"))
    assertEquals(listOf("Adele"), texts("ade", sources))
    assertEquals(1, MusicSuggest.suggest("hel", sources).size)
  }

  @Test fun capsAtEight() {
    val sources = MusicSuggestSources(liked = (1..20).map { track(it, "Song $it") })
    assertEquals(MusicSuggest.MAX_SUGGESTIONS, MusicSuggest.suggest("song", sources).size)
  }

  @Test fun recentSearchesKeepTwentyNewestFirstAndUnique() {
    val many = (1..25).fold(emptyList<MusicRecentSearch>()) { acc, n -> MusicRecentSearches.with(acc, "query $n", n.toLong()) }
    assertEquals(MusicRecentSearches.MAX_ENTRIES, many.size)
    assertEquals("query 25", many.first().query)
    val again = MusicRecentSearches.with(many, "  QUERY   10 ", 99)
    assertEquals(MusicRecentSearch("QUERY 10", 99), again.first())
    assertEquals(1, again.count { it.query.equals("query 10", ignoreCase = true) })
    assertEquals(many, MusicRecentSearches.with(many, "x", 100))
  }

  @Test fun recentSearchesRoundTripAndTolerateJunk() {
    val recent = listOf(MusicRecentSearch("adele", 2), MusicRecentSearch("abba", 1))
    assertEquals(recent, MusicRecentSearches.decode(MusicRecentSearches.encode(recent)))
    assertTrue(MusicRecentSearches.decode("{").isEmpty())
    assertEquals(1, MusicRecentSearches.decode("""[{"q":""},{"q":"ok","at":3},7]""").size)
  }
}
