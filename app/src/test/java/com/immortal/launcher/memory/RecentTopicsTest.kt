/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.State
import com.immortal.launcher.memory.MemoryFixtures.DAY
import com.immortal.launcher.memory.MemoryFixtures.NOW
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 4 end to end over the pure pieces: the text extractor's facts and Recent topics, the once-only follow-up, the
 * handshake that tells Jarvis to extract, and the same exclusions as slices 1–3.
 */
class RecentTopicsTest {
  private val store = MemoryFixtures.FakeStore()
  private val alex = MemoryScope.Person("alex")
  private var access: MemoryAccess = MemoryAccess.On(alex, "Alex", false)
  private val handoff = MemoryHandoff(store.runtime, { MemoryFixtures.dictionary }, { access }, { "Kitchen" },
      { store.clock }, { ZoneOffset.UTC }, { _, _ -> })

  private fun open(id: String) = handoff.context(JSONObject().put("conversation", id).toString())

  private fun end(id: String, said: List<String>, facts: List<JSONObject> = emptyList(),
      topics: List<JSONObject> = emptyList()): JSONObject = handoff.observe(JSONObject().put("conversation", id)
      .put("user", JSONArray(said))
      .put("extracted", JSONObject().put("facts", JSONArray(facts)).put("recentTopics", JSONArray(topics))).toString())

  private fun fact(kind: String, label: String, quote: String, intent: Boolean = false) = JSONObject()
      .put("kind", kind).put("label", label).put("evidenceQuote", quote).put("polarity", "positive").put("intent", intent)

  private fun topic(topic: String, gist: String, open: String? = null) =
      JSONObject().put("topic", topic).put("gist", gist).apply { open?.let { put("openQuestion", it) } }

  private fun profile(scope: MemoryScope = alex) = store.runtime.profile(scope)

  private val earnings = topic("NVIDIA earnings", "asked about NVIDIA earnings", "when they report")

  @Test fun theOpenAsksJarvisToExtractAndSaysWhatIsKnown() {
    open("c0")
    end("c0", listOf("Keep an eye on AMD"))
    val opened = open("c1")
    assertTrue(opened.getBoolean("extract"))
    assertEquals("ticker:AMD", opened.getJSONArray("known").getString(0))
  }

  @Test fun noExtractionWhenLearningIsPausedOrOff() {
    store.runtime.update(alex) { MemoryLearner.setPaused(it, true) }
    assertFalse(open("c1").has("extract"))
    for (reason in MemoryAccess.Reason.entries) {
      access = MemoryAccess.Off(reason)
      val opened = open("off-$reason")
      assertFalse(opened.has("extract"))
      assertFalse(opened.has("known"))
      assertTrue(end("off-$reason", listOf("When does NVIDIA report?"), topics = listOf(earnings)).getBoolean("ignored"))
    }
    assertTrue(profile().recentTopics.isEmpty())
  }

  @Test fun aTopicIsOfferedInOneNoteOnlyThenNeverAgain() {
    open("c1")
    end("c1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    store.clock += DAY
    val opened = open("c2")
    val first = opened.getString("note")
    assertTrue(first, first.contains("Recently: Mon asked about NVIDIA earnings (open: when they report)."))
    val thread = opened.getJSONArray("threads").getJSONObject(0)
    assertEquals("Monday", thread.getString("day"))
    assertEquals(listOf("nvidia", "earnings"), thread.getJSONArray("words").let { a -> List(a.length()) { a.getString(it) } })
    end("c2", listOf("Thanks"))
    val again = open("c3")
    assertFalse(again.optString("note"), again.optString("note").contains("NVIDIA earnings"))
    assertFalse(again.has("threads"))
    assertEquals(1, profile().recentTopics.single { it.extracted }.shownCount)
  }

  @Test fun talkingAboutATopicAgainDoesNotOfferItAgain() {
    open("c1")
    end("c1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    open("c2")
    end("c2", listOf("NVIDIA earnings again"), topics = listOf(earnings))
    assertFalse(open("c3").optString("note").contains("NVIDIA earnings"))
  }

  @Test fun theRecentlyLineStaysWithinItsCap() {
    open("c1")
    val gist = "asked about NVIDIA earnings " + "and guidance ".repeat(8)
    end("c1", listOf("NVIDIA earnings and guidance"), topics = listOf(topic("NVIDIA earnings", gist.trim().take(140),
        "whether margins hold up next quarter and what the outlook for data centre demand is")))
    val line = open("c2").getString("note").lines().single { it.startsWith(MemoryNote.THREAD_PREFIX) }
    assertTrue(line.length <= MemoryNote.RECENT_MAX)
  }

  @Test fun theThreadKeepsItsPlaceInAFullNote() {
    val teams = MemoryFixtures.dictionary.entries.filter { it.kind == MemoryFact.Kind.TEAM }.take(40)
    store.runtime.update(alex) { p ->
      teams.fold(p) { acc, e -> MemoryLearner.confirm(acc, e, Evidence.Source.EXPLICIT, null, "Kitchen", store.clock) }
    }
    open("c1")
    end("c1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    val note = open("c2").getString("note")
    assertTrue(note, note.contains("Recently: Mon asked about NVIDIA earnings"))
    assertTrue(note.length <= MemoryNote.BUDGET)
  }

  @Test fun noRecentLineWhenOthersAreNearOrForTheHousehold() {
    open("c1")
    end("c1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    access = MemoryAccess.On(alex, "Alex", othersNear = true)
    assertFalse(open("c2").optString("note").contains("Recently"))
    access = MemoryAccess.On(MemoryScope.Household, null, false)
    open("h1")
    end("h1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    assertFalse(open("h2").optString("note").contains("Recently"))
    access = MemoryAccess.On(alex, "Alex", false)
    assertEquals(0, profile().recentTopics.single { it.extracted }.shownCount)
  }

  @Test fun extractedFactsEnterTentativeUnlessMarkedIntent() {
    open("c1")
    val r = end("c1", listOf("What's the Bay Area weather like?", "Keep an eye on AMD for me"),
        listOf(fact("news_topic", "Bay Area weather", "Bay Area weather"),
            fact("ticker", "AMD", "keep an eye on AMD", intent = true)))
    assertEquals(State.TENTATIVE, profile().facts.single { it.id == "news:bay-area-weather" }.state)
    assertEquals(State.ACTIVE, profile().facts.single { it.id == "ticker:AMD" }.state)
    assertEquals(2, r.getInt("extracted"))
    assertEquals("ticker:AMD", r.getJSONArray("learned").getJSONObject(0).getString("id"))
  }

  @Test fun twoConversationsMakeAnExtractedTopicActive() {
    for (id in listOf("c1", "c2")) {
      open(id)
      end(id, listOf("What's the Bay Area weather like?"), listOf(fact("news_topic", "Bay Area weather", "Bay Area weather")))
      store.clock += DAY
    }
    assertEquals(State.ACTIVE, profile().facts.single { it.id == "news:bay-area-weather" }.state)
  }

  @Test fun placesAreKeptForThePersonButNeverInjectedNorLearnedForTheHousehold() {
    for (id in listOf("c1", "c2")) {
      open(id)
      end(id, listOf("I work in San Jose."), listOf(fact("place", "Works in San Jose", "I work in San Jose")))
      store.clock += DAY
    }
    val place = profile().facts.single { it.id == "place:works-in-san-jose" }
    assertTrue(place.sensitive)
    assertEquals(KnowsRows.Category.PLACES, KnowsRows.rows(profile(), store.clock, ZoneOffset.UTC).single().category)
    assertFalse(open("c3").optString("note").contains("San Jose"))
    access = MemoryAccess.On(MemoryScope.Household, null, false)
    open("h1")
    end("h1", listOf("I work in San Jose."), listOf(fact("place", "Works in San Jose", "I work in San Jose")))
    assertTrue(profile(MemoryScope.Household).facts.isEmpty())
  }

  @Test fun aNegativeSuggestionStopsTheDictionaryCountingTheSameTeam() {
    open("c1")
    end("c1", listOf("The Lakers again? I don't follow the Lakers anymore, honestly"),
        listOf(fact("team", "Los Angeles Lakers", "the Lakers again").put("polarity", "negative")))
    assertTrue(profile().facts.none { it.id == "team:nba.lal" })
  }

  @Test fun forgettingAFactAlsoForgetsATopicAboutIt() {
    open("c1")
    end("c1", listOf("Keep an eye on NVIDIA. When does NVIDIA report earnings?"),
        listOf(fact("ticker", "NVIDIA", "keep an eye on NVIDIA", intent = true)), listOf(earnings))
    val before = profile().recentTopics.count { it.extracted }
    assertEquals(1, before)
    store.runtime.update(alex, immediate = true) { MemoryLearner.forget(it, setOf("ticker:NVDA"), store.clock) }
    assertTrue(profile().recentTopics.none { it.extracted })
  }

  @Test fun pausedLearningKeepsNewTopicsOut() {
    store.runtime.update(alex) { MemoryLearner.setPaused(it, true) }
    open("c1")
    end("c1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    assertTrue(profile().recentTopics.isEmpty())
  }

  @Test fun aHandoverArrivingAfterTheNextConversationOpenedStillGoesToItsOwnPerson() {
    open("c1")
    access = MemoryAccess.On(alex, "Alex", false)
    open("c2") // Jarvis was still extracting c1 when c2 opened
    end("c1", listOf("When does NVIDIA report earnings?"), topics = listOf(earnings))
    assertEquals(1, profile().recentTopics.count { it.extracted })
  }

  @Test fun shownCountSurvivesTheSealedFileAndOldFilesReadAsNeverShown() {
    val p = MemoryProfile(alex, recentTopics = listOf(
        RecentTopic("topic:nvidia-earnings", "asked about NVIDIA earnings", "when", NOW, shownCount = 1)))
    val back = MemoryProfileCodec.decode(MemoryProfileCodec.encode(p), alex)
    assertEquals(1, back.recentTopics.single().shownCount)
    val old = """{"v":1,"scope":"person-alex","facts":[],"topics":[{"topic":"team:nba.gsw","gist":"Warriors","at":1}]}"""
    assertEquals(0, MemoryProfileCodec.decode(old, alex).recentTopics.single().shownCount)
    assertNull(MemoryProfileCodec.decode(old, alex).recentTopics.single().openQuestion)
  }
}
