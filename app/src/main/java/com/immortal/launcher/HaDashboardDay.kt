/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The dashboard's time-of-day rules: greeting, which scene is "Now", scene glyphs and the header
 * subline. Pure (hours and counts in, words out), unit-tested ([HaDashboardDayTest]).
 */
object HaDashboardDay {

  /** Morning 05–11, afternoon 12–16, evening 17–20, night 21–04 (device clock). */
  enum class Part(val greeting: String) {
    MORNING("Good morning"), AFTERNOON("Good afternoon"), EVENING("Good evening"), NIGHT("Good night")
  }

  private const val MORNING_FROM = 5
  private const val AFTERNOON_FROM = 12
  private const val EVENING_FROM = 17
  private const val NIGHT_FROM = 21

  fun partOf(hour: Int): Part = when (hour) {
    in MORNING_FROM until AFTERNOON_FROM -> Part.MORNING
    in AFTERNOON_FROM until EVENING_FROM -> Part.AFTERNOON
    in EVENING_FROM until NIGHT_FROM -> Part.EVENING
    else -> Part.NIGHT
  }

  /** A scene as the side column shows it. [now] marks the one for this part of the day. */
  data class SceneChip(val id: String, val name: String, val icon: String, val now: Boolean)

  /** Used when HA has no `scene.*` entities (the pre-1.327 fixed set). */
  val FALLBACK_SCENES = listOf(
      HaDashboardApi.Scene("scene.evening", "Evening"),
      HaDashboardApi.Scene("scene.night", "Night"),
      HaDashboardApi.Scene("scene.movie", "Movie"),
      HaDashboardApi.Scene("scene.leave", "Leave"),
  )

  private val PART_WORDS = mapOf(
      Part.MORNING to listOf("morning", "wake", "sunrise", "breakfast"),
      Part.AFTERNOON to listOf("afternoon", "daytime", "away", "leave", "lunch"),
      Part.EVENING to listOf("evening", "sunset", "dinner", "dusk"),
      Part.NIGHT to listOf("night", "bedtime", "sleep", "goodnight"),
  )

  private val ICON_WORDS = listOf(
      "morning" to "sunrise", "wake" to "sunrise", "sunrise" to "sunrise",
      "evening" to "sunset", "sunset" to "sunset", "dinner" to "sunset",
      "night" to "moon", "sleep" to "moon", "bed" to "moon",
      "movie" to "film", "film" to "film", "tv" to "film", "cinema" to "film",
      "away" to "leave", "leave" to "leave", "out" to "leave",
      "focus" to "sun", "work" to "sun", "read" to "sun", "day" to "sun",
  )

  /** The part of the day a scene belongs to, from its name; null for "Movie", "Focus" and the like. */
  fun sceneWhen(name: String): Part? {
    val n = name.lowercase()
    return PART_WORDS.entries.firstOrNull { (_, words) -> words.any { it in n } }?.key
  }

  fun sceneIcon(name: String): String {
    val n = name.lowercase()
    return ICON_WORDS.firstOrNull { (word, _) -> word in n }?.second ?: "sparkle"
  }

  /** HA's scenes (or the fallback set), this part of the day's first, the rest in HA order. */
  fun scenes(fromHa: List<HaDashboardApi.Scene>, part: Part): List<SceneChip> {
    val source = fromHa.ifEmpty { FALLBACK_SCENES }
    val chips = source.map { SceneChip(it.id, it.name, sceneIcon(it.name), sceneWhen(it.name) == part) }
    val firstNow = chips.indexOfFirst { it.now }
    if (firstNow < 0) return chips
    // Only one "Now": the first match leads, any other match reads as an ordinary scene.
    val lead = chips[firstNow]
    return listOf(lead) + chips.filterIndexed { i, _ -> i != firstNow }.map { it.copy(now = false) }
  }

  /** "6:48" or "18:48". */
  fun clock(hour: Int, minute: Int, use24h: Boolean): String {
    val h = if (use24h) hour else (hour % 12).let { if (it == 0) 12 else it }
    val hh = if (use24h) "%02d".format(hour) else h.toString()
    return "$hh:${"%02d".format(minute)}"
  }

  fun lightsPhrase(lit: Int): String = when (lit) {
    0 -> "All lights off"
    1 -> "1 light on"
    else -> "$lit lights on"
  }

  /** "Updated just now" / "Last updated 4 min ago" for the offline banner and subline. */
  fun ageText(minutes: Long): String = when {
    minutes < 1 -> "Last updated just now"
    minutes < MINUTES_PER_HOUR -> "Last updated $minutes min ago"
    else -> "Last updated ${minutes / MINUTES_PER_HOUR} h ago"
  }

  private const val MINUTES_PER_HOUR = 60

  /** What the subline says besides the clock and the lights. */
  data class Facts(val lit: Int, val lock: String?, val insideTemp: String?, val offlineMinutes: Long?,
      val hasNightScene: Boolean)

  /** "6:48 · 5 lights on · Front door locked · 20.5° inside", plus a wind-down hint at night. */
  fun subline(clock: String, part: Part, f: Facts): String {
    val rest = if (f.offlineMinutes != null) listOf(ageText(f.offlineMinutes))
        else listOfNotNull(f.lock, f.insideTemp?.let { "$it inside" })
    val hint = if (part == Part.NIGHT && f.lit > 0 && f.hasNightScene && f.offlineMinutes == null)
        listOf("tap Night to wind down") else emptyList()
    return (listOf(clock, lightsPhrase(f.lit)) + rest + hint).joinToString(" · ")
  }

  /** "Front door locked" from the house's first lock, null when there is none. */
  fun lockFact(locks: List<HaDashboardApi.Detail>): String? {
    val lock = locks.firstOrNull { it.domain == "lock" && it.state != "unavailable" } ?: return null
    return "${lock.friendlyName} ${HaEntityLook.status(lock).lowercase()}"
  }
}
