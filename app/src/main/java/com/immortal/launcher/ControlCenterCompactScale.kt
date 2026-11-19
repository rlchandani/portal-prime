/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The Control Center's whole-screen density scale, by [LayoutClass]. The screens are written in
 * the mock's 1920-wide units; [CcTheme] multiplies the density by this.
 *
 *  - [LayoutClass.LARGE]: exactly the pre-existing rule ([legacy]): 75% below [BREAK_DP] wide, else 1.
 *  - [LayoutClass.COMPACT_LANDSCAPE] (the Mini turned, 1280×740; the 10" Portals, 1280×800): 75%, as
 *    the 1280-wide Portals always had. Never lower: the Mini's dp is already ~0.85 of a true dp,
 *    so a smaller scale would shrink touch targets below the Hearthpane bar.
 *  - [LayoutClass.COMPACT_PORTRAIT] (the Mini upright, 800×1220): [PORTRAIT]. The layouts are
 *    re-arranged (stacked panes, bottom bar) instead of shrunk, so the canvas is ~941×1435.
 */
internal object CcCompactScale {
  const val BREAK_DP = 1700
  const val LEGACY = 0.75f
  const val LANDSCAPE = 0.75f
  const val PORTRAIT = 0.85f

  fun of(layout: LayoutClass, widthDp: Int): Float = when (layout) {
    LayoutClass.LARGE -> legacy(widthDp)
    LayoutClass.COMPACT_LANDSCAPE -> LANDSCAPE
    LayoutClass.COMPACT_PORTRAIT -> PORTRAIT
  }

  fun of(widthDp: Int, heightDp: Int, television: Boolean = false): Float =
      of(LayoutClass.of(widthDp, heightDp, television), widthDp)

  /** The rule before the compact work (width only). */
  fun legacy(widthDp: Int): Float = if (widthDp in 1 until BREAK_DP) LEGACY else 1f

  /** The canvas width (scaled dp) a window of [widthDp] gets. */
  fun canvasWidth(widthDp: Int, heightDp: Int): Float = widthDp / of(widthDp, heightDp)
}
