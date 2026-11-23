/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind

/**
 * Hearthpane's re-check of the model's suggestions (plan §3.3, slice 4), before any of it is learned:
 * - **kind allow-list:** teams, tickers, crypto, news topics, music taste, places and preferences only;
 * - **grounded:** the evidence quote must be the person's own words from this conversation, and the label must share
 *   a word with what they said (so "they" can mean the Warriors only if the Warriors came up);
 * - **deny-list:** [MemorySensitive] on the label, the quote and every topic line; someone else's interests are
 *   skipped, as the dictionary extractor does;
 * - **canonical:** teams, tickers and crypto must be in the bundled [MemoryDictionary] (an unknown ticker is dropped);
 *   news topics and music match it when they can, otherwise they are kept as short plain labels;
 * - **weight:** a suggestion counts as a casual mention ([Evidence.Source.UTTERANCE], TENTATIVE), or as an intent
 *   phrase only when the model marked it so; never more.
 * A negative one ("I don't care about the Lakers") is returned as [Result.negated], so this conversation can't count
 * towards that fact at all. Pure.
 */
internal class LlmExtractionCheck(private val dictionary: MemoryDictionary) {

  data class Result(
      val observations: List<MemoryExtractor.Observation>,
      val negated: Set<String>,
      val topics: List<RecentTopic>,
  )

  fun check(extraction: LlmExtraction?, userTurns: List<String>, nowMs: Long): Result {
    if (extraction == null || userTurns.isEmpty()) return EMPTY
    val turns = userTurns.map(MemoryText::tokens)
    val said = turns.flatten().toSet()
    val kept = extraction.facts.mapNotNull { fact ->
      if (!grounded(fact, userTurns, turns)) return@mapNotNull null
      entry(fact)?.takeIf { named(it, said) }?.let { fact to it }
    }
    val negated = kept.filter { it.first.negative }.map { it.second.factId }.toSet()
    val observations = kept.filterNot { it.first.negative || it.second.factId in negated }
        .map { (fact, entry) ->
          MemoryExtractor.Observation(entry, if (fact.intent) Evidence.Source.INTENT_PHRASE else Evidence.Source.UTTERANCE)
        }
        .groupBy { it.factId }.map { (_, same) -> same.maxBy { MemoryConfidence.weightOf(it.source) } }
        .sortedBy { it.factId }
    val topics = extraction.topics.mapNotNull { topic(it, said, nowMs) }.distinctBy { it.topic }
    return Result(observations, negated, topics)
  }

  private fun grounded(
      fact: LlmExtraction.Fact,
      userTurns: List<String>,
      turns: List<List<String>>,
  ): Boolean {
    val quote = MemoryText.tokens(fact.evidenceQuote)
    val from = userTurns.filterIndexed { i, _ -> quote.isNotEmpty() && MemoryText.contains(turns[i], quote) }
    if (from.isEmpty()) return false
    // The sentence the quote sits in (or, for a quote across sentences, its whole turn) must be about the person.
    val sentences = from.flatMap(MemoryText::sentences)
    val around = sentences.filter { MemoryText.contains(it, quote) }.ifEmpty { sentences }
    if (around.any { s -> MemoryText.THIRD_PARTY.any { MemoryText.contains(s, it) } }) return false
    return listOf(fact.label, fact.canonical, fact.evidenceQuote).none(MemorySensitive::touches)
  }

  /**
   * Whether the person said a word of what [entry] is called (so "they" can be the Warriors only if the Warriors came
   * up). Only the entry's own names count, not the model's label, so a wrong label can't vouch for another team.
   */
  private fun named(entry: MemoryDictionary.Entry, said: Set<String>): Boolean {
    val names = listOf(entry.label) + entry.names + entry.cuedNames
    return names.any { name -> words(name).any { it in said } }
  }

  /** The dictionary entry (or a plain-label entry for free kinds) [fact] names, or null to drop it. */
  private fun entry(fact: LlmExtraction.Fact): MemoryDictionary.Entry? {
    val kind = KINDS[fact.kind] ?: return null
    return when (kind) {
      Kind.TEAM -> known(kind, fact.canonical) ?: single(fact.label, kind) ?: single(fact.canonical, kind)
      Kind.TICKER -> known(kind, fact.canonical.uppercase()) ?: single(fact.label, kind)
      Kind.CRYPTO -> known(kind, fact.canonical.lowercase()) ?: single(fact.label, kind)
      Kind.NEWS_TOPIC, Kind.MUSIC_TASTE -> single(fact.label, kind) ?: plain(kind, fact.label)
      else -> plain(kind, fact.label)
    }
  }

  private fun known(kind: Kind, canonical: String) = dictionary.entry(MemoryDictionary.factId(kind, canonical))

  private fun single(text: String, kind: Kind) = dictionary.lookup(text, kind).singleOrNull()

  /** A short label kept as it was said ("Bay Area weather", "Works in San Jose"); no digits for a place. */
  private fun plain(kind: Kind, label: String): MemoryDictionary.Entry? {
    val clean = label.replace(NOT_LABEL, " ").replace(SPACES, " ").trim()
    val words = clean.split(' ').filter { it.isNotEmpty() }
    if (clean.length !in MIN_LABEL..LlmExtraction.MAX_LABEL || words.size > MAX_WORDS) return null
    if (kind == Kind.PLACE && clean.any(Char::isDigit)) return null
    val slug = slug(clean) ?: return null
    return MemoryDictionary.Entry(kind, slug, clean.replaceFirstChar { it.uppercase() }, emptyMap())
  }

  private fun topic(t: LlmExtraction.Topic, said: Set<String>, nowMs: Long): RecentTopic? {
    val lines = listOfNotNull(t.topic, t.gist, t.openQuestion)
    if (lines.any(MemorySensitive::touches) || words(t.topic).none { it in said }) return null
    val slug = slug(t.topic) ?: return null
    val gist = t.gist.replace(CONTROL, " ").replace(SPACES, " ").trim().ifEmpty { return null }
    val open = t.openQuestion?.replace(CONTROL, " ")?.replace(SPACES, " ")?.trim()?.ifEmpty { null }
    return RecentTopic(topic = "$TOPIC_PREFIX$slug", gist = gist, openQuestion = open, atMs = nowMs)
  }

  private fun words(text: String) = MemoryText.tokens(text).filter { it.length >= MIN_WORD && it !in STOP }

  companion object {
    const val TOPIC_PREFIX = "topic:"
    private const val MIN_LABEL = 2
    private const val MAX_WORDS = 6
    private const val MIN_WORD = 3
    private const val MAX_SLUG = 48
    private val EMPTY = Result(emptyList(), emptySet(), emptyList())
    private val NOT_LABEL = Regex("[^\\p{L}\\p{N}&' .-]")
    private val CONTROL = Regex("\\p{Cntrl}")
    private val SPACES = Regex("\\s+")
    private val STOP = setOf("the", "and", "for", "with", "about", "news", "music")

    /** The model's kind names (the request schema's enum) to the kinds slice 4 may learn. */
    val KINDS = mapOf(
        "team" to Kind.TEAM, "ticker" to Kind.TICKER, "crypto" to Kind.CRYPTO, "news_topic" to Kind.NEWS_TOPIC,
        "music_taste" to Kind.MUSIC_TASTE, "music" to Kind.MUSIC_TASTE, "place" to Kind.PLACE,
        "preference" to Kind.PREFERENCE,
    )

    /** A file- and id-safe key for a plain label: lower-case words joined by `-`. */
    fun slug(label: String): String? = MemoryText.tokens(label).joinToString("-")
        .replace(Regex("[^a-z0-9-]"), "").replace(Regex("-+"), "-").trim('-').take(MAX_SLUG).trim('-')
        .ifEmpty { null }
  }
}
