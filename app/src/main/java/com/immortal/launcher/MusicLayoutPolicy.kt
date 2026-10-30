/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Layout budgets in dp; the image decode/cache budget remains independent. */
internal object MusicLayoutPolicy {
  fun reservedHeight(small: Boolean, fontScale: Float): Float {
    val scale = fontScale.takeIf { it.isFinite() && it > 0 }?.coerceAtLeast(1f) ?: 1f
    // One-line title/artist/album, 48dp seek target, elapsed labels, transport and gaps.
    return (if (small) 280f else 320f) + (scale - 1f) * (if (small) 120f else 200f)
  }

  fun coverEdge(width: Float, height: Float, small: Boolean, fontScale: Float,
      extraHeight: Float = 0f): Float {
    val cap = if (small) 200f else 480f
    val availableWidth = width.takeIf { it.isFinite() && it > 0 } ?: cap
    val extra = extraHeight.takeIf { it.isFinite() && it > 0f } ?: 0f
    val availableHeight = height.takeIf { it.isFinite() && it > 0 }
        ?.let { (it - reservedHeight(small, fontScale) - extra)
            .coerceAtLeast(24f) } ?: cap
    return minOf(cap, availableWidth, availableHeight)
  }
}
