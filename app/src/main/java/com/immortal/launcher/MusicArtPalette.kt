/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Four colours (ARGB ints) that tint the Music background from a song's artwork, like the mock's
 * `--c1…--c4`: [deep] the darkest mass, [accent] the most vivid, [light] the brightest, [mid] the rest.
 */
internal data class MusicArtPalette(val deep: Int, val accent: Int, val light: Int, val mid: Int) {
  val colors: List<Int> get() = listOf(deep, accent, light, mid)

  companion object {
    /** During an ad: quiet greys (nothing hints at the song). */
    val NEUTRAL = MusicArtPalette(0xFF5D5A60.toInt(), 0xFF8A878D.toInt(), 0xFFB3B0B5.toInt(), 0xFF6E6B71.toInt())
    /** Nothing playing: the warm Hearthpane wash. */
    val IDLE = MusicArtPalette(0xFF4A3A30.toInt(), 0xFF8A6A52.toInt(), 0xFFC9B39C.toInt(), 0xFF5E4A3C.toInt())
  }
}

/**
 * Pure palette extraction: a tiny k-means over a small thumbnail's pixels with deterministic
 * farthest-first seeds. Runs off the main thread on at most a few thousand pixels.
 */
internal object MusicArtPaletteMath {
  /** Thumbnails are scaled to this edge before extraction. */
  const val SAMPLE_EDGE = 24
  /** The middle of a 4:3 YouTube thumbnail that holds a music cover (see [MusicV3CoverArt.ZOOM]). */
  const val COVER_SHARE = 0.75f
  private const val CLUSTERS = 4
  private const val ROUNDS = 6
  private const val MIN_ALPHA = 128
  /** A cluster smaller than this share of the pixels can't be the accent. */
  private const val MIN_ACCENT_SHARE = 0.04f

  /** Centre square (x, y, side) of a [w]×[h] image to sample. */
  fun coverCrop(w: Int, h: Int): Triple<Int, Int, Int> {
    val side = max(1, (min(w, h) * COVER_SHARE).roundToInt())
    return Triple((w - side) / 2, (h - side) / 2, side)
  }

  fun extract(pixels: IntArray, fallback: MusicArtPalette = MusicArtPalette.IDLE): MusicArtPalette {
    val opaque = pixels.filter { (it ushr 24) >= MIN_ALPHA }
    if (opaque.isEmpty()) return fallback
    var centers = seeds(opaque)
    var counts = IntArray(CLUSTERS)
    repeat(ROUNDS) {
      val sums = Array(CLUSTERS) { DoubleArray(3) }
      counts = IntArray(CLUSTERS)
      for (p in opaque) {
        val c = rgb(p)
        val k = nearest(centers, c)
        counts[k]++
        for (i in 0..2) sums[k][i] += c[i]
      }
      centers = centers.mapIndexed { k, old ->
        if (counts[k] == 0) old else DoubleArray(3) { i -> sums[k][i] / counts[k] }
      }
    }
    return assign(centers, counts, opaque.size)
  }

  private fun assign(centers: List<DoubleArray>, counts: IntArray, total: Int): MusicArtPalette {
    val order = centers.indices.sortedBy { lumaOf(centers[it]) }
    val deep = order.first()
    val light = order.last()
    val rest = order.drop(1).dropLast(1)
    val accent = rest.maxByOrNull { k ->
      val share = counts[k].toFloat() / total
      if (share < MIN_ACCENT_SHARE) -1.0 else saturation(centers[k]) * sqrt(share.toDouble())
    } ?: light
    val mid = rest.firstOrNull { it != accent } ?: deep
    return MusicArtPalette(argb(centers[deep]), argb(centers[accent]), argb(centers[light]), argb(centers[mid]))
  }

  /** Farthest-first seeds from the darkest pixel: distinct colour masses each get a cluster. */
  private fun seeds(pixels: List<Int>): List<DoubleArray> {
    val colors = pixels.map(::rgb)
    val seeds = mutableListOf(colors.minByOrNull(::lumaOf)!!)
    while (seeds.size < CLUSTERS) seeds += colors.maxByOrNull { c -> seeds.minOf { distance(it, c) } }!!
    return seeds.toList()
  }

  private fun nearest(centers: List<DoubleArray>, c: DoubleArray): Int {
    var best = 0
    var bestD = Double.MAX_VALUE
    centers.forEachIndexed { k, m ->
      val d = distance(m, c)
      if (d < bestD) { bestD = d; best = k }
    }
    return best
  }

  private fun distance(a: DoubleArray, b: DoubleArray): Double =
      (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])

  private fun rgb(p: Int) = doubleArrayOf(((p shr 16) and 0xFF).toDouble(), ((p shr 8) and 0xFF).toDouble(),
      (p and 0xFF).toDouble())

  private fun argb(c: DoubleArray): Int {
    fun ch(v: Double) = v.roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
  }

  private fun lumaOf(c: DoubleArray): Double = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

  /** HSV-style saturation, 0…1. */
  fun saturation(c: DoubleArray): Double {
    val hi = maxOf(c[0], c[1], c[2])
    val lo = minOf(c[0], c[1], c[2])
    return if (hi <= 0.0) 0.0 else (hi - lo) / hi
  }
}
