/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Shared fixtures: Monday 9 November 2026 (epoch day 20766), local wall-clock milliseconds. */
internal object RoutineFixtures {
  const val MON = 20766
  fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
      RoutineDates.localMs(day, hour * 60 + minute) + second * 1000L

  fun cards(vararg pics: String) = pics.mapIndexed { i, p -> RoutineCard("c$i", p, RoutinePictures.defaultLabel(p)) }

  fun morning(kids: List<String> = emptyList()) = Routine(
      id = "r1", name = "School morning", kids = kids,
      cards = cards("shirt", "bowl", "toothbrush", "shoes", "backpack"),
      days = listOf(true, true, true, true, true, false, false), start = 435, end = 475, enabled = true, skips = emptyList())

  fun bedtime() = Routine(
      id = "r2", name = "Bedtime", kids = emptyList(), cards = cards("bath", "pyjamas", "toothbrush", "book"),
      days = List(7) { true }, start = 1170, end = 1200, enabled = true, skips = emptyList())

  val kids = listOf(RoutineKid("maya", "Maya", "fox"), RoutineKid("leo", "Leo", "owl"))

  fun doc(vararg routines: Routine, rev: Long = 3) = RoutineDoc(rev, routines.toList(), kids)

  fun routineJson(
      id: String = "r9", name: String = "Swim", start: Int = 600, end: Int = 630,
      days: List<Int> = listOf(0, 0, 0, 0, 0, 1, 1),
      cards: JSONArray = JSONArray().put(JSONObject().put("id", "a").put("pic", "bottle").put("label", "Water bottle")),
  ): JSONObject = JSONObject().put("id", id).put("name", name).put("kids", JSONArray()).put("cards", cards)
      .put("days", JSONArray(days)).put("start", start).put("end", end).put("enabled", true).put("skips", JSONArray())
}
