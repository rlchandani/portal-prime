/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import kotlin.math.roundToInt

/** Thermostat tile maths and words: dial range, step, labels. Pure, unit-tested ([HaClimateTest]). */
object HaClimate {

  /** Targets above this are Fahrenheit (no HA install sets a 40 °C target). */
  private const val FAHRENHEIT_ABOVE = 40f
  private const val C_MIN = 10f
  private const val C_MAX = 30f
  private const val F_MIN = 50f
  private const val F_MAX = 86f
  private const val C_STEP = 0.5f
  private const val F_STEP = 1f
  private const val FALLBACK_TARGET = 21f

  fun fahrenheit(target: Float): Boolean = target > FAHRENHEIT_ABOVE

  /** Where [target] sits on the dial's 270° arc, 0..1. */
  fun fraction(target: Float): Float {
    val (lo, hi) = if (fahrenheit(target)) F_MIN to F_MAX else C_MIN to C_MAX
    return ((target - lo) / (hi - lo)).coerceIn(0f, 1f)
  }

  /** HA's own `target_temp_step` when given, else 0.5 °C / 1 °F. */
  fun step(d: HaDashboardApi.Detail, target: Float): Float =
      d.tempStep?.takeIf { it > 0f } ?: if (fahrenheit(target)) F_STEP else C_STEP

  fun target(d: HaDashboardApi.Detail): Float = d.targetTemp ?: d.currentTemp ?: FALLBACK_TARGET

  /** [value] nudged by [delta] and snapped to the step, so 20.5 + 0.5 never reads 20.999. */
  fun stepped(value: Float, delta: Float, step: Float): Float = ((value + delta) / step).roundToInt() * step

  /** "21°" or "20.5°". */
  fun degrees(value: Float): String =
      if (value == value.roundToInt().toFloat()) "${value.roundToInt()}°" else String.format(Locale.US, "%.1f°", value)

  /** "21.0°" for the stepper (always one decimal on Celsius so the width holds still). */
  fun stepperText(value: Float): String =
      if (fahrenheit(value)) "${value.roundToInt()}°" else String.format(Locale.US, "%.1f°", value)

  /** "Heat · target 21°". */
  fun statusLine(d: HaDashboardApi.Detail, target: Float): String {
    val mode = when (d.state) {
      "heat" -> "Heat"; "cool" -> "Cool"; "heat_cool", "auto" -> "Auto"; "off" -> "Off"; "dry" -> "Dry"; "fan_only" -> "Fan"
      else -> d.state.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
    return if (d.state == "off") mode else "$mode · target ${degrees(target)}"
  }

  /** The action chip ("Heating"), or null when idle/unknown. */
  fun actionChip(d: HaDashboardApi.Detail): String? = when (d.hvacAction) {
    "heating" -> "Heating"; "cooling" -> "Cooling"; "drying" -> "Drying"; "fan" -> "Fan"
    else -> null
  }
}
