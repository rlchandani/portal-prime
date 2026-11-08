/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Result of checking the player viewport against YouTube's minimum. */
enum class YtSizeVerdict { NOT_LAID_OUT, OK, TOO_SMALL }

/**
 * YouTube's Required Minimum Functionality: the embedded player's viewport must be at least
 * 200×200 CSS pixels. In a WebView, CSS pixels are view pixels divided by display density.
 */
object YtPlayerSize {
  const val MIN_CSS_PX = 200

  fun verdict(widthPx: Int, heightPx: Int, density: Float): YtSizeVerdict {
    if (widthPx <= 0 || heightPx <= 0) return YtSizeVerdict.NOT_LAID_OUT
    val scale = if (density.isFinite() && density > 0f) density else 1f
    val ok = widthPx / scale >= MIN_CSS_PX && heightPx / scale >= MIN_CSS_PX
    return if (ok) YtSizeVerdict.OK else YtSizeVerdict.TOO_SMALL
  }
}
