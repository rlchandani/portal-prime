/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** Ephemeral diagnostic commands, never user preferences or unbounded recurring work. */
internal data class FramePerformanceRequest(val action: String, val durationSec: Int, val phase: String,
    val sampleMain: Boolean = false, val sampleSettingsWork: Boolean = true) {
  // Ordinary captures retain the established tail threshold; sampled attribution needs shorter frames.
  internal val slowFrameThresholdNs: Long get() = if (sampleMain) 32_000_000L else 100_000_000L
  init {
    require((action == "stop" && durationSec == 0 && phase == "all" && !sampleMain) ||
        (action == "start" && durationSec in 10..900 && phase in phases && (!sampleMain || durationSec <= 60)))
  }
  companion object {
    private val phases = setOf("all", "home", "apps", "tools", "settings", "slideshow", "launch", "idle")
    fun parse(body: JSONObject): FramePerformanceRequest? {
      val action = body.opt("action") as? String ?: return null
      val keys = body.keys().asSequence().toSet()
      if (action == "stop") return if (keys == setOf("action"))
        FramePerformanceRequest("stop", 0, "all") else null
      if (action != "start" || !setOf("action", "durationSec", "phase", "sampleMain", "sampleSettingsWork").containsAll(keys)) return null
      val raw = if (body.has("durationSec")) body.opt("durationSec") else 60
      val duration = when (raw) { is Int -> raw.toLong(); is Long -> raw; else -> return null }
      if (duration !in 10L..900L) return null
      val sampleMain = if (body.has("sampleMain")) body.opt("sampleMain") as? Boolean ?: return null else false
      if (sampleMain && duration > 60) return null
      val sampleSettingsWork = if (body.has("sampleSettingsWork"))
        body.opt("sampleSettingsWork") as? Boolean ?: return null else true
      val phase = if (body.has("phase")) body.opt("phase") as? String ?: return null else "all"
      if (phase !in phases) return null
      return FramePerformanceRequest(action, duration.toInt(), phase, sampleMain, sampleSettingsWork)
    }
  }
}
