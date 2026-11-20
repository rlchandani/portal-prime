/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.roundToInt

/**
 * Evening treatment for photo modes: after sunset the photos warm and dim (a multiply tint,
 * applied as a colour filter on the photo draw, so it costs no extra pass), and back at dawn.
 */
object HearthNight {
  const val DUSK_MINUTES = 45
  const val DAWN_MINUTES = 40
  private const val WARM_R = 1f
  private const val WARM_G = .698f
  private const val WARM_B = .478f
  private const val WARMTH = .34f
  private const val DIM = .42f

  /**
   * 0 (day) .. 1 (full night) for minutes-of-day, easing in over [DUSK_MINUTES] after sunset
   * and out over [DAWN_MINUTES] before sunrise. Handles nights that cross midnight.
   */
  fun level(nowMin: Int, sunriseMin: Int, sunsetMin: Int): Float {
    val day = 24 * 60
    val since = Math.floorMod(nowMin - sunsetMin, day)
    val night = Math.floorMod(sunriseMin - sunsetMin, day)
    if (night == 0 || since >= night) return 0f
    val ramp = minOf(1f, since / DUSK_MINUTES.toFloat(), (night - since) / DAWN_MINUTES.toFloat())
    return ramp * ramp * (3f - 2f * ramp)
  }

  /** Opaque multiply colour for a night [level]; white (no change) at 0. */
  fun multiplyColor(level: Float): Int {
    val l = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    fun ch(warm: Float) = ((1f - DIM * l) * (1f - WARMTH * l * (1f - warm)) * 255f).roundToInt()
    return (255 shl 24) or (ch(WARM_R) shl 16) or (ch(WARM_G) shl 8) or ch(WARM_B)
  }

  /**
   * The night clock's dusk handoff tint at [progress] 0..1: a #FF9A5A multiply at up to 45%
   * plus a 25% dim, as one opaque multiply colour (mock NightClock warm + veil).
   */
  fun duskMultiply(progress: Float): Int {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    fun ch(warm: Float) = ((1f - .25f * p) * (1f - .45f * p * (1f - warm)) * 255f).roundToInt()
    return (255 shl 24) or (ch(1f) shl 16) or (ch(.604f) shl 8) or ch(.353f)
  }

  /** The overlay switches to its warm night ink once the dusk ramp is half way. */
  fun nightInk(level: Float): Boolean = level >= .5f
}

/** Slow continuous burn-in orbit, evaluated only on frames the screensaver already draws. */
object HearthOrbit {
  /** Overlay orbit: +/-4 dp over 10 and 12.7 minute periods. */
  const val FACE_RADIUS_DP = 4f
  const val FACE_PERIOD_X_MS = 600_000L
  const val FACE_PERIOD_Y_MS = 760_000L
  const val NIGHT_PERIOD_X_MS = 2_400_000L
  const val NIGHT_PERIOD_Y_MS = 3_180_000L

  fun x(timeMs: Long, radius: Float, periodMs: Long): Float =
      (radius * Math.sin(2 * Math.PI * Math.floorMod(timeMs, periodMs) / periodMs)).toFloat()

  fun y(timeMs: Long, radius: Float, periodMs: Long): Float =
      (radius * Math.cos(2 * Math.PI * Math.floorMod(timeMs, periodMs) / periodMs)).toFloat()
}

/** Viewport-driven overlay layout; adaptations come from size and orientation, never a model. */
object HearthLayout {
  /** Below this width (or in portrait) the two bottom corners stack into one column. */
  const val STACK_BELOW_DP = 1000
  const val CALENDAR_WIDTH_DP = 430
  const val EDGE_DP = 48

  fun stackBottom(widthDp: Float, heightDp: Float): Boolean =
      widthDp < STACK_BELOW_DP || heightDp > widthDp

  /** Calendar card width: the mock's 430 dp at Medium, never wider than the viewport allows. */
  fun calendarWidthDp(scale: Float, widthDp: Float): Float =
      minOf(CALENDAR_WIDTH_DP * scale, widthDp - 2 * EDGE_DP).coerceAtLeast(0f)
}
