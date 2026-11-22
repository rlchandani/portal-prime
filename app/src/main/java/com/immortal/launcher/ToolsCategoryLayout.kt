/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The Tools library's compact geometry (pure, JVM-tested): its category chips and its card
 * columns. On compact windows the chips sit in a measured flow row (wrapping, never scrolling
 * sideways); upright they use tighter padding so the whole set fits one line at the default text
 * size, and the cards go single-column so a wrapped description never leaves its neighbour shorter.
 */
internal object ToolsCategoryLayout {
  /** One chip family: label padding each side and the gap between neighbours, dp. */
  data class Chips(val padding: Int, val gap: Int)

  /** The Portal+'s scrolling row, as shipped. */
  val LARGE = Chips(padding = 24, gap = 12)
  /** The Mini turned: the shipped look, a little tighter. */
  val LANDSCAPE = Chips(padding = 20, gap = 12)
  /** The Mini upright: tight enough for every category, Advanced included, on one line. */
  val PORTRAIT = Chips(padding = 14, gap = 4)

  /** The gap between wrapped chip lines and the inset around the chip block, dp. */
  const val ROW_GAP_DP = 12
  const val INSET_DP = 4

  fun of(cls: LayoutClass): Chips = when (cls) {
    LayoutClass.LARGE -> LARGE
    LayoutClass.COMPACT_LANDSCAPE -> LANDSCAPE
    LayoutClass.COMPACT_PORTRAIT -> PORTRAIT
  }

  /** 22 sp medium label (about 13 dp a character, measured on the Portal) + [padding] each side. */
  fun chip(chips: Chips) = CompactRowFit.Spec(charDp = 13.0, chromeDp = 2.0 * chips.padding)

  /** Below this content width (dp) the library has two card columns instead of three. */
  const val THREE_COLUMN_MIN_DP = 1200.0

  /** Card columns for a library [widthDp] wide: one upright, else two or three by width. */
  fun libraryColumns(cls: LayoutClass, widthDp: Double): Int = when {
    cls.compactPortrait -> 1
    widthDp < THREE_COLUMN_MIN_DP -> 2
    else -> 3
  }
}
