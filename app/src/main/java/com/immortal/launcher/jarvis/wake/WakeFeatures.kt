/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

/**
 * Frontend features → the model's int8 input, exactly as microWakeWord's reference does it: each uint16 value is
 * scaled by 0.0390625 (the frontend's float scale) and quantised with the input tensor's scale and zero point,
 * rounding half to even in double precision (numpy's `round`). [stride] frames (30 ms) make one model input. Pure;
 * the caller owns the `into` buffer.
 */
internal class WakeFeatures(scale: Float, private val zeroPoint: Int, val stride: Int) {
  private val inputScale = scale.toDouble()

  /** Write frame [index] (0 until [stride]) of [features] into [into] as int8 bytes. */
  fun quantize(features: IntArray, index: Int, into: ByteArray) {
    val base = index * features.size
    for (i in features.indices) {
      val q = Math.rint(features[i] * FEATURE_SCALE / inputScale + zeroPoint).toInt()
      into[base + i] = q.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
    }
  }

  companion object {
    /** The frontend's uint16 → float feature scale (`FLOAT32_SCALE` in microWakeWord's preprocessor). */
    const val FEATURE_SCALE = 0.0390625
  }
}
