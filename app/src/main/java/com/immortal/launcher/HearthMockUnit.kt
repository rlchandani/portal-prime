/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The approved screensaver mock (screensaver-premium-20261118) sizes everything in a 1440-wide
 * design space: one mock unit is `frame width / 1440` dp, so on the 1920x1280 dp Portal+ a "176"
 * clock is 234.7 dp. Faces that used the raw numbers as dp drew at 75% of the approved size.
 *
 * [boost] is the extra factor over the raw numbers. It applies only to Portal+-class surfaces
 * (short side at least [MIN_SHORT_DP] dp), so smaller panels, the Mini and in-Settings previews
 * keep their existing sizes.
 */
internal object HearthMockUnit {
  const val DESIGN_WIDTH_DP = 1440f
  const val MIN_SHORT_DP = 1219f
  const val MAX_BOOST = 1.4f

  /** Mock unit over raw dp for a [widthPx] x [heightPx] surface at [density]; 1 when not Portal+-class. */
  fun boost(widthPx: Int, heightPx: Int, density: Float): Float {
    if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return 1f
    val shortDp = minOf(widthPx, heightPx) / density
    val longDp = maxOf(widthPx, heightPx) / density
    return if (shortDp < MIN_SHORT_DP) 1f else (longDp / DESIGN_WIDTH_DP).coerceIn(1f, MAX_BOOST)
  }
}
