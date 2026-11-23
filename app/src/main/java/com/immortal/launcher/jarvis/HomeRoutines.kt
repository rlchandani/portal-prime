/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One step of a [HomeRoutine]. */
internal sealed interface RoutineStep {
  /** A house request in plain words, run exactly as `hearthpane.home_control` would ("lock all the doors"). */
  data class Home(val request: String) : RoutineStep
  /** Turn on Do Not Disturb on this Portal (Jarvis's own tool does it at the end of its turn). */
  object Quiet : RoutineStep
  /** Say when the next wake-up alarm rings. */
  object NextAlarm : RoutineStep
}

/** A named multi-step routine ("Good night"); [aliases] are other ways people say it. */
internal data class HomeRoutine(val name: String, val steps: List<RoutineStep>, val aliases: List<String> = emptyList())

/**
 * Jarvis's home routines (not the children's picture routines): two built in — Good night and Leaving, written in
 * words the Control Center already understands, so no Home Assistant scene is needed — plus the household's own,
 * made by voice and saved here. A household routine with a built-in's name replaces it. Pure apart from [load] /
 * [save].
 */
internal object HomeRoutines {
  const val MAX_ROUTINES = 12
  const val MAX_STEPS = 12
  const val MAX_NAME = 30
  const val MAX_STEP = 120
  private const val PREFS = "immortal_home_routines"
  private const val KEY = "routines"
  private const val QUIET = "quiet"
  private const val NEXT_ALARM = "next_alarm"
  private val QUIET_WORDS = setOf("quiet", "quiet mode", "do not disturb", "dnd", "turn on do not disturb",
      "do not disturb on", "turn on quiet mode", "quiet mode on")
  private val ALARM_WORDS = setOf("next alarm", "my alarm", "say my alarm", "tell me my alarm", "when is my alarm",
      "alarm time", "wake up time")

  val BUILT_IN = listOf(
      HomeRoutine("Good night", listOf(RoutineStep.Home("turn off all the lights"), RoutineStep.Home("lock all the doors"),
          RoutineStep.Home("arm the alarm for the night"), RoutineStep.Quiet, RoutineStep.NextAlarm),
          aliases = listOf("goodnight", "night", "bedtime", "going to bed", "going to sleep")),
      HomeRoutine("Leaving", listOf(RoutineStep.Home("turn off all the lights"), RoutineStep.Home("lock all the doors"),
          RoutineStep.Home("close the garage"), RoutineStep.Home("arm the alarm away")),
          aliases = listOf("i m leaving", "im leaving", "leave", "leaving home", "heading out", "going out", "away",
              "we re leaving", "were leaving", "bye")),
  )

  /** "Good-night routine!" → "good night"; "I'm leaving" → "i m leaving". */
  fun key(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
      .removeSuffix(" routine").removePrefix("the ").removePrefix("my ").removePrefix("run ").trim()

  /** The routine called [name]: the household's first, then a built-in (by name or alias). */
  fun find(saved: List<HomeRoutine>, name: String): HomeRoutine? {
    val k = key(name)
    if (k.isEmpty()) return null
    fun same(a: String, b: String) = a == b || a.replace(" ", "") == b.replace(" ", "")
    saved.firstOrNull { same(key(it.name), k) }?.let { return it }
    val builtIn = BUILT_IN.firstOrNull { r -> same(key(r.name), k) || r.aliases.any { same(key(it), k) } } ?: return null
    // "goodnight" still finds the household's own Good night when it replaced the built-in.
    return saved.firstOrNull { key(it.name) == key(builtIn.name) } ?: builtIn
  }

  /** Every routine Jarvis can run: the household's, then the built-ins they don't replace. */
  fun all(saved: List<HomeRoutine>): List<HomeRoutine> =
      saved + BUILT_IN.filter { b -> saved.none { key(it.name) == key(b.name) } }

  /** A step from the user's words: Do Not Disturb, the next alarm, or a house request. Null when blank. */
  fun step(raw: String): RoutineStep? {
    val text = raw.trim().take(MAX_STEP)
    if (text.isEmpty()) return null
    val k = key(text)
    return when (k) {
      in QUIET_WORDS -> RoutineStep.Quiet
      in ALARM_WORDS -> RoutineStep.NextAlarm
      else -> RoutineStep.Home(text)
    }
  }

  fun encode(routines: List<HomeRoutine>): String = JSONArray().apply {
    routines.forEach { r ->
      put(JSONObject().put("name", r.name).put("steps", JSONArray(r.steps.map {
        when (it) {
          is RoutineStep.Home -> it.request
          RoutineStep.Quiet -> "#$QUIET"
          RoutineStep.NextAlarm -> "#$NEXT_ALARM"
        }
      })))
    }
  }.toString()

  fun decode(raw: String?): List<HomeRoutine> {
    val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
      val o = array.optJSONObject(i) ?: return@mapNotNull null
      val name = o.optString("name").trim().take(MAX_NAME).ifEmpty { return@mapNotNull null }
      val stepsJson = o.optJSONArray("steps") ?: return@mapNotNull null
      val steps = (0 until stepsJson.length()).mapNotNull { j ->
        when (val s = stepsJson.optString(j)) {
          "#$QUIET" -> RoutineStep.Quiet
          "#$NEXT_ALARM" -> RoutineStep.NextAlarm
          else -> s.trim().take(MAX_STEP).ifEmpty { null }?.let(RoutineStep::Home)
        }
      }.take(MAX_STEPS)
      if (steps.isEmpty()) null else HomeRoutine(name, steps)
    }.take(MAX_ROUTINES)
  }

  fun load(context: Context): List<HomeRoutine> =
      decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

  fun save(context: Context, routines: List<HomeRoutine>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(routines)).apply()
  }
}
