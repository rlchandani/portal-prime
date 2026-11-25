/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import kotlin.math.ceil

/**
 * Decode and cache edges derived from the panel's long edge rather than a fixed 2400 px.
 *
 * The long edge is orientation independent, so a Portal Mini (800x1280) decodes at the same
 * size in portrait and landscape, and a rotation never needs a re-decode. The 15% headroom
 * covers the premium settle zoom (at most 6.5%) plus a modest aspect-mismatch crop.
 */
object PhotoEdges {
  /** Never decode above today's fixed ceiling: memory must not grow on the Portal+. */
  const val MAX_DECODE_EDGE = SlideshowDecodeBudget.MAX_EDGE
  const val MIN_DECODE_EDGE = 640
  const val MAX_PARTNER_EDGE = 1400
  private const val DECODE_HEADROOM = 1.15
  private const val PARTNER_FRACTION = 0.65
  // A smaller panel still keeps at least this fraction of the reference budget, so even a tiny
  // display holds a usable photo working set rather than thrashing its cache.
  private const val MIN_BUDGET_FRACTION = 0.25

  /** Long edge of a single full-screen photo for a panel whose long edge is [longEdgePx]. */
  fun decodeEdge(longEdgePx: Int): Int =
      if (longEdgePx <= 0) MAX_DECODE_EDGE
      else ceil(longEdgePx * DECODE_HEADROOM).toInt().coerceIn(MIN_DECODE_EDGE, MAX_DECODE_EDGE)

  /** A half-screen pair tile needs about two thirds of the long edge (1400 on a 2160 panel). */
  fun partnerEdge(longEdgePx: Int): Int =
      if (longEdgePx <= 0) MAX_PARTNER_EDGE
      else ceil(longEdgePx * PARTNER_FRACTION).toInt().coerceIn(MIN_DECODE_EDGE, MAX_PARTNER_EDGE)

  /**
   * Disk budget for drawn-size photo copies, scaled off the panel's long edge rather than a
   * fixed constant. A cached copy's footprint grows with the square of its long edge
   * ([decodeEdge]), so a Portal Mini stores markedly smaller copies than a Portal+. Scaling the
   * budget by that same area ratio keeps a comparable photo working set on every panel: the
   * Portal+ reference ([referenceBudgetBytes], tuned at [MAX_DECODE_EDGE]) is preserved exactly,
   * while a smaller panel gets a proportionally smaller budget instead of over-retaining copies.
   * Floored at [MIN_BUDGET_FRACTION] of the reference and never raised above it.
   */
  fun cacheBudgetBytes(longEdgePx: Int, referenceBudgetBytes: Long): Long {
    if (referenceBudgetBytes <= 0L) return 0L
    val edge = decodeEdge(longEdgePx)
    val areaFraction = (edge.toDouble() / MAX_DECODE_EDGE) * (edge.toDouble() / MAX_DECODE_EDGE)
    val floor = (referenceBudgetBytes * MIN_BUDGET_FRACTION).toLong()
    return (referenceBudgetBytes * areaFraction).toLong()
        .coerceIn(minOf(floor, referenceBudgetBytes), referenceBudgetBytes)
  }

  /** Physical long edge of the default display, independent of the current rotation. */
  fun displayLongEdge(context: Context): Int {
    val metrics = context.resources.displayMetrics
    return maxOf(metrics.widthPixels, metrics.heightPixels)
  }
}
