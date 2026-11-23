/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import com.immortal.launcher.memory.MemoryFixtures.NOW
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 4: the text extractor's suggestions are re-checked before anything is learned (plan §3.3). */
class LlmExtractionTest {
  private val check = LlmExtractionCheck(MemoryFixtures.dictionary)

  private fun fact(kind: String, label: String, quote: String, canonical: String? = null, intent: Boolean = false,
      polarity: String = "positive") = JSONObject().put("kind", kind).put("label", label).put("evidenceQuote", quote)
      .put("polarity", polarity).put("intent", intent).apply { canonical?.let { put("canonical", it) } }

  private fun topic(topic: String, gist: String, open: String? = null) =
      JSONObject().put("topic", topic).put("gist", gist).apply { open?.let { put("openQuestion", it) } }

  private fun extraction(facts: List<JSONObject> = emptyList(), topics: List<JSONObject> = emptyList()) =
      LlmExtraction.parse(JSONObject().put("facts", JSONArray(facts)).put("recentTopics", JSONArray(topics)))

  private fun run(said: List<String>, facts: List<JSONObject> = emptyList(), topics: List<JSONObject> = emptyList()) =
      check.check(extraction(facts, topics), said, NOW)

  @Test fun aPronounResolvedByTheModelCountsAsACasualMentionOfADictionaryTeam() {
    val r = run(listOf("The Warriors were great.", "How did they do last night?"),
        listOf(fact("team", "Golden State Warriors", "how did they do last night", canonical = "nba.gsw")))
    val obs = r.observations.single()
    assertEquals("team:nba.gsw", obs.factId)
    assertEquals(Evidence.Source.UTTERANCE, obs.source)
  }

  @Test fun intentOnlyWhenTheModelSaysSo() {
    val r = run(listOf("Keep an eye on AMD for me"),
        listOf(fact("ticker", "AMD", "keep an eye on AMD", canonical = "AMD", intent = true)))
    assertEquals(Evidence.Source.INTENT_PHRASE, r.observations.single().source)
  }

  @Test fun anUnknownTickerIsDropped() {
    val r = run(listOf("What about ZZZQ stock"), listOf(fact("ticker", "ZZZQ", "ZZZQ stock", canonical = "ZZZQ")))
    assertTrue(r.observations.isEmpty())
  }

  @Test fun aQuoteThePersonNeverSaidIsDropped() {
    val r = run(listOf("Play something"), listOf(fact("music_taste", "Jazz", "I love jazz")))
    assertTrue(r.observations.isEmpty())
  }

  @Test fun aLabelSharingNoWordWithWhatWasSaidIsDropped() {
    val r = run(listOf("I really like that"), listOf(fact("music_taste", "Jazz", "I really like that")))
    assertTrue(r.observations.isEmpty())
  }

  @Test fun freeNewsTopicsPlacesAndPreferencesAreKeptAsShortLabels() {
    val r = run(listOf("What's the Bay Area weather like?", "I work in San Jose.", "I prefer oat milk in coffee."),
        listOf(
            fact("news_topic", "Bay Area weather", "bay area weather"),
            fact("place", "Works in San Jose", "I work in San Jose"),
            fact("preference", "Oat milk in coffee", "I prefer oat milk in coffee"),
        ))
    val ids = r.observations.map { it.factId }.toSet()
    assertEquals(setOf("news:bay-area-weather", "place:works-in-san-jose", "preference:oat-milk-in-coffee"), ids)
    assertEquals(Kind.PLACE, r.observations.single { it.factId.startsWith("place:") }.entry.kind)
  }

  @Test fun aNewsTopicTheDictionaryKnowsUsesItsId() {
    val r = run(listOf("Any news on semiconductors?"), listOf(fact("news_topic", "Chips", "news on semiconductors")))
    assertEquals("news:chips", r.observations.single().factId)
  }

  @Test fun sensitiveSuggestionsAreNeverKept() {
    val said = listOf("My doctor said my diabetes is fine.", "I go to church on Sunday.", "I live at 12 Oak Street.",
        "I vote Republican.", "My salary is low.")
    val r = run(said,
        listOf(
            fact("preference", "Doctor visits", "my doctor said"),
            fact("preference", "Church on Sunday", "I go to church on Sunday"),
            fact("place", "12 Oak Street", "I live at 12 Oak Street"),
            fact("preference", "Republican", "I vote Republican"),
            fact("preference", "Low salary", "my salary is low"),
        ),
        listOf(topic("Diabetes", "talked about diabetes"), topic("Church", "asked about church times")))
    assertTrue(r.observations.isEmpty())
    assertTrue(r.topics.isEmpty())
  }

  @Test fun someoneElsesInterestsAreSkipped() {
    val r = run(listOf("My son follows the Lakers"), listOf(fact("team", "Los Angeles Lakers", "follows the Lakers")))
    assertTrue(r.observations.isEmpty())
  }

  @Test fun aNegativeSuggestionCancelsTheFactForThisConversation() {
    val r = run(listOf("I don't care about the Lakers"),
        listOf(fact("team", "Los Angeles Lakers", "don't care about the Lakers", polarity = "negative")))
    assertTrue(r.observations.isEmpty())
    assertEquals(setOf("team:nba.lal"), r.negated)
  }

  @Test fun unknownKindsAreDropped() {
    val r = run(listOf("My name is Alex"), listOf(fact("person_name", "Alex", "my name is Alex")))
    assertTrue(r.observations.isEmpty())
  }

  @Test fun recentTopicsAreGroundedCappedAndIdSafe() {
    val r = run(listOf("When does NVIDIA report earnings?"),
        listOf(), listOf(topic("NVIDIA earnings", "asked about NVIDIA earnings", "when they report"),
            topic("Holiday plans", "talked about holidays")))
    val t = r.topics.single()
    assertEquals("topic:nvidia-earnings", t.topic)
    assertEquals("when they report", t.openQuestion)
    assertEquals(NOW, t.atMs)
  }

  @Test fun oversizedOrMalformedEntriesAreDroppedNotCut() {
    val longLabel = "x".repeat(LlmExtraction.MAX_LABEL + 1)
    val parsed = LlmExtraction.parse(JSONObject()
        .put("facts", JSONArray().put(fact("team", longLabel, "q")).put("not an object").put(JSONObject()))
        .put("recentTopics", JSONArray((0..9).map { topic("t$it", "gist $it") })))!!
    assertTrue(parsed.facts.isEmpty())
    assertEquals(LlmExtraction.MAX_TOPICS, parsed.topics.size)
    assertNull(LlmExtraction.parse(null))
  }

  @Test fun theDigestCarriesTheExtraction() {
    val json = JSONObject().put("conversation", "c1").put("user", JSONArray(listOf("Keep an eye on AMD")))
        .put("extracted", JSONObject().put("facts", JSONArray(listOf(fact("ticker", "AMD", "keep an eye on AMD")))))
    val digest = MemoryDigest.parse(json.toString())!!
    assertEquals(1, digest.extracted!!.facts.size)
    assertNull(MemoryDigest.parse(JSONObject().put("conversation", "c2").toString())!!.extracted)
  }

  @Test fun theDenyListLeavesOrdinaryInterestsAlone() {
    listOf("Golden State Warriors", "NVIDIA earnings", "Bay Area weather", "Works in San Jose", "Jazz on Sunday")
        .forEach { assertFalse(it, MemorySensitive.touches(it)) }
  }
}
