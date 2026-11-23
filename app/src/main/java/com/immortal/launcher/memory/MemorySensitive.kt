/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * The deny-list every model-suggested fact and Recent topic passes (plan §3.3, slice 4): health, religion, politics,
 * sexuality, money beyond ticker names, precise addresses and anything about children are never kept, whatever the
 * model said. Word and phrase matches over [MemoryText.tokens], plus a street-address shape. Pure.
 */
internal object MemorySensitive {
  /** Whether [text] touches anything the plan says is never learned. */
  fun touches(text: String): Boolean {
    val tokens = MemoryText.tokens(text)
    if (tokens.isEmpty()) return false
    if (tokens.any { it in WORDS }) return true
    if (PHRASES.any { MemoryText.contains(tokens, it) }) return true
    return ADDRESS.containsMatchIn(text.lowercase())
  }

  private val WORDS = setOf(
      // health
      "health", "doctor", "doctors", "hospital", "clinic", "diagnosis", "diagnosed", "cancer", "diabetes", "diabetic",
      "pregnant", "pregnancy", "therapy", "therapist", "medication", "medications", "medicine", "prescription",
      "depression", "depressed", "anxiety", "adhd", "autism", "disease", "illness", "surgery", "symptoms", "covid",
      "allergy", "allergic", "disability", "rehab", "psychiatrist", "dentist", "std", "hiv", "ivf",
      // religion
      "religion", "religious", "church", "mosque", "synagogue", "temple", "prayer", "pray", "praying", "bible",
      "quran", "torah", "god", "jesus", "allah", "christian", "muslim", "jewish", "hindu", "buddhist", "atheist",
      "catholic", "mormon",
      // politics
      "politics", "political", "election", "elections", "vote", "voting", "voted", "democrat", "democrats",
      "republican", "republicans", "liberal", "conservative", "congress", "senate", "president", "trump", "biden",
      "abortion", "immigration", "protest",
      // sexuality
      "sex", "sexual", "sexuality", "gay", "lesbian", "bisexual", "transgender", "queer", "lgbt", "lgbtq",
      "dating", "tinder",
      // money beyond ticker names
      "salary", "income", "debt", "debts", "loan", "loans", "mortgage", "bankrupt", "bankruptcy", "paycheck",
      "savings", "taxes", "pension", "401k",
      // children
      "child", "children", "kid", "kids", "son", "daughter", "baby", "toddler", "school", "kindergarten",
      "daycare", "teen", "teenager",
  )

  private val PHRASES = listOf(
      "bank account", "credit card", "credit score", "net worth", "social security", "home address",
      "mental health", "my weight", "lose weight", "weight loss", "how much i make", "how much i earn",
  ).map(MemoryText::tokens)

  /** "12 Oak Street", "221b baker st", "apt 4": a number next to a street word, or a unit. */
  private val ADDRESS = Regex(
      "\\b\\d+[a-z]?\\s+(?:[a-z]+\\s+){0,3}(?:street|st|avenue|ave|road|rd|lane|ln|drive|dr|court|ct|boulevard|" +
          "blvd|way|place|pl|terrace)\\b|\\b(?:apt|apartment|unit|suite)\\s*#?\\d+",
  )
}
