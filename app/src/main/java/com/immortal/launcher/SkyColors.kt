/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Maps the time of day to a sky-coloured gradient (top → bottom), driven by the
 * real sunrise/sunset so the launcher background tells time ambiently: dawn pinks,
 * midday blue, dusk orange, night near-black. Pure + unit-testable.
 */
object SkyColors {

  // Palette anchors (top, bottom) for each phase of the day.
  private val NIGHT = Color(0xFF0B1026) to Color(0xFF05060E)
  private val DAWN = Color(0xFF2A3A6B) to Color(0xFFEF9A6B) // deep blue over warm pink
  private val MORNING = Color(0xFF3A7BD5) to Color(0xFF8FD3F4)
  private val MIDDAY = Color(0xFF2980B9) to Color(0xFF6DD5FA)
  private val EVENING = Color(0xFF3A7BD5) to Color(0xFF8FD3F4)
  private val DUSK = Color(0xFF2C3E66) to Color(0xFFE96443) // blue over sunset orange
  private val TWILIGHT = Color(0xFF1A1F3D) to Color(0xFF3A2A4D)

  /** Gradient (top, bottom) for [nowMin] given today's [sunriseMin]/[sunsetMin]
   * (all minutes-of-day, 0..1439). Blends smoothly between phase anchors. */
  fun gradientFor(nowMin: Int, sunriseMin: Int, sunsetMin: Int): Pair<Color, Color> {
    val daylight = sunsetMin - sunriseMin
    val shortDay = daylight in 1 until 480
    // Keep all daytime anchors in order even when winter daylight is brief.
    val phaseScale = if (shortDay) daylight / 480f else 1f
    val dawnStart = (sunriseMin - 40).toFloat()
    val dawnEnd = sunriseMin + 40 * phaseScale
    val morningEnd = sunriseMin + 120 * phaseScale
    val midday = if (shortDay) (sunriseMin + sunsetMin) / 2f
        else ((sunriseMin + sunsetMin) / 2).toFloat()
    val eveningStart = sunsetMin - 120 * phaseScale
    val duskStart = sunsetMin - 40 * phaseScale
    val duskEnd = (sunsetMin + 40).toFloat()
    val twilightEnd = (sunsetMin + 80).toFloat()

    return when {
      nowMin < dawnStart -> NIGHT
      nowMin < dawnEnd -> blend(NIGHT, DAWN, frac(nowMin, dawnStart, dawnEnd))
      nowMin < morningEnd -> blend(DAWN, MORNING, frac(nowMin, dawnEnd, morningEnd))
      nowMin < midday -> blend(MORNING, MIDDAY, frac(nowMin, morningEnd, midday))
      nowMin < eveningStart -> blend(MIDDAY, EVENING, frac(nowMin, midday, eveningStart))
      nowMin < duskStart -> blend(EVENING, DUSK, frac(nowMin, eveningStart, duskStart))
      nowMin < duskEnd -> blend(DUSK, TWILIGHT, frac(nowMin, duskStart, duskEnd))
      nowMin < twilightEnd -> blend(TWILIGHT, NIGHT, frac(nowMin, duskEnd, twilightEnd))
      else -> NIGHT
    }
  }

  private fun frac(now: Int, start: Float, end: Float): Float =
      if (end <= start) 1f else ((now - start) / (end - start)).coerceIn(0f, 1f)

  private fun blend(a: Pair<Color, Color>, b: Pair<Color, Color>, t: Float): Pair<Color, Color> =
      lerp(a.first, b.first, t) to lerp(a.second, b.second, t)
}
