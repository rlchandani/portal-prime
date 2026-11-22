/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * Text handling shared by the dictionary and the extractor: one normalization for both sides (so "St. Louis",
 * "NVIDIA's" and "A.I." meet their dictionary names), sentence splitting, and the cue and intent lexicons
 * (plan §3.3). Pure.
 */
internal object MemoryText {
  private val APOSTROPHES = Regex("[‘’ʼ`]")
  private val ABBREVIATIONS = listOf(
      Regex("\\bst\\.\\s") to "st ", Regex("\\ba\\.i\\.?") to "ai", Regex("\\bu\\.s\\.?") to "us",
      Regex("\\bmr\\.\\s") to "mr ", Regex("\\bmrs\\.\\s") to "mrs ", Regex("\\bdr\\.\\s") to "dr ",
  )
  private val SENTENCE_BREAK = Regex("[.!?;:\\n]+(\\s+|$)|[!?;\\n]+")
  private val NOT_WORD = Regex("[^a-z0-9&'\\- ]+")
  private val SPACES = Regex("\\s+")

  /** Lower case, typographic apostrophes folded, a few dotted abbreviations closed up. */
  fun clean(text: String): String {
    var t = text.lowercase().replace(APOSTROPHES, "'")
    for ((pattern, replacement) in ABBREVIATIONS) t = t.replace(pattern, replacement)
    return t
  }

  /** [text] as sentences, each a list of [tokens]. */
  fun sentences(text: String): List<List<String>> =
      clean(text).split(SENTENCE_BREAK).map(::tokens).filter { it.isNotEmpty() }

  /** One sentence (or a dictionary name) as normalized tokens; a trailing possessive `'s` is dropped. */
  fun tokens(text: String): List<String> = clean(text).replace(NOT_WORD, " ").trim().split(SPACES)
      .map { it.trim('\'', '-').removeSuffix("'s").trim('\'') }
      .filter { it.isNotEmpty() }

  /** Whether [phrase] (already tokenized) occurs in [sentence]. */
  fun contains(sentence: List<String>, phrase: List<String>): Boolean = indexOf(sentence, phrase, 0) >= 0

  /** Every start index of [phrase] in [sentence]. */
  fun positions(sentence: List<String>, phrase: List<String>): List<Int> {
    val out = mutableListOf<Int>()
    var from = 0
    while (true) {
      val at = indexOf(sentence, phrase, from)
      if (at < 0) return out
      out += at
      from = at + 1
    }
  }

  private fun indexOf(sentence: List<String>, phrase: List<String>, from: Int): Int {
    if (phrase.isEmpty()) return -1
    for (i in from..sentence.size - phrase.size) {
      if ((phrase.indices).all { sentence[i + it] == phrase[it] }) return i
    }
    return -1
  }

  private fun phrases(vararg text: String): List<List<String>> = text.map(::tokens)

  /** Words that make a common-word team name ("the Heat", "the Giants") count as a team. */
  val SPORTS_CUES = phrases(
      "score", "scores", "game", "games", "match", "matches", "won", "win", "wins", "lose", "lost", "losing",
      "beat", "beating", "playoffs", "playoff", "season", "standings", "schedule", "roster", "trade", "traded",
      "draft", "highlights", "fixture", "fixtures", "league", "vs", "versus", "championship", "finals", "series",
      "goal", "goals", "touchdown", "inning", "innings", "quarterback", "coach", "stadium", "how did", "kickoff",
      "tip off", "home game", "away game", "box score", "team", "fan", "fans",
  )

  /** Words that make a common-word company ("Apple", "Target") count as a stock. */
  val MARKET_CUES = phrases(
      "stock", "stocks", "share", "shares", "share price", "stock price", "earnings", "ticker", "trading",
      "invest", "invested", "investing", "investment", "portfolio", "dividend", "dividends", "premarket",
      "pre-market", "after hours", "market cap", "nasdaq", "s&p", "dow jones", "wall street", "valuation",
      "analyst", "analysts", "quarterly results", "ipo", "options", "short", "rally", "sell-off", "selloff",
      "stock market", "market",
  )

  /** Words that make a common-word coin ("Ripple", "Avalanche") count as crypto. */
  val CRYPTO_CUES = phrases(
      "crypto", "cryptocurrency", "coin", "coins", "token", "tokens", "wallet", "blockchain", "hodl", "mining",
      "altcoin", "altcoins", "defi", "price", "prices", "worth", "market",
  )

  /** Words that make a topic word ("AI", "chips") a news interest. */
  val NEWS_CUES = phrases(
      "news", "headlines", "headline", "latest on", "latest in", "what's happening", "what is happening",
      "updates on", "update on", "articles", "article", "stories", "story about", "developments", "keep up with",
      "what's new in", "what is new in", "anything new in", "reports on", "coverage",
  )

  /**
   * Phrases that say the person cares about what follows ("keep an eye on AMD", "I follow the Warriors"). Each
   * marks the entities in the next [INTENT_WINDOW] tokens as intent: one such sentence is enough to learn.
   */
  val INTENT_FORWARD = phrases(
      "i follow", "i'm following", "i am following", "i've been following", "keep an eye on", "keep me posted on",
      "keep me updated on", "keep track of", "i'm watching", "i am watching", "i own", "i have shares in",
      "i have shares of", "i'm invested in", "i am invested in", "i invest in", "i hold", "i bought shares",
      "i care about", "i'm a fan of", "i am a fan of", "i'm a big fan of", "big fan of", "huge fan of",
      "i root for", "i support", "i cheer for", "i'm into", "i am into", "i'm interested in", "i am interested in",
      "i love the", "i'm all about", "my team is", "my teams are", "my stocks are", "my stock is",
      "i always follow", "always keep an eye on", "remind me about", "i'm tracking", "i am tracking",
  )

  /** Phrases after which the entity has already been named ("the Warriors are my team"). */
  val INTENT_BACKWARD = phrases(
      "is my team", "are my team", "are my teams", "is my favorite team", "are my favorite team",
      "is my stock", "is in my portfolio", "are in my portfolio", "is my favourite team",
  )

  /** Phrases that say the opposite: entities in the window get no evidence from this sentence. */
  val NEGATIONS = phrases(
      "don't care about", "do not care about", "not a fan of", "i hate", "i can't stand", "i cannot stand",
      "stop telling me about", "not interested in", "i don't follow", "i do not follow", "i don't like",
      "i do not like", "i'm not into", "i am not into", "never mention", "don't mention", "sold all my",
      "i sold my", "i sold",
  )

  /** Someone else's interests ("my son follows the Lakers"): the whole sentence is skipped. */
  val THIRD_PARTY = phrases(
      "my wife", "my husband", "my partner", "my son", "my daughter", "my kid", "my kids", "my child",
      "my children", "my brother", "my sister", "my mom", "my mother", "my dad", "my father", "my friend",
      "my friends", "my boss", "my neighbor", "my neighbour", "my grandma", "my grandpa", "my uncle", "my aunt",
      "my cousin", "my boyfriend", "my girlfriend", "my roommate", "my coworker", "my colleague",
  )

  const val INTENT_WINDOW = 8
  const val BACKWARD_WINDOW = 6
}
