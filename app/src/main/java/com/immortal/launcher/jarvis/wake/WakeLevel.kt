/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The input level over a session's first second, logged once, so a log shows whether Android is really giving this
 * screen the room's audio (a silenced client reads pure digital zero, see the wake-frame spike) — without keeping,
 * logging or storing any audio. Folds 16-bit little-endian PCM into a sum of squares and a peak; immutable, pure.
 */
internal data class WakeLevel(val samples: Long = 0, val sumSquares: Double = 0.0, val peak: Int = 0) {
  /** Enough audio for a verdict. */
  val complete: Boolean get() = samples >= SAMPLES

  /** RMS in dBFS, or null for digital silence (a silenced client). */
  val dbfs: Double? get() {
    if (samples == 0L || sumSquares == 0.0) return null
    return 20 * log10(sqrt(sumSquares / samples) / FULL_SCALE)
  }

  /** The level with [bytes] (the first [length] of them) folded in. */
  fun add(bytes: ByteArray, length: Int): WakeLevel {
    var sum = sumSquares
    var max = peak
    var count = 0
    var i = 0
    while (i + 1 < length) {
      val v = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
      sum += v.toDouble() * v
      val abs = if (v < 0) -v else v
      if (abs > max) max = abs
      count++
      i += 2
    }
    return WakeLevel(samples + count, sum, max)
  }

  /** One log line: a number, never audio. */
  fun describe(): String = dbfs?.let { "mic live: rms ${"%.1f".format(it)} dBFS, peak $peak" }
      ?: "mic silent: Android is giving this screen no audio"

  companion object {
    /** One second at the frontend's 16 kHz. */
    const val SAMPLES = 16_000L
    private const val FULL_SCALE = 32_768.0
  }
}
