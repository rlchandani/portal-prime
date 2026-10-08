/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal object RemoteScenePolicy {
  val keys = setOf("home", "back", "apps", "screensaver", "wake", "sleep")
  val media = setOf("playpause", "next", "prev")
  val sources = setOf("default", "folder", "local", "immich", "smb", "dav", "web", "album")
  fun validate(scenes: JSONArray, settings: (String, JSONObject) -> Boolean): RemoteCompanionValidation {
    val errors = mutableListOf<String>()
    if (scenes.length() > 20) errors += "too_many_scenes"
    val ids = mutableSetOf<String>()
    for (i in 0 until scenes.length()) {
      val scene = scenes.optJSONObject(i)
      if (scene == null) { errors += "invalid_scene"; continue }
      if (!scene.only("id", "name", "steps", "schedule") || !validId(scene.opt("id")) || !ids.add(scene.optString("id")) || !scene.opt("name").text(64) || scene.optString("name").isBlank()) errors += "invalid_scene_identity"
      val steps = scene.optJSONArray("steps")
      if (steps == null || steps.length() !in 1..16) { errors += "invalid_steps"; continue }
      var waits = 0L
      for (j in 0 until steps.length()) {
        val step = steps.optJSONObject(j)
        val valid = if (step == null) false else when (step.optString("type")) {
          "settings" -> step.only("type", "domain", "values") && step.opt("domain") is String && step.optJSONObject("values")?.let { it.length() > 0 && settings(step.getString("domain"), it) } == true
          "key" -> step.only("type", "action") && step.opt("action") in keys
          "media" -> step.only("type", "action") && step.opt("action") in media
          "launch" -> step.only("type", "packageName") && RemoteLayoutPolicy.packageName(step.opt("packageName"))
          "source" -> step.only("type", "values") && validSource(step.optJSONObject("values"))
          "wait" -> step.only("type", "ms") && step.opt("ms").integer()?.let { waits += it; it in 0..5000 } == true
          else -> false
        }
        if (!valid) errors += "invalid_step:$i:$j"
      }
      if (waits > 5000) errors += "wait_budget_exceeded"
      if (scene.has("schedule") && !scene.isNull("schedule") && !validSchedule(scene.optJSONObject("schedule"))) errors += "invalid_schedule"
    }
    return RemoteCompanionValidation(errors.distinct())
  }
  fun validSource(values: JSONObject?): Boolean {
    if (values == null || !values.only("source", "folderPath")) return false
    val source = values.opt("source") ?: if (values.has("folderPath")) "folder" else return false
    if (source !in sources) return false
    return if (source in setOf("folder", "local")) values.opt("folderPath").text(512) && values.optString("folderPath").startsWith("/sdcard/") && !values.optString("folderPath").split('/').contains("..") else !values.has("folderPath")
  }
  fun validId(id: Any?): Boolean = id is String && Regex("[A-Za-z0-9_-]{1,64}").matches(id)
  fun validSchedule(s: JSONObject?): Boolean {
    if (s == null || !s.only("enabled", "hour", "minute", "days") || s.opt("enabled") !is Boolean || s.opt("hour").integer() !in 0L..23L || s.opt("minute").integer() !in 0L..59L) return false
    val days = s.optJSONArray("days") ?: return false
    val parsed = (0 until days.length()).map { days.opt(it).integer() }
    return parsed.size in 1..7 && parsed.distinct().size == parsed.size && parsed.all { it in 1L..7L }
  }
}
