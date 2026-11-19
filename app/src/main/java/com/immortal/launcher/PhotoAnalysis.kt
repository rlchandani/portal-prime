/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-photo analysis computed once on the preparation worker from a small ARGB thumbnail:
 * a luminance grid (drives adaptive readability under each overlay), the photo's accent colour
 * (ties overlays to the photo) and a saliency focus (subject-aware crops when no face is known).
 * Pure: takes pixels, never Android bitmaps, so every rule is JVM-tested.
 */
class PhotoAnalysis private constructor(
    /** Row-major [GRID]x[GRID] relative luminance, 0..1. */
    private val luma: FloatArray,
    /** Row-major [GRID]x[GRID] local contrast (luma standard deviation), 0..1. */
    private val texture: FloatArray,
    /** The dominant vivid colour, opaque ARGB; null when the photo is near-monochrome. */
    val dominant: Int?,
    val focus: PremiumFraming.Focus?,
) {
  /**
   * Mean luminance and texture of a normalized image rectangle (0..1 on both axes).
   * Cells partially inside the rectangle count by their overlap.
   */
  fun region(left: Float, top: Float, right: Float, bottom: Float): Zone {
    val l = left.coerceIn(0f, 1f) * GRID
    val t = top.coerceIn(0f, 1f) * GRID
    val r = right.coerceIn(0f, 1f) * GRID
    val b = bottom.coerceIn(0f, 1f) * GRID
    if (r <= l || b <= t) return Zone(.4f, 0f)
    var weight = 0f
    var sum = 0f
    var tex = 0f
    for (y in t.toInt() until min(GRID, kotlin.math.ceil(b).toInt())) {
      val wy = min(b, y + 1f) - max(t, y.toFloat())
      if (wy <= 0f) continue
      for (x in l.toInt() until min(GRID, kotlin.math.ceil(r).toInt())) {
        val wx = min(r, x + 1f) - max(l, x.toFloat())
        if (wx <= 0f) continue
        val w = wx * wy
        weight += w
        sum += luma[y * GRID + x] * w
        tex += texture[y * GRID + x] * w
      }
    }
    return if (weight <= 0f) Zone(.4f, 0f) else Zone(sum / weight, tex / weight)
  }

  /** Average brightness and busyness of the photo under one overlay zone. */
  data class Zone(val luminance: Float, val texture: Float)

  companion object {
    const val GRID = 16
    /** Thumbnail long edge the worker should supply; a 4:3 photo becomes 96x72 pixels. */
    const val THUMB_EDGE = 96

    /** A neutral analysis for photos that could not be sampled (bundled ember accent). */
    val NEUTRAL = PhotoAnalysis(FloatArray(GRID * GRID) { .4f }, FloatArray(GRID * GRID), null, null)

    fun of(pixels: IntArray, width: Int, height: Int): PhotoAnalysis {
      if (width <= 0 || height <= 0 || pixels.size < width * height) return NEUTRAL
      val luma = FloatArray(width * height) { lightness(pixels[it]) }
      val grid = FloatArray(GRID * GRID)
      val sq = FloatArray(GRID * GRID)
      val count = IntArray(GRID * GRID)
      for (y in 0 until height) {
        val gy = y * GRID / height
        for (x in 0 until width) {
          val cell = gy * GRID + x * GRID / width
          val v = luma[y * width + x]
          grid[cell] += v
          sq[cell] += v * v
          count[cell]++
        }
      }
      val texture = FloatArray(GRID * GRID)
      for (i in grid.indices) {
        val n = count[i]
        if (n == 0) { grid[i] = .4f; continue }
        val mean = grid[i] / n
        grid[i] = mean
        texture[i] = sqrt(max(0f, sq[i] / n - mean * mean))
      }
      return PhotoAnalysis(grid, texture, dominant(pixels, width, height),
          saliency(luma, width, height))
    }

    /** Perceptual lightness (CIE L*, scaled to 0..1) of an sRGB colour. */
    fun lightness(argb: Int): Float {
      fun lin(c: Int): Float {
        val s = c / 255f
        return if (s <= .04045f) s / 12.92f else Math.pow(((s + .055f) / 1.055f).toDouble(), 2.4).toFloat()
      }
      // L* reads better than linear light for "is this zone bright" (mid-grey = 0.5).
      val y = .2126f * lin(argb shr 16 and 255) + .7152f * lin(argb shr 8 and 255) + .0722f * lin(argb and 255)
      return if (y <= .008856f) y * 9.033f else (1.16f * Math.cbrt(y.toDouble()).toFloat() - .16f)
    }

    /**
     * The most populous vivid hue, weighted by saturation: a light-weight Palette "vibrant".
     * Returns the mean colour of the winning hue bucket; null for monochrome photos.
     */
    fun dominant(pixels: IntArray, width: Int, height: Int): Int? {
      val buckets = HUE_BUCKETS
      val weight = FloatArray(buckets)
      val r = FloatArray(buckets)
      val g = FloatArray(buckets)
      val b = FloatArray(buckets)
      val hsl = FloatArray(3)
      for (i in 0 until width * height) {
        val c = pixels[i]
        rgbToHsl(c shr 16 and 255, c shr 8 and 255, c and 255, hsl)
        val sat = hsl[1]
        val light = hsl[2]
        if (sat < .18f || light < .12f || light > .92f) continue
        val w = sat * (1f - abs(light - .5f) * 1.4f).coerceAtLeast(.05f)
        val bucket = (hsl[0] / 360f * buckets).toInt().coerceIn(0, buckets - 1)
        weight[bucket] += w
        r[bucket] += (c shr 16 and 255) * w
        g[bucket] += (c shr 8 and 255) * w
        b[bucket] += (c and 255) * w
      }
      var best = -1
      for (i in 0 until buckets) if (best < 0 || weight[i] > weight[best]) best = i
      // A photo needs a meaningful share of vivid pixels before its hue drives the accent.
      if (best < 0 || weight[best] < width * height * .015f) return null
      val w = weight[best]
      return (255 shl 24) or ((r[best] / w).roundToInt() shl 16) or
          ((g[best] / w).roundToInt() shl 8) or (b[best] / w).roundToInt()
    }

    /**
     * Saliency centre: gradient energy weighted toward the middle of the frame, so busy detail
     * (a subject) pulls the crop while flat sky or wall does not. Null for featureless images.
     */
    fun saliency(luma: FloatArray, width: Int, height: Int): PremiumFraming.Focus? {
      if (width < 3 || height < 3) return null
      var sum = 0.0
      var sx = 0.0
      var sy = 0.0
      for (y in 1 until height - 1) for (x in 1 until width - 1) {
        val gx = luma[y * width + x + 1] - luma[y * width + x - 1]
        val gy = luma[(y + 1) * width + x] - luma[(y - 1) * width + x]
        val nx = (x + .5f) / width - .5f
        val ny = (y + .5f) / height - .5f
        val prior = 1f - (nx * nx + ny * ny) * 1.6f
        val e = (gx * gx + gy * gy) * prior.coerceAtLeast(.1f)
        sum += e
        sx += e * (x + .5)
        sy += e * (y + .5)
      }
      if (sum < width * height * 1e-4) return null
      // Lean halfway back to the centre: saliency is a hint, not a face.
      val fx = (.5 + ((sx / sum) / width - .5) * .5).toFloat()
      val fy = (.5 + ((sy / sum) / height - .5) * .5).toFloat()
      return PremiumFraming.Focus(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }

    fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
      val rf = r / 255f
      val gf = g / 255f
      val bf = b / 255f
      val mx = max(rf, max(gf, bf))
      val mn = min(rf, min(gf, bf))
      val l = (mx + mn) / 2f
      val d = mx - mn
      if (d == 0f) { out[0] = 0f; out[1] = 0f; out[2] = l; return }
      val s = d / (1f - abs(2f * l - 1f))
      var h = when (mx) {
        rf -> ((gf - bf) / d) % 6f
        gf -> (bf - rf) / d + 2f
        else -> (rf - gf) / d + 4f
      } * 60f
      if (h < 0f) h += 360f
      out[0] = h; out[1] = s.coerceIn(0f, 1f); out[2] = l
    }

    private const val HUE_BUCKETS = 18
  }
}
