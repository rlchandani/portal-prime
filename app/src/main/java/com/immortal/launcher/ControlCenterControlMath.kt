/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure maths behind the device controls (unit tested): the big brightness bar and horizontal
 * sliders (touch position ↔ percent), the thermostat's 270° dial (touch angle ↔ temperature),
 * the send throttle used while dragging, white/colour swatches and fan speed steps.
 */
internal object CcControlMath {
  private const val PCT = 100

  /** Vertical bar: the bottom is 0 %, the top 100 %. */
  fun pctFromVertical(y: Float, height: Float): Int {
    if (height <= 0f) return 0
    return ((1f - y / height) * PCT).roundToInt().coerceIn(0, PCT)
  }

  /** Horizontal slider: the left edge is 0 %, the right 100 %. */
  fun pctFromHorizontal(x: Float, width: Float): Int {
    if (width <= 0f) return 0
    return (x / width * PCT).roundToInt().coerceIn(0, PCT)
  }

  // --- thermostat dial ------------------------------------------------------------------------

  /** The arc starts bottom-left (135° clockwise from 3 o'clock) and sweeps 270°, like the mock. */
  const val DIAL_START_DEG = 135f
  const val DIAL_SPAN_DEG = 270f
  private const val FULL_DEG = 360f
  private const val GAP_MID_DEG = DIAL_SPAN_DEG + (FULL_DEG - DIAL_SPAN_DEG) / 2f

  /**
   * Fraction 0…1 along the arc for a touch at ([dx], [dy]) from the dial centre (screen axes, y
   * down). Touches in the bottom gap snap to the nearer end so the knob never jumps across.
   */
  fun dialFraction(dx: Float, dy: Float): Float {
    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val rel = ((deg - DIAL_START_DEG) % FULL_DEG + FULL_DEG) % FULL_DEG
    return when {
      rel <= DIAL_SPAN_DEG -> rel / DIAL_SPAN_DEG
      rel < GAP_MID_DEG -> 1f
      else -> 0f
    }
  }

  /** Temperature for [fraction] in [min]…[max], snapped to [step]. */
  fun dialValue(fraction: Float, min: Double, max: Double, step: Double): Double {
    if (max <= min) return min
    val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
    val s = if (step > 0) step else 1.0
    return (Math.round(raw / s) * s).coerceIn(min, max)
  }

  fun dialFractionOf(value: Double, min: Double, max: Double): Float =
      if (max <= min) 0f else ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)

  /** Point on the arc at [fraction] for a circle of [radius] around ([cx], [cy]). */
  fun dialPoint(fraction: Float, radius: Float, cx: Float, cy: Float): Pair<Float, Float> {
    val a = Math.toRadians((DIAL_START_DEG + DIAL_SPAN_DEG * fraction.coerceIn(0f, 1f)).toDouble())
    return (cx + radius * cos(a).toFloat()) to (cy + radius * sin(a).toFloat())
  }

  private const val F_MIN = 45.0
  private const val F_MAX = 95.0
  /** A setpoint above this can only be °F (no home is heated to 45 °C). */
  private const val F_HINT = 40.0

  /**
   * The dial's range: HA's min_temp/max_temp, else a range in the right unit (°F 45–95, °C 7–35,
   * guessed from the values when HA doesn't say), always wide enough to include the setpoint.
   */
  fun tempRange(e: CcEntity, fahrenheit: Boolean): Pair<Double, Double> {
    val f = fahrenheit || listOfNotNull(e.targetTemp, e.currentTemp).any { it > F_HINT }
    val (dMin, dMax) = if (f) F_MIN to F_MAX else CcEntity.DEFAULT_MIN_TEMP to CcEntity.DEFAULT_MAX_TEMP
    val lo = (e.attrs["min_temp"] as? Number)?.toDouble() ?: dMin
    val hi = (e.attrs["max_temp"] as? Number)?.toDouble() ?: dMax
    val (a, b) = if (hi > lo) lo to hi else dMin to dMax
    val t = e.targetTemp ?: return a to b
    return minOf(a, t) to maxOf(b, t)
  }

  // --- swatches -------------------------------------------------------------------------------

  @androidx.compose.runtime.Immutable
  data class White(val kelvin: Int, val label: String)

  private val WHITE_PRESETS = listOf(White(2200, "Candle"), White(2700, "Warm"), White(3000, "Soft"),
      White(4000, "Neutral"), White(5500, "Daylight"))
  private const val MIN_PRESETS = 3
  private const val SWATCHES = 5
  private const val KELVIN_MATCH = 200
  private const val COLOUR_MATCH = 48

  /** The mock's five whites that the bulb can make; evenly spread presets for a narrow range. */
  fun whites(minK: Int, maxK: Int): List<White> {
    if (maxK <= minK) return emptyList()
    val fit = WHITE_PRESETS.filter { it.kelvin in minK..maxK }
    if (fit.size >= MIN_PRESETS) return fit
    val stepK = (maxK - minK) / (SWATCHES - 1)
    return (0 until SWATCHES).map { i ->
      val k = minK + stepK * i
      White(k, when { i == 0 -> "Warmest"; i == SWATCHES - 1 -> "Coolest"; else -> "${k}K" })
    }
  }

  /** The mock's colour swatches (Red, Pink, Violet, Blue, Teal), packed 0xRRGGBB. */
  val COLOURS = listOf(0xFF5A6A to "Red", 0xFF7EB6 to "Pink", 0x9B6BFF to "Violet", 0x4F8CFF to "Blue", 0x3FD6C0 to "Teal")

  /** Index of the white the light shows now (nearest within 200 K, in colour-temp mode). */
  fun selectedWhite(e: CcEntity, whites: List<White>): Int {
    if (!e.isOn || e.attrs["color_mode"] != "color_temp") return -1
    val k = e.colorTempK ?: return -1
    val best = whites.withIndex().minByOrNull { kotlin.math.abs(it.value.kelvin - k) } ?: return -1
    return if (kotlin.math.abs(best.value.kelvin - k) <= KELVIN_MATCH) best.index else -1
  }

  /** Index of the colour the light shows now (close RGB match, not in colour-temp mode). */
  fun selectedColour(e: CcEntity): Int {
    if (!e.isOn || e.attrs["color_mode"] == "color_temp") return -1
    val rgb = e.rgb ?: return -1
    return COLOURS.indexOfFirst { (c, _) -> rgbDistance(c, rgb) <= COLOUR_MATCH }
  }

  private fun rgbDistance(a: Int, b: Int): Int {
    fun ch(v: Int, shift: Int) = (v shr shift) and 0xff
    return maxOf(kotlin.math.abs(ch(a, 16) - ch(b, 16)), kotlin.math.abs(ch(a, 8) - ch(b, 8)), kotlin.math.abs(ch(a, 0) - ch(b, 0)))
  }

  // --- fans -----------------------------------------------------------------------------------

  private const val DEFAULT_FAN_SPEEDS = 3
  private const val MAX_FAN_SEGMENTS = 5

  /** Percentages for the fan's speed segments (0 = off), from HA's `percentage_step`. */
  fun fanSpeeds(e: CcEntity): List<Int> {
    val step = (e.attrs["percentage_step"] as? Number)?.toDouble()?.takeIf { it > 0 }
    val n = step?.let { Math.round(PCT / it).toInt() } ?: DEFAULT_FAN_SPEEDS
    if (n !in 1..MAX_FAN_SEGMENTS) return emptyList()
    return listOf(0) + (1..n).map { Math.round(it * PCT.toDouble() / n).toInt() }
  }

  /** Segment the fan is on now: the nearest speed for its percentage, 0 when off. */
  fun fanSpeedIndex(e: CcEntity, speeds: List<Int>): Int {
    if (!e.isOn || speeds.isEmpty()) return 0
    val p = e.percentage ?: return speeds.lastIndex
    return speeds.withIndex().drop(1).minByOrNull { kotlin.math.abs(it.value - p) }?.index ?: 0
  }

  fun fanLabels(count: Int): List<String> = when (count) {
    4 -> listOf("Off", "Low", "Medium", "High")
    3 -> listOf("Off", "Low", "High")
    2 -> listOf("Off", "On")
    else -> listOf("Off") + (1 until count).map { "$it" }
  }
}

/**
 * Sends a dragged value at most every [intervalMs] (≥ 250 ms) so HA isn't flooded, and never
 * repeats a value it already sent; the final value is always sent on release ([final]).
 */
internal data class CcThrottle(val lastAt: Long? = null, val lastValue: Int? = null, val intervalMs: Long = INTERVAL_MS) {
  fun shouldSend(now: Long, value: Int, final: Boolean = false): Boolean = when {
    value == lastValue -> false
    final -> true
    else -> lastAt == null || now - lastAt >= intervalMs
  }

  fun sent(now: Long, value: Int) = copy(lastAt = now, lastValue = value)

  companion object {
    const val INTERVAL_MS = 250L
  }
}
