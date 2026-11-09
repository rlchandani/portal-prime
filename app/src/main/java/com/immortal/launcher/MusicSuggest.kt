/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.Normalizer
import java.util.Locale

/** What tapping a suggestion does: run a YouTube search (search/artist) or play the song now. */
internal enum class MusicSuggestionKind(val wire: String) { SEARCH("search"), SONG("song"), ARTIST("artist") }

/** One suggestion under the search field; [videoId]/[artist]/[thumb] only for songs. Immutable. */
internal data class MusicSuggestion(
    val text: String,
    val kind: MusicSuggestionKind,
    val videoId: String? = null,
    val artist: String? = null,
    val thumb: String? = null,
    /** A query this Portal searched before (shown with a clock), vs a fresh completion. */
    val recent: Boolean = false,
)

/** A query this Portal searched, newest first. */
internal data class MusicRecentSearch(val query: String, val atMs: Long)

/** Local data the suggestions come from (no YouTube quota). Lists are newest / most relevant first. */
internal data class MusicSuggestSources(
    val searches: List<MusicRecentSearch> = emptyList(),
    val history: List<MusicPlay> = emptyList(),
    val liked: List<YtTrack> = emptyList(),
    val artists: List<String> = emptyList(),
)

/**
 * Search-as-you-type from local data only. Prefix / word-start / contains matching, case and
 * diacritic insensitive; ranked exact prefix > word start > contains, then recent > older (recent
 * searches and plays by time, then liked songs, then followed artists).
 */
internal object MusicSuggest {
  const val MAX_SUGGESTIONS = 8
  const val MIN_CHARS = 1
  const val DEBOUNCE_MS = 150L
  /** With YouTube completions available, at most this many of your own matches come first. */
  const val MAX_LOCAL_WITH_COMPLETIONS = 4

  private enum class Tier { PREFIX, WORD_START, CONTAINS }

  private data class Candidate(val suggestion: MusicSuggestion, val match: String, val atMs: Long)

  private val MARKS = Regex("\\p{Mn}+")
  private val SPACES = Regex("\\s+")

  /** Lowercase, accents removed, whitespace collapsed: "Beyoncé  Knowles" → "beyonce knowles". */
  fun normalize(text: String): String =
      Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).trim().replace(SPACES, " ")

  fun suggest(query: String, sources: MusicSuggestSources): List<MusicSuggestion> {
    val q = normalize(query)
    if (q.length < MIN_CHARS) return emptyList()
    val ranked = candidates(sources).mapNotNull { c -> tier(c.match, q)?.let { it to c } }
        .sortedWith(compareBy<Pair<Tier, Candidate>> { it.first }.thenByDescending { it.second.atMs })
        .map { it.second.suggestion }
    return ranked.distinctBy(::identity).take(MAX_SUGGESTIONS)
  }

  /**
   * Your own matches first (at most [MAX_LOCAL_WITH_COMPLETIONS] when there are completions), then
   * YouTube's search completions as searches, skipping any that repeat a local suggestion's words.
   */
  fun withCompletions(local: List<MusicSuggestion>, completions: List<String>): List<MusicSuggestion> {
    if (completions.isEmpty()) return local.take(MAX_SUGGESTIONS)
    val mine = local.take(MAX_LOCAL_WITH_COMPLETIONS)
    // A song you own and a search with the same words do different things (play vs search): keep both.
    val seen = mine.filter { it.kind != MusicSuggestionKind.SONG }.map { normalize(it.text) }.toMutableSet()
    val web = completions.mapNotNull { text ->
      normalize(text).takeIf { it.isNotEmpty() && seen.add(it) }?.let { MusicSuggestion(text.trim().replace(SPACES, " "), MusicSuggestionKind.SEARCH) }
    }
    return (mine + web).take(MAX_SUGGESTIONS)
  }

  /** Songs are one per video; a search and an artist with the same words are one suggestion. */
  private fun identity(s: MusicSuggestion): String =
      if (s.kind == MusicSuggestionKind.SONG) "song:${s.videoId}" else "words:${normalize(s.text)}"

  private fun candidates(sources: MusicSuggestSources): List<Candidate> {
    val plays = MusicHistory.listing(sources.history)
    val searches = sources.searches.map { Candidate(MusicSuggestion(it.query, MusicSuggestionKind.SEARCH, recent = true), it.query, it.atMs) }
    val played = plays.map { song(it.toTrack(), it.playedAtMs) }
    val playedArtists = plays.filter { it.artist.isNotBlank() }.distinctBy { normalize(it.artist) }
        .map { Candidate(MusicSuggestion(it.artist, MusicSuggestionKind.ARTIST), it.artist, it.playedAtMs) }
    // Undated sources keep their own order after everything dated (a stable sort).
    val liked = sources.liked.map { song(it, UNDATED) }
    val artists = sources.artists.filter { it.isNotBlank() }
        .map { Candidate(MusicSuggestion(it, MusicSuggestionKind.ARTIST), it, UNDATED) }
    return (searches + played + playedArtists).sortedByDescending { it.atMs } + liked + artists
  }

  private fun song(track: YtTrack, atMs: Long) = Candidate(
      MusicSuggestion(track.title, MusicSuggestionKind.SONG, track.videoId, track.artist.ifBlank { null },
          track.thumbnailUrl.ifBlank { null }), track.title, atMs)

  private fun tier(text: String, q: String): Tier? {
    val t = normalize(text)
    if (t.isEmpty()) return null
    return when {
      t.startsWith(q) -> Tier.PREFIX
      wordStarts(t).any { t.startsWith(q, it) } -> Tier.WORD_START
      t.contains(q) -> Tier.CONTAINS
      else -> null
    }
  }

  /** Indices where a word begins (after a space or punctuation). */
  private fun wordStarts(t: String): List<Int> =
      (1 until t.length).filter { !t[it - 1].isLetterOrDigit() && t[it].isLetterOrDigit() }

  private const val UNDATED = Long.MIN_VALUE
}

/** The last [MAX_ENTRIES] searches this Portal ran (stored), newest first, case-insensitively unique. */
internal object MusicRecentSearches {
  const val MAX_ENTRIES = 20
  private const val MAX_QUERY = 100

  fun with(recent: List<MusicRecentSearch>, query: String, atMs: Long): List<MusicRecentSearch> {
    val q = query.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY)
    if (q.length < MusicV3Rules.MIN_QUERY) return recent
    return (listOf(MusicRecentSearch(q, atMs)) + recent.filterNot { it.query.equals(q, ignoreCase = true) })
        .take(MAX_ENTRIES)
  }

  fun encode(recent: List<MusicRecentSearch>): String = org.json.JSONArray().also { array ->
    recent.forEach { array.put(org.json.JSONObject().put("q", it.query).put("at", it.atMs)) }
  }.toString()

  fun decode(text: String?): List<MusicRecentSearch> {
    if (text.isNullOrBlank()) return emptyList()
    val array = try { org.json.JSONArray(text) } catch (_: org.json.JSONException) { return emptyList() }
    return (0 until array.length()).mapNotNull { i ->
      val row = array.optJSONObject(i) ?: return@mapNotNull null
      val q = row.optString("q").takeIf { it.isNotBlank() && it.length <= MAX_QUERY } ?: return@mapNotNull null
      MusicRecentSearch(q, row.optLong("at"))
    }.take(MAX_ENTRIES)
  }
}
