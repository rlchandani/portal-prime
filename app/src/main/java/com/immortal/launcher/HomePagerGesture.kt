/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry for the Home widget pager's finger tracking. The view moves 1:1 with the drag while
 * a finger is down, and the settle animation that follows release starts from wherever the finger
 * left it. Kept side-effect free so it can be unit tested without Compose.
 */
internal object HomePagerGesture {
  /** A lift faster than this (dp/s) commits to the next page in its direction regardless of distance. */
  const val FLING_VELOCITY_DP = 360f

  /** Settle duration bounds; the remaining fraction of a page scales between them. */
  const val MIN_SETTLE_MS = 90
  const val MAX_SETTLE_MS = 220

  /**
   * Live scroll position (in pages) while a finger drags. A leftward drag (negative [dragPx]) moves
   * toward higher page indices, matching [HomePresentation.pageOffset]. Clamped to the real pages.
   */
  fun dragPosition(startScroll: Float, dragPx: Float, viewportPx: Float, lastIndex: Int): Float {
    if (viewportPx <= 0f || lastIndex < 0) return startScroll
    return (startScroll - dragPx / viewportPx).coerceIn(0f, lastIndex.toFloat())
  }

  /**
   * Page to settle on when the finger lifts: a fling past [flingVelocityPx] wins by its direction
   * (one page from [startPage]); otherwise the nearest page to the released [position].
   */
  fun targetPage(
      position: Float,
      startPage: Int,
      velocityPxPerSec: Float,
      flingVelocityPx: Float,
      lastIndex: Int,
  ): Int {
    if (lastIndex < 0) return 0
    val target =
        if (abs(velocityPxPerSec) >= flingVelocityPx) startPage + if (velocityPxPerSec < 0f) 1 else -1
        else position.roundToInt()
    return target.coerceIn(0, lastIndex)
  }

  /** Shorter settle when the finger already dragged most of the way; longer for a full-page jump. */
  fun settleDurationMillis(from: Float, to: Int): Int =
      (abs(to - from) * MAX_SETTLE_MS).roundToInt().coerceIn(MIN_SETTLE_MS, MAX_SETTLE_MS)
}
