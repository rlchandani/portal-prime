/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

/** How a registry choice is drawn in the split-view Settings. */
enum class SettingsChoiceStyle {
  /** A value row that opens a list (long or open-ended choices). */
  LIST,
  /** Big one-tap tiles in a track, for 2–5 short options. */
  TILES,
  /** Light / Dark preview tiles. */
  APPEARANCE,
}

/** One tile of a preset row; [custom] opens the existing number editor instead of applying. */
data class SettingsPresetTile(val value: Int, val label: String, val custom: Boolean = false)

/**
 * Presentation-only policy shared by the renderer and search: which control a row uses, its
 * shorter title, and its one-tap presets. Pure (no Android), never changes what is stored.
 */
object SettingsChoicePolicy {
  const val MIN_TILE_OPTIONS = 2
  const val MAX_TILE_OPTIONS = 5
  const val MAX_TILE_LABEL_CHARS = 12
  /** Up to this many short options sit beside the title; more go full width below it. */
  const val MAX_INLINE_OPTIONS = 3
  const val MAX_INLINE_LABEL_CHARS = 18
  const val CUSTOM_LABEL = "Custom"

  private const val SECONDS_PER_MINUTE = 60
  private const val MINUTES_PER_HOUR = 60

  private val appearanceControls = setOf("immortal.settingsTheme")

  /** Titles the approved mock shortened; the registry (phone schema, diagnostics) keeps its own. */
  private val titles = mapOf(
      "immortal.clockFormat" to "Time format",
      "screensaver.enabled" to "Photo frame screensaver",
      "screensaver.mode" to "Style",
      "screensaver.gestureWave" to "Wave to wake",
      "sunrise.rampMinutes" to "Ramp",
      "chime.volume" to "Chime & voice volume",
  )

  private val tileLabels = mapOf(
      "immortal.weatherUnit" to mapOf("f" to "°F", "c" to "°C"),
  )

  /** Number rows with one-tap presets; a Custom tile keeps every other stored value reachable. */
  private val intPresets = mapOf(
      "screensaver.intervalSec" to listOf(10, 30, 60, 300),
      "sunrise.rampMinutes" to listOf(10, 20, 30),
      "chime.sunriseVariant" to listOf(0, 1),
      "calendar.size" to listOf(0, 1, 2),
      "screensaver.sleepTimerMin" to listOf(15, 30, 60, 120),
  )

  /** Levels drawn as sliders (the value is applied when the finger lifts). */
  private val sliders = setOf("chime.volume", "screensaver.soundscapeVolume", "screensaver.nightClockBrightness")

  private val secondsLabels = setOf("screensaver.intervalSec")
  private val minutesLabels = setOf("screensaver.sleepTimerMin")

  fun displayTitle(domain: String, key: String, title: String): String = titles["$domain.$key"] ?: title

  fun enumStyle(domain: String, key: String, labels: List<String>): SettingsChoiceStyle = when {
    "$domain.$key" in appearanceControls -> SettingsChoiceStyle.APPEARANCE
    tilesFit(labels) -> SettingsChoiceStyle.TILES
    else -> SettingsChoiceStyle.LIST
  }

  fun tilesFit(labels: List<String>): Boolean =
      labels.size in MIN_TILE_OPTIONS..MAX_TILE_OPTIONS && labels.all { it.length <= MAX_TILE_LABEL_CHARS }

  /** True when the tiles need the full row width below the title. */
  fun stacked(labels: List<String>): Boolean =
      labels.size > MAX_INLINE_OPTIONS || labels.sumOf { it.length } > MAX_INLINE_LABEL_CHARS

  fun tileLabel(domain: String, key: String, value: String, label: String): String =
      tileLabels["$domain.$key"]?.get(value) ?: label

  fun isSlider(domain: String, key: String): Boolean = "$domain.$key" in sliders

  fun hasPresets(domain: String, key: String): Boolean = "$domain.$key" in intPresets

  /**
   * The tiles for a number row: its presets inside [min]..[max], plus a Custom tile when the
   * presets don't cover every value the editor allows. A stored [value] that is not a preset is
   * shown (by its value) on the Custom tile, which then stays selected. [format] is the registry's
   * own label.
   */
  fun presetTiles(domain: String, key: String, min: Int, max: Int, step: Int, value: Int,
      format: (Int) -> String): List<SettingsPresetTile> {
    val id = "$domain.$key"
    val values = intPresets[id].orEmpty().filter { it in min..max }
    val label: (Int) -> String = when (id) {
      in secondsLabels -> ::secondsLabel
      in minutesLabels -> ::minutesLabel
      else -> format
    }
    val tiles = values.map { SettingsPresetTile(it, label(it)) }
    if (coversRange(values, min, max, step)) return tiles
    val custom = if (value in values) CUSTOM_LABEL else label(value)
    return tiles + SettingsPresetTile(value, custom, custom = true)
  }

  /** Index of the tile showing [value]: its preset, else the Custom tile, else none (-1). */
  fun selectedTile(tiles: List<SettingsPresetTile>, value: Int): Int =
      tiles.indexOfFirst { !it.custom && it.value == value }.takeIf { it >= 0 }
          ?: tiles.indexOfFirst { it.custom }

  fun coversRange(values: List<Int>, min: Int, max: Int, step: Int): Boolean {
    if (step <= 0 || max < min) return false
    return (min..max step step).all { it in values }
  }

  /** Snaps a dragged slider position to the editor's own step and bounds. */
  fun snap(raw: Float, min: Int, max: Int, step: Int): Int {
    if (max <= min) return min
    val safeStep = step.coerceAtLeast(1)
    val steps = Math.round((raw.coerceIn(min.toFloat(), max.toFloat()) - min) / safeStep)
    return (min + steps * safeStep).coerceIn(min, max)
  }

  fun secondsLabel(seconds: Int): String = when {
    seconds < SECONDS_PER_MINUTE || seconds % SECONDS_PER_MINUTE != 0 -> "$seconds s"
    else -> "${seconds / SECONDS_PER_MINUTE} min"
  }

  fun minutesLabel(minutes: Int): String = when {
    minutes < MINUTES_PER_HOUR || minutes % MINUTES_PER_HOUR != 0 -> "$minutes min"
    minutes == MINUTES_PER_HOUR -> "1 hour"
    else -> "${minutes / MINUTES_PER_HOUR} hours"
  }
}
