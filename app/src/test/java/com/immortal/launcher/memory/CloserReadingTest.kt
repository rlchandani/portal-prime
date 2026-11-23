/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan D3's own switch: with "Closer reading" off, Jarvis is not asked to extract, and nothing it sends is used. */
class CloserReadingTest {
  private val store = MemoryFixtures.FakeStore()
  private val access = MemoryAccess.On(MemoryScope.Household, null, othersNear = false)
  private var closer = true
  private val handoff = MemoryHandoff(store.runtime, { MemoryFixtures.dictionary }, { access }, { "Kitchen" },
      { store.clock }, { ZoneOffset.UTC }, closerReading = { closer })

  private fun open(id: String) = handoff.context(JSONObject().put("conversation", id).toString())

  private fun endWithSuggestion(id: String) = handoff.observe(JSONObject().put("conversation", id)
      .put("user", JSONArray(listOf("What's the Bay Area weather like?")))
      .put("extracted", JSONObject().put("facts", JSONArray(listOf(JSONObject().put("kind", "news_topic")
          .put("label", "Bay Area weather").put("evidenceQuote", "Bay Area weather").put("polarity", "positive")
          .put("intent", false))))).toString())

  @Test fun onByDefaultJarvisIsAskedToReadCloser() {
    assertTrue(open("c1").optBoolean("extract"))
    endWithSuggestion("c1")
    assertTrue(store.runtime.profile(MemoryScope.Household).facts.any { it.id == "news:bay-area-weather" })
  }

  @Test fun offJarvisIsNotAskedAndASuggestionIsDroppedUnread() {
    closer = false
    val context = open("c1")
    assertFalse(context.has("extract"))
    assertFalse(context.has("known"))
    assertTrue(context.getBoolean("learning"))
    endWithSuggestion("c1")
    assertTrue(store.runtime.profile(MemoryScope.Household).facts.none { it.id == "news:bay-area-weather" })
  }
}
