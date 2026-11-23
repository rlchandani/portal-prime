/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.NewsFeed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.news`: a flash briefing from the household's own news feeds (NPR when none are added); keyless. */
class HearthpaneNewsTest {
  private fun h(title: String) = NewsFeed.Headline(title, "https://example.com/${title.hashCode()}")
  private var sources = listOf(
      HearthpaneNews.Source("BBC", listOf(h("Storm hits coast"), h("Election called"), h("Markets rally"))),
      HearthpaneNews.Source("NPR", listOf(h("Senate passes bill"), h("Storm hits coast"))),
  )
  private var defaulted = false
  private val tool = HearthpaneNews(sources = { sources }, defaulted = { defaulted })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  private fun titles(r: JSONObject) = (0 until r.getJSONArray("headlines").length()).map { r.getJSONArray("headlines").getJSONObject(it).getString("title") }

  @Test fun headlinesInterleaveSourcesDropRepeatsAndNeverCarryLinks() {
    val r = call()
    assertTrue(r.getBoolean("ok"))
    assertEquals(listOf("Storm hits coast", "Senate passes bill", "Election called", "Markets rally"), titles(r))
    assertEquals("NPR", r.getJSONArray("headlines").getJSONObject(1).getString("source"))
    assertFalse(r.toString().contains("example.com"))
    assertEquals("From BBC: Storm hits coast. Election called. Markets rally. From NPR: Senate passes bill.", r.getString("message"))
  }

  @Test fun countIsBoundedAndDefaultsToFive() {
    sources = listOf(HearthpaneNews.Source("BBC", (1..12).map { h("Story $it") }))
    assertEquals(HearthpaneNews.DEFAULT_COUNT, titles(call()).size)
    assertEquals(2, titles(call("count" to 2)).size)
    assertEquals(HearthpaneNews.MAX_COUNT, titles(call("count" to 50)).size)
    assertEquals(1, titles(call("count" to 0)).size)
  }

  @Test fun aNamedSourceGivesOnlyItsHeadlines() {
    assertEquals(listOf("Senate passes bill", "Storm hits coast"), titles(call("source" to "npr")))
    val unknown = call("source" to "Fox")
    assertFalse(unknown.getBoolean("ok"))
    assertTrue(unknown.getString("message"), unknown.getString("message").contains("BBC and NPR"))
  }

  @Test fun theDefaultFeedIsSaidSoAndUnreachableFeedsSaySo() {
    defaulted = true
    assertTrue(call().getString("note").contains("Settings"))
    sources = listOf(HearthpaneNews.Source("BBC", emptyList()))
    val r = call()
    assertFalse(r.getBoolean("ok"))
    assertTrue(r.getString("message").contains("couldn't reach"))
    sources = emptyList()
    assertFalse(call().getBoolean("ok"))
  }

  @Test fun titlesAreTrimmedAndBadInputIsAnError() {
    sources = listOf(HearthpaneNews.Source("BBC", listOf(h("y".repeat(400)))))
    assertEquals(HearthpaneNews.MAX_TITLE, titles(call())[0].length)
    assertTrue(tool.invoke("[").has("error"))
    assertEquals("news unavailable", HearthpaneNews(sources = { error("x") }).invoke("{}").getString("error"))
  }
}
