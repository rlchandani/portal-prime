/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pre-blurs the small backdrop copy once per photo on the preparation worker. Android 10 has no
 * RenderEffect, so the screensaver never blurs live: it draws this bitmap scaled up instead.
 * Three box passes per axis approximate a Gaussian; edges clamp so borders do not darken.
 */
object AmbientBlur {
  /** Blur [pixels] (opaque ARGB, row-major) in place. [radius] is in thumbnail pixels. */
  fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (width <= 0 || height <= 0 || radius <= 0 || pixels.size < width * height) return
    val scratch = IntArray(maxOf(width, height))
    val line = IntArray(maxOf(width, height))
    repeat(PASSES) {
      for (y in 0 until height) {
        for (x in 0 until width) line[x] = pixels[y * width + x]
        boxLine(line, scratch, width, radius)
        for (x in 0 until width) pixels[y * width + x] = scratch[x]
      }
      for (x in 0 until width) {
        for (y in 0 until height) line[y] = pixels[y * width + x]
        boxLine(line, scratch, height, radius)
        for (y in 0 until height) pixels[y * width + x] = scratch[y]
      }
    }
  }

  /** One clamped running-sum box filter over [n] samples of [src] into [dst]. */
  private fun boxLine(src: IntArray, dst: IntArray, n: Int, radius: Int) {
    val window = radius * 2 + 1
    var r = 0
    var g = 0
    var b = 0
    for (i in -radius..radius) {
      val c = src[i.coerceIn(0, n - 1)]
      r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
    }
    val half = window / 2
    for (i in 0 until n) {
      // Rounded division: six passes of truncation would visibly darken the backdrop.
      dst[i] = (255 shl 24) or (((r + half) / window) shl 16) or (((g + half) / window) shl 8) or
          ((b + half) / window)
      val out = src[(i - radius).coerceIn(0, n - 1)]
      val inn = src[(i + radius + 1).coerceIn(0, n - 1)]
      r += (inn shr 16 and 255) - (out shr 16 and 255)
      g += (inn shr 8 and 255) - (out shr 8 and 255)
      b += (inn and 255) - (out and 255)
    }
  }

  private const val PASSES = 3
}
