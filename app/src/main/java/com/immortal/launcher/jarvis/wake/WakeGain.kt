/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

/**
 * The fixed digital pre-gain in front of the wake frontend. The Portal's far-field mic delivers speech from across the
 * room at a peak of only ~20 (of 32767): measured on Family with a voice at the Mac speaker. At that level the model's
 * recall collapses (1 in 24 synthetic voices offline), and ×32 restores it (22 in 24) while staying robust to close,
 * loud speech (clipping is tolerated: 19 in 24 at a peak of 8000) and adding no false wakes (0 in 41 min of music
 * and speech at room level). Jarvis gains its conversation stream for the same reason. Pure; works in place.
 */
internal object WakeGain {
  const val GAIN = 32

  /** Multiply the first [length] bytes of little-endian int16 [pcm] by [GAIN], saturating at the int16 limits. */
  fun apply(pcm: ByteArray, length: Int) {
    var i = 0
    while (i + 1 < length) {
      val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and BYTE)
      val gained = (sample * GAIN).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      pcm[i] = gained.toByte()
      pcm[i + 1] = (gained shr 8).toByte()
      i += 2
    }
  }

  private const val BYTE = 0xFF
}
