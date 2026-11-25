/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyless ESPN parser's edge cases ([SportsFeed]): ESPN's two date shapes, scores given as a string, a number or
 * an object, a postponed game dropped not errored, and picking the last result / live / next game. These back the
 * "keyless sports" voice answers, so a quiet shape change must drop a game, never the whole reply.
 */
class SportsFeedTest {

  private val teamId = "9"

  private fun side(id: String, name: String, home: Boolean, score: Any?, winner: Boolean?): JSONObject =
      JSONObject().put("homeAway", if (home) "home" else "away")
          .put("team", JSONObject().put("id", id).put("displayName", name))
          .apply { score?.let { put("score", it) } }
          .apply { winner?.let { put("winner", it) } }

  private fun event(id: String, date: String, state: String, completed: Boolean, home: JSONObject, away: JSONObject,
      detail: String = "") = JSONObject().put("id", id).put("date", date).put("competitions", JSONArray().put(
      JSONObject().put("status", JSONObject().put("type",
          JSONObject().put("state", state).put("completed", completed).put("shortDetail", detail)))
          .put("competitors", JSONArray().put(home).put(away))))

  private fun body(vararg events: JSONObject) =
      JSONObject().put("events", JSONArray().apply { events.forEach { put(it) } }).toString()

  @Test fun parsesBothEspnDateShapesAndRejectsJunk() {
    val withSeconds = SportsFeed.parseDate("2026-11-23T01:30:00Z")
    val noSeconds = SportsFeed.parseDate("2026-11-23T01:30Z")
    assertNotNull(withSeconds)
    assertEquals("the seconds-less form means the same instant", withSeconds, noSeconds)
    assertNull(SportsFeed.parseDate(""))
    assertNull(SportsFeed.parseDate("tomorrow"))
  }

  @Test fun scoreIsReadFromAStringANumberOrAnObject() {
    val games = SportsFeed.parseGames(body(event("1", "2026-11-23T01:30Z", "post", true,
        home = side(teamId, "Golden State Warriors", home = true, score = 112, winner = true),
        away = side("2", "Boston Celtics", home = false,
            score = JSONObject().put("displayValue", "98"), winner = false))))
    val game = games.single()
    assertEquals("112", game.us(teamId)?.score)
    assertEquals("98", game.them(teamId)?.score)
    assertEquals(true, game.us(teamId)?.winner)
  }

  @Test fun postponedGameIsDroppedNotErrored() {
    val games = SportsFeed.parseGames(body(
        event("1", "2026-11-23T01:30Z", "post", completed = false,
            home = side(teamId, "Warriors", true, "0", null), away = side("2", "Celtics", false, "0", null)),
        event("2", "2026-11-24T01:30Z", "pre", completed = false,
            home = side(teamId, "Warriors", true, null, null), away = side("3", "Lakers", false, null, null))))
    assertEquals("only the real game survives", listOf("2"), games.map { it.id })
  }

  @Test fun picksLastFinalLiveAndSoonestUpcoming() {
    val yesterday = SportsFeed.parseDate("2026-11-22T01:30Z")!!
    val now = SportsFeed.parseDate("2026-11-23T01:30Z")!!
    val tomorrow = SportsFeed.parseDate("2026-11-24T01:30Z")!!
    val older = SportsFeed.parseDate("2026-11-20T01:30Z")!!
    val games = SportsFeed.parseGames(body(
        event("old", "2026-11-20T01:30Z", "post", true,
            side(teamId, "Warriors", true, "90", false), side("2", "Celtics", false, "95", true)),
        event("last", "2026-11-22T01:30Z", "post", true,
            side(teamId, "Warriors", true, "101", true), side("3", "Lakers", false, "99", false)),
        event("live", "2026-11-23T01:30Z", "in", false,
            side(teamId, "Warriors", true, "40", null), side("4", "Suns", false, "38", null)),
        event("next", "2026-11-24T01:30Z", "pre", false,
            side(teamId, "Warriors", true, null, null), side("5", "Kings", false, null, null))))

    assertEquals("last", SportsFeed.lastResult(games, teamId)?.id)
    assertTrue(SportsFeed.lastResult(games, teamId)!!.startMs > older)
    assertEquals("live", SportsFeed.live(games, teamId)?.id)
    assertEquals("next", SportsFeed.upcoming(games, teamId, now)?.id)
    assertEquals("the upcoming game is tomorrow", tomorrow, SportsFeed.upcoming(games, teamId, now)?.startMs)
    assertNull("a team not playing has no live game", SportsFeed.live(games, "999"))
    assertEquals("yesterday's final is the latest result", yesterday, SportsFeed.lastResult(games, teamId)?.startMs)
  }

  @Test fun aShapeChangeDropsTheGameNotTheAnswer() {
    assertEquals(emptyList<SportsFeed.Game>(), SportsFeed.parseGames("<html>"))
    assertEquals(emptyList<SportsFeed.Game>(), SportsFeed.parseGames(null))
    // One good game, one missing its competitors: the good one still comes back.
    val oneGood = JSONObject().put("events", JSONArray()
        .put(JSONObject().put("id", "broken").put("competitions", JSONArray().put(JSONObject())))
        .put(event("ok", "2026-11-23T01:30Z", "in", false,
            side(teamId, "Warriors", true, "1", null), side("2", "Celtics", false, "2", null)))).toString()
    assertEquals(listOf("ok"), SportsFeed.parseGames(oneGood).map { it.id })
  }
}
