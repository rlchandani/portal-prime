/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Jarvis card is told at the open, for the mock's badges, and nothing more. Pure.
 * - [who]: "Talking with Alex · Kitchen Portal" and "Uses what Jarvis knows about Alex". Only the first name and
 *   this Portal's room, and only for a recognised adult alone with Jarvis: never when others are near, never for
 *   the household. Off moments (switch off, guests, children, teens) never get here.
 * - [facts]: the facts the note named, for the per-answer "Warriors · your team". Each is a short label, a reason
 *   code (Jarvis owns the words) and up to [MAX_WORDS] dictionary names the answer may use for it. Only the kinds a
 *   badge can explain; preferences, places and people never get one.
 */
internal object MemoryCard {
  const val MAX_FACTS = 6
  const val MAX_WORDS = 4
  private const val MAX_WORD = 32
  private const val MAX_ROOM = 40
  /** Asked about in this many conversations: "you ask about it often". */
  private const val OFTEN_CONVERSATIONS = 3
  private val ASKED = setOf(Evidence.Source.UTTERANCE, Evidence.Source.TOOL_CALL, Evidence.Source.INTENT_PHRASE)

  /** The card's name and room, or null unless a named adult is alone with Jarvis. */
  fun who(access: MemoryAccess.On, room: String): JSONObject? {
    if (access.scope !is MemoryScope.Person || access.othersNear) return null
    val name = access.firstName ?: return null
    val where = room.trim().take(MAX_ROOM)
    return JSONObject().put("name", name).apply { if (where.isNotEmpty()) put("room", where) }
  }

  /** The badges for the facts the note [used], or null when none can have one. */
  fun facts(used: List<MemoryFact>, dictionary: MemoryDictionary): JSONArray? =
      used.mapNotNull { badge(it, dictionary) }.take(MAX_FACTS).takeIf { it.isNotEmpty() }?.let(::JSONArray)

  /** One fact's badge: `{label, why, words}`; null for a kind no badge explains. */
  fun badge(fact: MemoryFact, dictionary: MemoryDictionary): JSONObject? {
    val why = why(fact) ?: return null
    val entry = dictionary.entry(fact.id)
    val names = entry?.let { it.names + it.cuedNames }.orEmpty()
    val label = when (fact.kind) {
      Kind.TEAM -> shortName(fact.label, names)
      Kind.TICKER -> fact.id.substringAfter(':').takeUnless { it.startsWith("^") } ?: fact.label
      else -> fact.label
    }
    val words = (listOf(label, fact.label) + names)
        .map { MemoryText.tokens(it).joinToString(" ") }
        .filter { it.isNotEmpty() && it.length <= MAX_WORD }
        .distinct().take(MAX_WORDS)
    if (words.isEmpty()) return null
    return JSONObject().put("label", label).put("why", why).put("words", JSONArray(words))
  }

  /** A badge's words, as the test and Jarvis read them. */
  fun words(badge: JSONObject): List<String> =
      badge.optJSONArray("words")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

  private fun why(fact: MemoryFact): String? = when (fact.kind) {
    Kind.TEAM -> "team"
    Kind.TICKER, Kind.CRYPTO -> if (askedIn(fact) >= OFTEN_CONVERSATIONS) "often" else "follow"
    Kind.NEWS_TOPIC -> "follow"
    Kind.MUSIC_TASTE -> "music"
    else -> null
  }

  private fun askedIn(fact: MemoryFact): Int =
      fact.evidence.filter { it.source in ASKED }.mapNotNull { it.conversationId }.distinct().size

  /**
   * The shortest dictionary name the [label] ends with, in the label's own casing ("Golden State Warriors" →
   * "Warriors", "Boston Red Sox" → "Red Sox"); the whole label when none is.
   */
  private fun shortName(label: String, names: List<String>): String {
    val words = label.trim().split(Regex("\\s+"))
    val tokens = MemoryText.tokens(label)
    if (tokens.size != words.size) return label
    val k = names.map(MemoryText::tokens).filter { it.isNotEmpty() && it.size <= tokens.size }
        .filter { tokens.takeLast(it.size) == it }.minOfOrNull { it.size } ?: return label
    return words.takeLast(k).joinToString(" ")
  }
}
