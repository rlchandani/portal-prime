/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * The keyless sports source for [HearthpaneSports]: ESPN's public site API (`site.api.espn.com/apis/site/v2/sports`),
 * the JSON behind espn.com's own scoreboards. No key, no sign-up; it is undocumented, so ESPN may change or close it
 * at any time, and its terms allow personal, non-commercial use only. Every parse here is tolerant: a missing field
 * drops that game (or team), never the answer, and a shape change reads as "couldn't reach the scores".
 *
 * Paths, relative to [BASE]:
 * - `{league}/teams`: every team with its ESPN id and names (changes yearly; cached for a week);
 * - `{league}/teams/{id}`: the team's record, standing and next (or current) game;
 * - `{league}/teams/{id}/schedule`: the season's games with final scores (the last result);
 * - `{league}/scoreboard`: today's games with live scores and clocks.
 * Pure.
 */
internal object SportsFeed {
  const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

  /** The household dictionary's league codes ([com.immortal.launcher.memory.MemoryDictionary]) → ESPN paths. */
  private val LEAGUES = mapOf(
      "NBA" to "basketball/nba", "NFL" to "football/nfl", "MLB" to "baseball/mlb", "NHL" to "hockey/nhl",
      "EPL" to "soccer/eng.1", "MLS" to "soccer/usa.1", "WNBA" to "basketball/wnba",
      "LALIGA" to "soccer/esp.1", "BUNDESLIGA" to "soccer/ger.1", "SERIEA" to "soccer/ita.1", "LIGUE1" to "soccer/fra.1",
  )

  /** The dictionary's "EU" clubs, each in its own domestic league. */
  private val EU_CLUBS = mapOf(
      "eu.rma" to "soccer/esp.1", "eu.bar" to "soccer/esp.1", "eu.bay" to "soccer/ger.1",
      "eu.psg" to "soccer/fra.1", "eu.juv" to "soccer/ita.1",
  )

  /** The words a person may use for a league, for the tool's `league` argument. */
  private val LEAGUE_WORDS = mapOf(
      "nba" to "NBA", "basketball" to "NBA", "nfl" to "NFL", "american football" to "NFL", "mlb" to "MLB",
      "baseball" to "MLB", "nhl" to "NHL", "hockey" to "NHL", "premier league" to "EPL", "epl" to "EPL",
      "english premier league" to "EPL", "mls" to "MLS", "wnba" to "WNBA", "la liga" to "LALIGA",
      "bundesliga" to "BUNDESLIGA", "serie a" to "SERIEA", "ligue 1" to "LIGUE1",
  )

  /** [color] is ESPN's primary team colour as six hex digits ("1d428a"), or empty when it isn't given. */
  data class Team(val id: String, val name: String, val short: String, val abbreviation: String, val nickname: String,
      val color: String = "")

  enum class State { PRE, LIVE, FINAL }

  data class Side(val teamId: String, val name: String, val score: String?, val home: Boolean, val winner: Boolean?)

  data class Game(val id: String, val startMs: Long, val state: State, val detail: String, val sides: List<Side>) {
    fun us(teamId: String): Side? = sides.firstOrNull { it.teamId == teamId }
    fun them(teamId: String): Side? = sides.firstOrNull { it.teamId != teamId }
  }

  data class TeamInfo(val record: String?, val standing: String?, val next: Game?)

  /** The ESPN path for a dictionary league code, or the league a person named; null when unknown. */
  fun leaguePath(league: String?): String? {
    val raw = league?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val code = LEAGUE_WORDS[raw.lowercase(Locale.ROOT)] ?: raw.uppercase(Locale.ROOT)
    return LEAGUES[code]
  }

  /** The path for a dictionary team ([factCanonical] like `nba.gsw`, [league] like `NBA`). */
  fun pathFor(factCanonical: String, league: String?): String? = EU_CLUBS[factCanonical] ?: leaguePath(league)

  fun teamsPath(league: String) = "$league/teams"
  fun teamPath(league: String, id: String) = "$league/teams/$id"
  fun schedulePath(league: String, id: String) = "$league/teams/$id/schedule"
  fun scoreboardPath(league: String) = "$league/scoreboard"

  fun parseTeams(body: String?): List<Team> = runCatching {
    val leagues = JSONObject(body ?: return emptyList()).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues")
    val teams = leagues.getJSONObject(0).getJSONArray("teams")
    (0 until teams.length()).mapNotNull { i -> teams.optJSONObject(i)?.optJSONObject("team")?.let(::team) }
  }.getOrDefault(emptyList())

  private fun team(o: JSONObject): Team? {
    val id = o.optString("id").takeIf(TEAM_ID::matches) ?: return null // goes into a URL path
    val name = o.optString("displayName").ifEmpty { return null }
    val color = o.optString("color").lowercase(Locale.ROOT).takeIf(COLOR::matches).orEmpty()
    return Team(id, name, o.optString("shortDisplayName"), o.optString("abbreviation"), o.optString("nickname"), color)
  }

  /**
   * The team [said] means among [teams]: its full name, short name ("Man City"), the dictionary's label, or the
   * nickname ("Warriors"); else the one team whose full name holds every word said. Null when none or several.
   */
  fun match(said: String, teams: List<Team>): Team? {
    val want = words(said)
    if (want.isEmpty()) return null
    teams.firstOrNull { t -> listOf(t.name, t.short, t.abbreviation).any { words(it) == want } }?.let { return it }
    teams.filter { t -> words(t.name).takeLast(want.size) == want || words(t.nickname) == want }.singleOrNull()
        ?.let { return it }
    return teams.filter { t -> words(t.name).containsAll(want) }.singleOrNull()
  }

  private fun words(s: String): List<String> =
      s.lowercase(Locale.ROOT).replace("&", " and ").split(Regex("[^\\p{L}\\p{N}]+"))
          .filter { it.isNotEmpty() && it != "the" && it != "fc" }

  /** The team's record, standing and next game from `teams/{id}`. */
  fun parseTeam(body: String?): TeamInfo? = runCatching {
    val t = JSONObject(body ?: return null).getJSONObject("team")
    val record = t.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)?.optString("summary")?.ifEmpty { null }
    val next = t.optJSONArray("nextEvent")?.let { events(it) }?.firstOrNull()
    TeamInfo(record, t.optString("standingSummary").ifEmpty { null }, next)
  }.getOrNull()

  /** Every game in a schedule or scoreboard response, in date order. */
  fun parseGames(body: String?): List<Game> = runCatching {
    events(JSONObject(body ?: return emptyList()).optJSONArray("events") ?: return emptyList())
  }.getOrDefault(emptyList())

  /** The team's latest finished game. */
  fun lastResult(games: List<Game>, teamId: String): Game? =
      games.filter { it.state == State.FINAL && it.us(teamId) != null }.maxByOrNull { it.startMs }

  /** The team's game under way on [games] (a scoreboard), if any. */
  fun live(games: List<Game>, teamId: String): Game? = games.firstOrNull { it.state == State.LIVE && it.us(teamId) != null }

  /** The team's soonest game still to come. */
  fun upcoming(games: List<Game>, teamId: String, nowMs: Long): Game? =
      games.filter { it.state == State.PRE && it.us(teamId) != null && it.startMs > nowMs - HOUR_MS }.minByOrNull { it.startMs }

  private fun events(a: JSONArray): List<Game> =
      (0 until a.length()).mapNotNull { i -> runCatching { game(a.getJSONObject(i)) }.getOrNull() }.sortedBy { it.startMs }

  private fun game(e: JSONObject): Game? {
    val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
    val type = c.optJSONObject("status")?.optJSONObject("type") ?: return null
    val state = when (type.optString("state")) {
      "pre" -> State.PRE
      "in" -> State.LIVE
      "post" -> if (type.optBoolean("completed", true)) State.FINAL else return null // postponed, cancelled
      else -> return null
    }
    val start = parseDate(e.optString("date").ifEmpty { c.optString("date") }) ?: return null
    val comps = c.optJSONArray("competitors") ?: return null
    val sides = (0 until comps.length()).mapNotNull { i -> side(comps.optJSONObject(i)) }
    if (sides.size != 2) return null
    return Game(e.optString("id"), start, state, type.optString("shortDetail").ifEmpty { type.optString("detail") }, sides)
  }

  private fun side(o: JSONObject?): Side? {
    val team = o?.optJSONObject("team") ?: return null
    val id = team.optString("id").ifEmpty { o.optString("id") }.ifEmpty { return null }
    val score = when (val s = o.opt("score")) {
      is JSONObject -> s.optString("displayValue").ifEmpty { null }
      is String -> s.ifEmpty { null }
      is Number -> s.toInt().toString()
      else -> null
    }
    val winner = if (o.has("winner")) o.optBoolean("winner") else null
    return Side(id, team.optString("displayName").ifEmpty { return null }, score, o.optString("homeAway") == "home", winner)
  }

  /**
   * [body] reduced to just what the parsers here read, in the same shape, so a cached copy is a few KB instead of
   * ESPN's hundreds (a season's NBA schedule is about 900 KB). Null when [body] doesn't parse.
   */
  fun compact(path: String, body: String?): String? = runCatching {
    when {
      body == null -> null
      path.endsWith("/teams") -> parseTeams(body).takeIf { it.isNotEmpty() }?.let { teams ->
        val list = JSONArray(teams.map { t ->
          JSONObject().put("team", JSONObject().put("id", t.id).put("displayName", t.name).put("shortDisplayName", t.short)
              .put("abbreviation", t.abbreviation).put("nickname", t.nickname)
              .apply { if (t.color.isNotEmpty()) put("color", t.color) })
        })
        JSONObject().put("sports", JSONArray().put(JSONObject().put("leagues", JSONArray().put(JSONObject().put("teams", list)))))
            .toString()
      }
      path.endsWith("/schedule") || path.endsWith("/scoreboard") ->
        JSONObject().put("events", JSONArray(parseGames(body).map(::gameJson))).toString()
      else -> parseTeam(body)?.let { info ->
        val team = JSONObject().put("standingSummary", info.standing.orEmpty())
            .put("nextEvent", JSONArray(listOfNotNull(info.next).map(::gameJson)))
        info.record?.let { team.put("record", JSONObject().put("items", JSONArray().put(JSONObject().put("summary", it)))) }
        JSONObject().put("team", team).toString()
      }
    }
  }.getOrNull()

  private fun gameJson(g: Game): JSONObject {
    val state = when (g.state) { State.PRE -> "pre"; State.LIVE -> "in"; State.FINAL -> "post" }
    val status = JSONObject().put("type", JSONObject().put("state", state).put("completed", g.state == State.FINAL)
        .put("shortDetail", g.detail))
    val sides = JSONArray(g.sides.map { side ->
      JSONObject().put("homeAway", if (side.home) "home" else "away")
          .put("team", JSONObject().put("id", side.teamId).put("displayName", side.name))
          .apply { side.score?.let { put("score", it) } }
          .apply { side.winner?.let { put("winner", it) } }
    })
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(g.startMs))
    return JSONObject().put("id", g.id).put("date", date)
        .put("competitions", JSONArray().put(JSONObject().put("status", status).put("competitors", sides)))
  }

  /** ESPN dates: `2026-11-23T01:30Z` (no seconds) or with seconds. */
  fun parseDate(raw: String): Long? {
    if (raw.isEmpty()) return null
    for (pattern in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
      runCatching {
        return SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            .parse(raw)?.time
      }
    }
    return null
  }

  private const val HOUR_MS = 60L * 60 * 1000
  private val TEAM_ID = Regex("[0-9]{1,8}")
  private val COLOR = Regex("[0-9a-f]{6}")
}
