/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.memory.MemoryFact
import com.immortal.launcher.memory.MemoryFixtures
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `hearthpane.sports`: live score, last result, next game and record from ESPN's keyless public API, for a named team
 * or the teams memory knows the person follows. Every payload is a fixed fixture (src/test/resources/sports, trimmed
 * from real ESPN responses); nothing here touches the network.
 */
class HearthpaneSportsTest {
  private fun fixture(name: String) = File("src/test/resources/sports/$name").readText()

  /** 2026-11-23 00:00 UTC: Sunday 22 November, 4 pm in California. */
  private val now = 1_795_392_000_000L

  private val nba = "basketball/nba"
  private val epl = "soccer/eng.1"
  private var responses = mutableMapOf(
      "$nba/teams" to fixture("nba_teams.json"),
      "$nba/teams/9" to fixture("gsw_team.json"),
      "$nba/teams/9/schedule" to fixture("gsw_sched.json"),
      "$nba/scoreboard" to """{"events":[]}""",
      "$epl/teams" to fixture("epl_teams.json"),
      "$epl/teams/359" to fixture("ars_team.json"),
      "$epl/teams/359/schedule" to fixture("ars_sched.json"),
  )
  private val fetched = mutableListOf<List<String>>()
  private var followed: List<MemoryFact>? = null

  private val tool = HearthpaneSports(
      dictionary = { MemoryFixtures.dictionary },
      followed = { followed },
      fetch = { paths, _ -> fetched += paths; paths.map { responses[it] } },
      now = { now },
      zone = { TimeZone.getTimeZone("America/Los_Angeles") },
      locale = { Locale.US },
      use24 = { false },
  )

  private fun call(vararg kv: Pair<String, Any>) =
      tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  private fun team(id: String, label: String, league: String) = MemoryFact(
      id = id, kind = MemoryFact.Kind.TEAM, label = label, attrs = mapOf("league" to league),
      state = MemoryFact.State.ACTIVE, firstSeenMs = now, lastSeenMs = now)

  @Test fun namedTeamGivesLastResultNextGameAndRecord() {
    val r = call("team" to "the Warriors")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals(
        "Golden State Warriors lost to Memphis Grizzlies 126 to 98 last Thursday. " +
            "Next: Boston Celtics at home tonight at 5:30 PM. They're 8-8, 3rd in Pacific Division.",
        r.getString("message"))
    val t = r.getJSONArray("teams").getJSONObject(0)
    assertEquals("98", t.getJSONObject("last").getString("score"))
    assertEquals("126", t.getJSONObject("last").getString("opponent_score"))
    assertTrue(t.getJSONObject("next").getBoolean("home"))
    assertEquals("ESPN", r.getString("source"))
  }

  @Test fun liveGameLeadsAndTheNextGameIsTheOneAfterIt() {
    responses["$nba/scoreboard"] = fixture("nba_scoreboard_live.json")
    val r = call("team" to "golden state")
    val message = r.getString("message")
    assertTrue(message, message.startsWith("Live: Golden State Warriors 78, Boston Celtics 81, 3:12 - 3rd."))
    assertTrue(message, message.contains("Next: Sacramento Kings at home on Wednesday at 7:00 PM."))
    assertFalse("a live game replaces the last result", message.contains("Grizzlies"))
    assertEquals("3:12 - 3rd", r.getJSONArray("teams").getJSONObject(0).getJSONObject("live").getString("status"))
  }

  @Test fun soccerDrawAndAFixtureMoreThanAWeekOut() {
    val r = call("team" to "Arsenal")
    assertEquals(
        "Arsenal drew with Newcastle United 2 to 2 yesterday. Next: Manchester City at home on November 29 at 8:30 AM. " +
            "They're 9-1-1, 1st in English Premier League.",
        r.getString("message"))
  }

  @Test fun myTeamUsesTheTeamsMemoryKnows() {
    followed = listOf(team("team:nba.gsw", "Golden State Warriors", "NBA"), team("team:epl.ars", "Arsenal", "EPL"))
    val r = call()
    assertTrue(r.getBoolean("ok"))
    assertEquals(2, r.getJSONArray("teams").length())
    val message = r.getString("message")
    assertTrue(message, message.contains("Golden State Warriors lost to Memphis Grizzlies"))
    assertTrue(message, message.contains("Arsenal drew with Newcastle United"))
    assertEquals("each league's team list once, then every game page in one round", 2, fetched.size)
  }

  @Test fun myTeamWithMemoryOffOrEmptyAsksWhich() {
    followed = null
    assertTrue(call().getBoolean("needs_team"))
    followed = emptyList()
    val r = call()
    assertTrue(r.getBoolean("needs_team"))
    assertTrue(r.getString("message").contains("haven't learned"))
    assertTrue("no network without a team", fetched.isEmpty())
  }

  @Test fun memoryTeamsAreCapped() {
    followed = (1..5).map { team("team:nba.gsw", "Golden State Warriors", "NBA") } +
        team("team:epl.ars", "Arsenal", "EPL")
    val r = call()
    assertEquals("duplicates fold, and only the first three are read", 1, r.getJSONArray("teams").length())
  }

  @Test fun sameNamedTeamsNeedTheLeague() {
    val r = call("team" to "Giants")
    assertTrue(r.getBoolean("needs_league"))
    assertTrue(fetched.isEmpty())
    responses["baseball/mlb/teams"] = """{"sports":[{"leagues":[{"teams":[]}]}]}"""
    call("team" to "Giants", "league" to "baseball")
    assertEquals(listOf("baseball/mlb/teams"), fetched.first())
  }

  @Test fun unreachableEspnSaysSoInsteadOfGuessing() {
    responses.clear()
    val r = call("team" to "Warriors")
    assertFalse(r.getBoolean("ok"))
    assertEquals("I couldn't reach the scores just now.", r.getString("message"))
    responses["$nba/teams"] = fixture("nba_teams.json")
    responses["$nba/teams/9"] = "<html>moved</html>"
    assertEquals("I couldn't reach the scores just now.", call("team" to "Warriors").getString("message"))
  }

  @Test fun badArgumentsAreErrorsNotCrashes() {
    assertTrue(tool.invoke("not json").has("error"))
    assertTrue(call("team" to "Zzyzx Unknowns").getBoolean("needs_league"))
  }

  @Test fun feedParsesTolerantly() {
    assertEquals(emptyList<SportsFeed.Team>(), SportsFeed.parseTeams("{}"))
    assertEquals(emptyList<SportsFeed.Game>(), SportsFeed.parseGames("[]"))
    assertNull(SportsFeed.parseTeam(null))
    val teams = SportsFeed.parseTeams(fixture("epl_teams.json"))
    assertEquals("331", SportsFeed.match("Brighton", teams)?.id)
    assertEquals("382", SportsFeed.match("man city", teams)?.id)
    assertEquals("359", SportsFeed.match("gunners", teams)?.id)
    assertNull(SportsFeed.match("United", teams))
    val hostile = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"9/../../x","displayName":"Golden State Warriors"}}]}]}]}"""
    assertEquals("an id that isn't a number never reaches a URL", emptyList<SportsFeed.Team>(), SportsFeed.parseTeams(hostile))
    assertEquals("soccer/esp.1", SportsFeed.pathFor("eu.rma", "EU"))
    assertEquals("hockey/nhl", SportsFeed.leaguePath("hockey"))
    assertNull(SportsFeed.leaguePath("quidditch"))
  }

  @Test fun compactCopiesParseTheSameAndAreSmall() {
    for ((path, name) in listOf("$nba/teams/9/schedule" to "gsw_sched.json", "$nba/scoreboard" to "nba_scoreboard_live.json")) {
      val raw = fixture(name)
      val small = SportsFeed.compact(path, raw)!!
      assertEquals(SportsFeed.parseGames(raw), SportsFeed.parseGames(small))
      assertTrue(small.length < raw.length)
    }
    val team = fixture("gsw_team.json")
    assertEquals(SportsFeed.parseTeam(team), SportsFeed.parseTeam(SportsFeed.compact("$nba/teams/9", team)))
    val teams = fixture("nba_teams.json")
    assertEquals(SportsFeed.parseTeams(teams), SportsFeed.parseTeams(SportsFeed.compact("$nba/teams", teams)))
    assertNull(SportsFeed.compact("$nba/teams", "<html>"))
    assertNull(SportsFeed.compact("$nba/scoreboard", null))
  }
}
