/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import com.immortal.launcher.memory.MemoryFact.Kind
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled, versioned word lists the on-device extractor matches against (`assets/memory/dictionary.json`, plan
 * §3.3): sports teams with their leagues and nicknames, company names to tickers, crypto assets, news topics and
 * music genres. Only these kinds exist in it, so the extractor can never produce a sensitive kind.
 *
 * Each entry's names are either distinctive ([Name.cued] false: "Warriors", "NVIDIA") or common words ("Heat",
 * "Apple", "jazz") that count only with a cue for the entry's kind in the same sentence. Pure apart from [load].
 */
internal class MemoryDictionary private constructor(
    val version: Int,
    val entries: List<Entry>,
    /** League code → the words that name it ("NBA" → nba, basketball), to tell same-named teams apart. */
    val leagueWords: Map<String, List<List<String>>>,
) {
  /** One thing that can be learned. [factId] is its stable memory id, e.g. `team:nba.gsw`. */
  data class Entry(
      val kind: Kind,
      val canonical: String,
      val label: String,
      /** What a learned fact carries: the league, the CoinGecko id. */
      val attrs: Map<String, String>,
      val names: List<String> = emptyList(),
      val cuedNames: List<String> = emptyList(),
  ) {
    val factId: String get() = factId(kind, canonical)
  }

  /** One way of saying an [entry]. */
  data class Name(val tokens: List<String>, val entry: Entry, val cued: Boolean)

  private val namesByEntry: Map<String, List<Name>> = entries.associate { it.factId to namesOf(it) }

  /** First token → every name starting with it, longest first. */
  private val index: Map<String, List<Name>> = namesByEntry.values.flatten()
      .groupBy { it.tokens.first() }
      .mapValues { (_, names) -> names.sortedByDescending { it.tokens.size } }

  private val byFactId: Map<String, Entry> = entries.associateBy { it.factId }

  /** Every dictionary name that starts at [sentence]`[at]`, longest first. */
  fun namesAt(sentence: List<String>, at: Int): List<Name> = index[sentence[at]].orEmpty().filter { name ->
    at + name.tokens.size <= sentence.size && name.tokens.indices.all { sentence[at + it] == name.tokens[it] }
  }

  fun entry(factId: String): Entry? = byFactId[factId]

  /**
   * The entries a free-text [about] names ("Warriors", "AMD", "the Dubs"), for an explicit remember or forget:
   * names match whole, cued or not, since the person is saying it outright. Limited to [kind] when given.
   */
  fun lookup(about: String, kind: Kind? = null): List<Entry> {
    val tokens = MemoryText.tokens(about).dropWhile { it == "the" }
    if (tokens.isEmpty()) return emptyList()
    val exact = entries.filter { kind == null || it.kind == kind }.filter { entry ->
      namesByEntry[entry.factId].orEmpty().any { it.tokens == tokens } || MemoryText.tokens(entry.label) == tokens ||
          entry.canonical.lowercase() == tokens.joinToString(" ")
    }
    return exact.distinctBy { it.factId }
  }

  private fun namesOf(entry: Entry): List<Name> =
      (entry.names.map { Name(MemoryText.tokens(it), entry, cued = false) } +
          entry.cuedNames.map { Name(MemoryText.tokens(it), entry, cued = true) }).filter { it.tokens.isNotEmpty() }

  companion object {
    const val ASSET = "memory/dictionary.json"
    const val ATTR_LEAGUE = "league"
    const val ATTR_COINGECKO = "coingecko"

    /** The memory id for a [kind] and [canonical] key: `team:nba.gsw`, `ticker:NVDA`, `crypto:bitcoin`. */
    fun factId(kind: Kind, canonical: String): String = "${prefix(kind)}:$canonical"

    private fun prefix(kind: Kind) = when (kind) {
      Kind.TEAM -> "team"
      Kind.TICKER -> "ticker"
      Kind.CRYPTO -> "crypto"
      Kind.NEWS_TOPIC -> "news"
      Kind.MUSIC_TASTE -> "music"
      else -> kind.name.lowercase()
    }

    @Volatile private var cached: MemoryDictionary? = null

    /** The bundled dictionary, parsed once per process (about 30 KB, read lazily on first use). */
    fun load(context: Context): MemoryDictionary = cached ?: synchronized(this) {
      cached ?: parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }).also { cached = it }
    }

    /** Parses the dictionary JSON. Throws on a malformed file: it is bundled, so a test catches that first. */
    fun parse(json: String): MemoryDictionary {
      val root = JSONObject(json)
      val leagues = root.getJSONObject("leagues").let { o ->
        o.keys().asSequence().associateWith { key -> strings(o.getJSONArray(key)).map(MemoryText::tokens) }
      }
      val entries = listOf(
          "teams" to Kind.TEAM, "tickers" to Kind.TICKER, "crypto" to Kind.CRYPTO,
          "news" to Kind.NEWS_TOPIC, "music" to Kind.MUSIC_TASTE,
      ).flatMap { (key, kind) -> objects(root.getJSONArray(key)).map { entry(kind, it) } }
      return MemoryDictionary(root.getInt("v"), entries, leagues)
    }

    private fun entry(kind: Kind, o: JSONObject): Entry {
      val id = o.getString("id")
      val attrs = buildMap {
        o.optString("g").takeIf { it.isNotEmpty() }?.let { put(ATTR_LEAGUE, it) }
        if (kind == Kind.CRYPTO) put(ATTR_COINGECKO, id)
      }
      return Entry(kind, id, o.getString("l"), attrs, strings(o.optJSONArray("n")), strings(o.optJSONArray("c")))
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }
  }
}
