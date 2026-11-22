/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale

/**
 * How one HA entity reads on a dashboard tile: its glyph, its words, its tone, what a tap calls,
 * and the optimistic state that tap shows. Pure, unit-tested ([HaEntityLookTest]).
 *
 * Fixes the old "an open cover / unlocked lock reads Off" bug: each domain has its own active
 * state ("open", "unlocked"), not a comparison with "on".
 */
object HaEntityLook {

  /** Tile colouring: amber "on", red attention (unlocked, open door), or plain. */
  enum class Tone { LIT, ALERT, PLAIN }

  /** A Home Assistant service call: `POST /api/services/<domain>/<service>`. */
  data class Service(val domain: String, val service: String)

  private val GLOW_DOMAINS = setOf("light", "switch", "fan", "input_boolean")
  private const val UNAVAILABLE = "unavailable"

  /** True when the entity's switch shows "on": open cover, unlocked lock, on light/switch/fan. */
  fun isActive(d: HaDashboardApi.Detail): Boolean = when (d.domain) {
    "lock" -> d.state == "unlocked" || d.state == "unlocking" || d.state == "open"
    "cover" -> d.state == "open" || d.state == "opening"
    "binary_sensor" -> d.state == "on"
    "media_player" -> d.state == "playing"
    else -> d.state.equals("on", true)
  }

  fun tone(d: HaDashboardApi.Detail): Tone = when {
    d.domain == "lock" && isActive(d) -> Tone.ALERT
    d.domain == "binary_sensor" && isActive(d) -> Tone.ALERT
    d.domain in GLOW_DOMAINS && isActive(d) -> Tone.LIT
    else -> Tone.PLAIN
  }

  /** The tile's status words. */
  fun status(d: HaDashboardApi.Detail): String {
    if (d.state == UNAVAILABLE) return "Unavailable"
    return when (d.domain) {
      "lock" -> lockWord(d.state)
      "cover" -> coverWord(d.state)
      "binary_sensor" -> binaryWord(d)
      "light" -> if (!isActive(d)) "Off" else d.brightnessPct?.let { "$it%" } ?: "On"
      else -> if (isActive(d)) "On" else "Off"
    }
  }

  private fun lockWord(state: String) = when (state) {
    "locked" -> "Locked"; "unlocked" -> "Unlocked"; "locking" -> "Locking…"; "unlocking" -> "Unlocking…"
    "jammed" -> "Jammed"; "open" -> "Open"; else -> title(state)
  }

  private fun coverWord(state: String) = when (state) {
    "open" -> "Open"; "closed" -> "Closed"; "opening" -> "Opening…"; "closing" -> "Closing…"; else -> title(state)
  }

  private fun binaryWord(d: HaDashboardApi.Detail): String {
    val on = d.state == "on"
    return when (d.deviceClass) {
      "moisture" -> if (on) "Wet" else "Dry"
      "smoke", "gas", "carbon_monoxide" -> if (on) "Detected" else "Clear"
      else -> if (on) "Open" else "Closed"
    }
  }

  private fun title(s: String) = s.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

  /** Glyph name for [HaDashboardIcons]. */
  fun icon(d: HaDashboardApi.Detail): String = when (d.domain) {
    "light" -> if (d.brightnessPct != null) "bulb" else "lamp"
    "switch", "input_boolean" -> "plug"
    "fan" -> "fan"
    "lock" -> if (isActive(d)) "unlock" else "lock"
    "cover" -> "blinds"
    "climate" -> "flame"
    "media_player" -> "speaker"
    "binary_sensor" -> binaryIcon(d.deviceClass)
    "sensor" -> sensorIcon(d)
    else -> "plug"
  }

  private fun binaryIcon(cls: String?) = when (cls) {
    "window" -> "window"; "garage_door" -> "garage"; "moisture" -> "drop"; "smoke", "gas", "carbon_monoxide" -> "alert"
    else -> "door"
  }

  private fun sensorIcon(d: HaDashboardApi.Detail) = when {
    d.unit == "%" || d.deviceClass == "humidity" -> "drop"
    d.unit.contains('°') || d.deviceClass == "temperature" -> "therm"
    d.deviceClass == "illuminance" -> "sun"
    else -> "motion"
  }

  /** The service a tap calls. Locks and covers have no `toggle` that works everywhere, so they get explicit ones. */
  fun tapService(d: HaDashboardApi.Detail): Service = when (d.domain) {
    "lock" -> Service("lock", if (isActive(d)) "lock" else "unlock")
    "cover" -> Service("cover", if (isActive(d)) "close_cover" else "open_cover")
    else -> Service(d.domain, "toggle")
  }

  /** The state a tap shows straight away (reverted if HA refuses). */
  fun tapped(d: HaDashboardApi.Detail): HaDashboardApi.Detail = when (d.domain) {
    "lock" -> d.copy(state = if (isActive(d)) "locked" else "unlocked")
    "cover" -> d.copy(state = if (isActive(d)) "closed" else "open")
    "light" -> if (isActive(d)) d.copy(state = "off")
        else d.copy(state = "on", brightnessPct = d.brightnessPct?.takeIf { it > 0 } ?: d.brightnessPct?.let { DEFAULT_ON_PCT })
    else -> d.copy(state = if (isActive(d)) "off" else "on")
  }

  /** A dimmer moved to [pct] (0 = off). */
  fun dimmed(d: HaDashboardApi.Detail, pct: Int): HaDashboardApi.Detail =
      d.copy(state = if (pct > 0) "on" else "off", brightnessPct = pct.coerceIn(0, MAX_PCT))

  private const val DEFAULT_ON_PCT = 60
  private const val MAX_PCT = 100
}
