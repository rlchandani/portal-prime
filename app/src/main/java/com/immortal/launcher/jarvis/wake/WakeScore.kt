/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

/**
 * microWakeWord's decision rule, as an immutable value: the mean of the last [WINDOW] model probabilities
 * (one per 30 ms) must exceed [CUTOFF]. The cutoff and window are the model's published `hey_jarvis` settings
 * (0.97 over 5); offline they gave zero wakes in 41 min of music and 16 min of household-style speech. Pure.
 *
 * [recent] holds raw uint8 outputs (probability × 256), newest last.
 */
internal data class WakeScore(val recent: List<Int> = emptyList()) {
  /** The score after one more model output [q8] (0..255). */
  fun next(q8: Int): WakeScore = WakeScore((recent + q8.coerceIn(0, MAX_Q8)).takeLast(WINDOW))

  /** The smoothed probability, 0 until the window is full. */
  val mean: Float get() = if (recent.size < WINDOW) 0f else recent.sum().toFloat() / (WINDOW * Q8_ONE)

  val triggered: Boolean get() = mean > CUTOFF

  companion object {
    const val WINDOW = 5
    const val CUTOFF = 0.97f
    private const val Q8_ONE = 256f
    private const val MAX_Q8 = 255
  }
}
