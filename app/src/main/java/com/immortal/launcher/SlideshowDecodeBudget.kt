/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Hard admission limits apply to every source, including caller-requested derivatives. */
object SlideshowDecodeBudget {
  const val MAX_EDGE = 2400
  const val MAX_PIXELS = 3L * 1024 * 1024
  const val MAX_PIXEL_BYTES = MAX_PIXELS * 4
  /** A mirrored/rotated decode may temporarily own the sampled source and upright output. */
  const val MAX_TRANSFORM_PIXEL_BYTES = MAX_PIXEL_BYTES * 2
  const val MAX_ENCODED_BYTES = 32 * 1024 * 1024

  data class Size(val width: Int, val height: Int)

  /** Width/height conservatively round up; codecs must not allocate an original-size bitmap. */
  data class Plan(val sampleSize: Int, val width: Int, val height: Int)

  /** Signed orthogonal matrix; offsets express the same transform in normalized coordinates. */
  data class ExifTransform(val xx: Int, val xy: Int, val yx: Int, val yy: Int) {
    val offsetX: Int get() = if (xx < 0 || xy < 0) 1 else 0
    val offsetY: Int get() = if (yx < 0 || yy < 0) 1 else 0
  }

  /**
   * Pick the smallest power-of-two sample whose conservative output fits BOTH limits.
   * Sampling may sacrifice some resolution, but never relies on a subsequent resize.
   */
  fun plan(width: Int, height: Int, maxEdge: Int = MAX_EDGE, maxPixels: Long = MAX_PIXELS): Plan? {
    if (width <= 0 || height <= 0 || maxEdge <= 0 || maxPixels <= 0) return null
    var sample = 1
    while (true) {
      val w = ((width.toLong() + sample - 1) / sample).toInt()
      val h = ((height.toLong() + sample - 1) / sample).toInt()
      if (fits(w, h, maxEdge, maxPixels)) return Plan(sample, w, h)
      if (sample >= 1 shl 30) return null
      sample *= 2
    }
  }

  fun fits(width: Int, height: Int, maxEdge: Int = MAX_EDGE, maxPixels: Long = MAX_PIXELS): Boolean =
      width > 0 && height > 0 && maxEdge > 0 && maxPixels > 0 &&
          width <= minOf(maxEdge, MAX_EDGE) && height <= minOf(maxEdge, MAX_EDGE) &&
          width.toLong() * height <= minOf(maxPixels, MAX_PIXELS)

  /** EXIF 5/7 are diagonal reflections, not ordinary quarter-turns. */
  fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
    2 -> ExifTransform(-1, 0, 0, 1)
    3 -> ExifTransform(-1, 0, 0, -1)
    4 -> ExifTransform(1, 0, 0, -1)
    5 -> ExifTransform(0, 1, 1, 0)
    6 -> ExifTransform(0, -1, 1, 0)
    7 -> ExifTransform(0, -1, -1, 0)
    8 -> ExifTransform(0, 1, -1, 0)
    else -> ExifTransform(1, 0, 0, 1)
  }

  fun uprightSize(width: Int, height: Int, orientation: Int): Size? {
    if (width <= 0 || height <= 0) return null
    return if (orientation in 5..8) Size(height, width) else Size(width, height)
  }
}
