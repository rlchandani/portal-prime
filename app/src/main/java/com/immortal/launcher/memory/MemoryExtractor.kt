/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind

/**
 * The on-device extractor (plan §3.3, slice 1): what one conversation says the person cares about, from the
 * person's own words and the structured tool calls, matched against the bundled [MemoryDictionary]. No cloud, no
 * model, deterministic, a few milliseconds.
 *
 * Rules, in the order they apply to each sentence:
 * - a sentence about someone else ("my son follows the Lakers") is skipped whole;
 * - names match longest first; a common-word name needs a cue of its kind in the same sentence;
 * - same-named teams ("the Giants") are told apart by a league word, else by what is already known, else dropped;
 * - an intent phrase ("keep an eye on", "I follow") marks the entities near it as intent; a negation ("I don't care
 *   about") drops them from this conversation.
 *
 * Music genres come only from the `play_music` tool call's query. Pure.
 */
internal class MemoryExtractor(private val dictionary: MemoryDictionary) {

  /** One thing the conversation points at, and how strongly ([Evidence.Source.INTENT_PHRASE]: they said they care). */
  data class Observation(val entry: MemoryDictionary.Entry, val source: Evidence.Source) {
    val factId: String get() = entry.factId
  }

  /** What [digest] says, one observation per fact (the strongest source wins). [known] breaks name ties. */
  fun extract(digest: MemoryDigest, known: Set<String> = emptySet()): List<Observation> {
    val fromSpeech = digest.userTurns.flatMap { text(it, Evidence.Source.UTTERANCE, known) }
    val fromTools = digest.toolCalls.flatMap { call -> tool(call, known) }
    val negated = (digest.userTurns.flatMap { negatedIds(it, known) }).toSet()
    return (fromSpeech + fromTools)
        .filter { it.factId !in negated }
        .groupBy { it.factId }
        .map { (_, same) -> same.maxBy { rank(it.source) } }
        .sortedBy { it.factId }
  }

  private fun tool(call: MemoryDigest.ToolCall, known: Set<String>): List<Observation> = when (call.name) {
    WEB_SEARCH -> text(call.query, Evidence.Source.TOOL_CALL, known)
    PLAY_MUSIC -> MemoryText.sentences(call.query).flatMap { sentence ->
      matches(sentence).flatMap { it.entries }.filter { it.kind == Kind.MUSIC_TASTE }
          .map { Observation(it, Evidence.Source.TOOL_CALL) }
    }
    else -> emptyList()
  }

  private fun text(text: String, source: Evidence.Source, known: Set<String>) =
      MemoryText.sentences(text).flatMap { sentence -> sentence(sentence, source, known) }

  private fun sentence(tokens: List<String>, source: Evidence.Source, known: Set<String>): List<Observation> {
    if (MemoryText.THIRD_PARTY.any { MemoryText.contains(tokens, it) }) return emptyList()
    val intents = windows(tokens, MemoryText.INTENT_FORWARD, MemoryText.INTENT_WINDOW) +
        backWindows(tokens, MemoryText.INTENT_BACKWARD, MemoryText.BACKWARD_WINDOW)
    val negations = windows(tokens, MemoryText.NEGATIONS, MemoryText.INTENT_WINDOW)
    return matches(tokens).mapNotNull { match ->
      val intent = intents.any { match.start in it }
      if (negations.any { match.start in it }) return@mapNotNull null
      val entry = resolve(match, tokens, intent, known) ?: return@mapNotNull null
      Observation(entry, if (intent) Evidence.Source.INTENT_PHRASE else source)
    }
  }

  /** The entity ids a negation in [text] points at, so a mention elsewhere in the chat can't count either. */
  private fun negatedIds(text: String, known: Set<String>): List<String> = MemoryText.sentences(text).flatMap { s ->
    val negations = windows(s, MemoryText.NEGATIONS, MemoryText.INTENT_WINDOW)
    matches(s).filter { m -> negations.any { m.start in it } }
        .mapNotNull { resolve(it, s, intent = true, known = known)?.factId }
  }

  /** A name match: where it starts and every entry it may mean, with whether that entry needs a cue. */
  private data class Match(val start: Int, val names: List<MemoryDictionary.Name>) {
    val entries get() = names.map { it.entry }
  }

  private fun matches(tokens: List<String>): List<Match> {
    val out = mutableListOf<Match>()
    var i = 0
    while (i < tokens.size) {
      val here = dictionary.namesAt(tokens, i)
      if (here.isEmpty()) {
        i++
        continue
      }
      val longest = here.first().tokens.size
      out += Match(i, here.filter { it.tokens.size == longest })
      i += longest
    }
    return out
  }

  /** The one entry [match] means in [tokens], or null. Music never comes from speech, only from `play_music`. */
  private fun resolve(match: Match, tokens: List<String>, intent: Boolean, known: Set<String>)
      : MemoryDictionary.Entry? {
    val cued = match.names.filter { name ->
      name.entry.kind != Kind.MUSIC_TASTE && (!name.cued || intent || cuedFor(name.entry, tokens))
    }.map { it.entry }.distinctBy { it.factId }
    if (cued.size <= 1) return cued.firstOrNull()
    val byLeague = cued.filter { entry ->
      dictionary.leagueWords[entry.attrs[MemoryDictionary.ATTR_LEAGUE]].orEmpty().any { MemoryText.contains(tokens, it) }
    }
    if (byLeague.size == 1) return byLeague.single()
    return cued.filter { it.factId in known }.singleOrNull()
  }

  private fun cuedFor(entry: MemoryDictionary.Entry, tokens: List<String>): Boolean {
    val cues = when (entry.kind) {
      Kind.TEAM -> MemoryText.SPORTS_CUES + dictionary.leagueWords.values.flatten()
      Kind.TICKER -> MemoryText.MARKET_CUES
      Kind.CRYPTO -> MemoryText.CRYPTO_CUES
      Kind.NEWS_TOPIC -> MemoryText.NEWS_CUES
      else -> emptyList()
    }
    return cues.any { MemoryText.contains(tokens, it) }
  }

  private fun windows(tokens: List<String>, phrases: List<List<String>>, size: Int): List<IntRange> =
      phrases.flatMap { phrase ->
        MemoryText.positions(tokens, phrase).map { at -> (at + phrase.size) until (at + phrase.size + size) }
      }

  private fun backWindows(tokens: List<String>, phrases: List<List<String>>, size: Int): List<IntRange> =
      phrases.flatMap { phrase -> MemoryText.positions(tokens, phrase).map { at -> (at - size).coerceAtLeast(0) until at } }

  private fun rank(source: Evidence.Source) = MemoryConfidence.weightOf(source)

  companion object {
    const val WEB_SEARCH = "portal.web_search"
    const val PLAY_MUSIC = "portal.play_music"
  }
}
