/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.floor

/**
 * A camera frame's YUV_420_888 planes copied into our own arrays (the Image is closed at once).
 * Lives only in memory for one glance and is never written anywhere.
 */
internal class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val timestampMs: Long,
) {
  init {
    require(width > 0 && height > 0 && yRowStride >= width && uvPixelStride >= 1) { "bad frame geometry" }
  }

  fun luma(x: Int, y: Int): Int = this.y[y * yRowStride + x].toInt() and 0xFF

  /** Mean luma on a coarse grid: the low-light check before we try to recognise anyone. */
  fun meanLuma(step: Int = 8): Float {
    var sum = 0L
    var n = 0
    var yy = 0
    while (yy < height) {
      var xx = 0
      while (xx < width) {
        sum += luma(xx, yy); n++; xx += step
      }
      yy += step
    }
    return if (n == 0) 0f else sum.toFloat() / n
  }
}

/** Pixel order and range for a model input. */
internal enum class TensorFormat {
  /** BlazeFace: RGB in −1…1. */
  RGB_SIGNED,
  /** SFace: BGR in 0…255 (the model normalises internally). */
  BGR_255,
}

internal object FaceFrameSampler {

  /**
   * Fills [out] (`size×size×3`, row-major HWC) by sampling [frame] bilinearly through
   * [inputToFrame] (model-input pixel → frame pixel). Outside the frame is black.
   */
  fun sample(frame: YuvFrame, inputToFrame: Affine, size: Int, format: TensorFormat, out: FloatArray) {
    require(out.size >= size * size * 3) { "output too small" }
    var o = 0
    for (row in 0 until size) {
      val vy = row + 0.5f
      for (col in 0 until size) {
        val vx = col + 0.5f
        val sx = inputToFrame.a * vx + inputToFrame.b * vy + inputToFrame.c - 0.5f
        val sy = inputToFrame.d * vx + inputToFrame.e * vy + inputToFrame.f - 0.5f
        val rgb = rgbAt(frame, sx, sy)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        when (format) {
          TensorFormat.RGB_SIGNED -> {
            out[o] = r / 127.5f - 1f; out[o + 1] = g / 127.5f - 1f; out[o + 2] = b / 127.5f - 1f
          }
          TensorFormat.BGR_255 -> {
            out[o] = b.toFloat(); out[o + 1] = g.toFloat(); out[o + 2] = r.toFloat()
          }
        }
        o += 3
      }
    }
  }

  /** Bilinear luma, nearest chroma (chroma is half resolution anyway). Returns 0xRRGGBB. */
  fun rgbAt(frame: YuvFrame, x: Float, y: Float): Int {
    if (x < 0f || y < 0f || x > frame.width - 1 || y > frame.height - 1) return 0
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val x1 = minOf(x0 + 1, frame.width - 1)
    val y1 = minOf(y0 + 1, frame.height - 1)
    val fx = x - x0
    val fy = y - y0
    val top = frame.luma(x0, y0) * (1 - fx) + frame.luma(x1, y0) * fx
    val bottom = frame.luma(x0, y1) * (1 - fx) + frame.luma(x1, y1) * fx
    val lum = top * (1 - fy) + bottom * fy
    val ci = (y0 / 2) * frame.uvRowStride + (x0 / 2) * frame.uvPixelStride
    val u = (if (ci < frame.u.size) frame.u[ci].toInt() and 0xFF else 128) - 128
    val v = (if (ci < frame.v.size) frame.v[ci].toInt() and 0xFF else 128) - 128
    // BT.601 full-range (camera YUV_420_888 is JFIF / full range).
    val r = clamp(lum + 1.402f * v)
    val g = clamp(lum - 0.344136f * u - 0.714136f * v)
    val b = clamp(lum + 1.772f * u)
    return (r shl 16) or (g shl 8) or b
  }

  private fun clamp(v: Float): Int = when {
    v <= 0f -> 0
    v >= 255f -> 255
    else -> (v + 0.5f).toInt()
  }

  /**
   * Variance of a 3×3 Laplacian over the aligned face's luma (from a BGR_255 tensor): low means
   * blurred. Used by the enrolment quality gate.
   */
  fun sharpness(bgr: FloatArray, size: Int): Float {
    fun l(x: Int, y: Int): Float {
      val i = (y * size + x) * 3
      return 0.114f * bgr[i] + 0.587f * bgr[i + 1] + 0.299f * bgr[i + 2]
    }
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in 1 until size - 1) for (x in 1 until size - 1) {
      val lap = 4 * l(x, y) - l(x - 1, y) - l(x + 1, y) - l(x, y - 1) - l(x, y + 1)
      sum += lap; sumSq += lap * lap; n++
    }
    if (n == 0) return 0f
    val mean = sum / n
    return (sumSq / n - mean * mean).toFloat()
  }
}
