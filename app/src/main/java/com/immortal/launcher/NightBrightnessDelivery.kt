/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread boundary between continuous fade progress and Android's window commands. */
internal class NightBrightnessDelivery(
    private val apiLevel: Int,
    private val readWindow: () -> Float,
    private val writeWindow: (Float) -> Unit,
) {
  private var delivered = readWindow()
  private var logical = delivered

  fun read(): Float {
    reconcileWindow()
    return logical
  }

  fun write(level: Float) = deliver(level, exact = false)

  /** Flush exact endpoints, including Android's -1 policy sentinel. */
  fun settle(level: Float) = deliver(level, exact = true)

  private fun deliver(level: Float, exact: Boolean) {
    val actual = reconcileWindow()
    if (actual.toRawBits() != level.toRawBits() &&
        (exact || !sameHardwareLevel(actual, level))) {
      writeWindow(level)
      delivered = level
    }
    logical = level
  }

  private fun reconcileWindow(): Float {
    val actual = readWindow()
    if (actual.toRawBits() != delivered.toRawBits()) {
      // Another owner may set even a same-bucket override: its value takes precedence.
      delivered = actual
      logical = actual
    }
    return actual
  }

  private fun sameHardwareLevel(a: Float, b: Float): Boolean {
    // Android 10 RootWindowContainer truncates window brightness to int(value * 255).
    // Android 11+ delivers floats; never quantize its commands or policy sentinels.
    return apiLevel <= 29 && a.isFinite() && b.isFinite() &&
        a in 0f..1f && b in 0f..1f && (a * 255f).toInt() == (b * 255f).toInt()
  }
}
