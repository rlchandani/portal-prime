/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Burn-in mitigation for the always-on screensaver overlay.
 *
 * A Portal that never sleeps shows the same clock in the same pixels for days, and on
 * panels prone to it those pixels age unevenly and ghost. The fix is the classic
 * "pixel shift": nudge the whole overlay by a few pixels so, averaged over time, the
 * bright content is spread across many pixels instead of branding a fixed silhouette.
 *
 * [shift] traces a slow Lissajous path from two incommensurate periods, so the motion
 * never settles into one repeating line (which would just burn a longer streak). It's
 * slow enough to be invisible and small enough not to disturb the layout — the host
 * oversizes the overlay by the same radius so the drift never bares a screen edge.
 *
 * Pure and deterministic: the offset is a function of the clock alone, so it's trivially
 * unit-tested and behaves identically on every device.
 */
object AntiBurnIn {

  // Two periods (ms) with no small common multiple, so the x/y loop precesses for a
  // long time before it ever repeats. Minutes-long, so a glance never catches it move.
  private const val PERIOD_X_MS = 91_000.0
  private const val PERIOD_Y_MS = 127_000.0

  /** A pixel offset to apply as the overlay's translationX / translationY. */
  data class Shift(val x: Float, val y: Float)

  /**
   * The overlay offset at [nowMs], each axis bounded to ±[maxPx]. x starts centred and
   * y at its extreme (the two axes are a quarter-cycle apart), so the path is a loop
   * rather than a single diagonal line.
   */
  fun shift(nowMs: Long, maxPx: Float): Shift {
    val x = sin(2.0 * PI * nowMs / PERIOD_X_MS) * maxPx
    val y = cos(2.0 * PI * nowMs / PERIOD_Y_MS) * maxPx
    return Shift(x.toFloat(), y.toFloat())
  }
}
