/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The Store browse page's vertical budget (pure, JVM-tested). The Portal+ keeps its shipped tall
 * banner. The Mini turned (~600 dp between the header and the dock) used it too, so only one row of
 * app cards showed; there the banner becomes a one-line strip and Search shares its row with
 * Updates, so the first card row and most of the second show without scrolling.
 */
internal object StoreBrowseLayout {
  /** How the banner and the toolbar above the cards are laid out, dp. */
  data class Top(
      val topPadding: Int,
      /** The banner: a strip (title beside its line, icons at the end) or the shipped block. */
      val strip: Boolean,
      val bannerHeight: Int,
      val bannerGap: Int,
      /** Search and Updates on one row (true) or Search above the buttons (false). */
      val searchBesideActions: Boolean,
      val toolbarHeight: Int,
      val headingHeight: Int,
  ) {
    /** Height from the top of the grid to the first card. */
    val aboveCards: Int get() = topPadding + bannerHeight + bannerGap + toolbarHeight + headingHeight
  }

  const val SEARCH_HEIGHT = 68
  const val ACTION_GAP = 24
  /** A category heading: 28 top + a 30 line + 12 bottom. */
  const val HEADING = 28 + 30 + 12
  /** A card row: min 158 tall + its 20 bottom gap; a three-line description makes it ~187. */
  const val CARD_ROW = 158 + 20
  const val TALL_CARD_ROW = 187 + 20

  /** The shipped banner (40 padding around a 46 sp title over two lines of 24 sp text). */
  val LARGE = Top(topPadding = 36, strip = false, bannerHeight = 245, bannerGap = 28, searchBesideActions = false,
      toolbarHeight = SEARCH_HEIGHT + ACTION_GAP + SEARCH_HEIGHT, headingHeight = HEADING)

  /** The upright Mini keeps its stacked hero; the catalog is one column and scrolls. */
  val PORTRAIT = LARGE.copy(bannerHeight = 330)

  /** Strip: 16 padding around a 28 sp title (34) over a 19 sp line (26); two 56 icons at the end. */
  const val STRIP_PADDING = 16
  const val STRIP_TITLE_SP = 28
  const val STRIP_TITLE_LINE = 34
  const val STRIP_LINE_GAP = 2
  const val STRIP_LINE_SP = 19
  const val STRIP_LINE_LINE = 26
  const val STRIP_ICON = 56
  val LANDSCAPE = Top(topPadding = 16, strip = true,
      bannerHeight = 2 * STRIP_PADDING + maxOf(STRIP_ICON, STRIP_TITLE_LINE + STRIP_LINE_GAP + STRIP_LINE_LINE),
      bannerGap = 16, searchBesideActions = true, toolbarHeight = SEARCH_HEIGHT, headingHeight = HEADING - 12)

  /** The strip's category heading trims 12 of the shipped 28 top padding. */
  fun headingTopPadding(top: Top): Int = if (top.strip) 16 else 28

  fun of(cls: LayoutClass): Top = when (cls) {
    LayoutClass.LARGE -> LARGE
    LayoutClass.COMPACT_PORTRAIT -> PORTRAIT
    LayoutClass.COMPACT_LANDSCAPE -> LANDSCAPE
  }

  /** Card rows visible (fractional) in a grid [contentHeight] dp tall, each row [rowHeight] tall. */
  fun rowsVisible(top: Top, contentHeight: Int, rowHeight: Int = TALL_CARD_ROW): Double =
      ((contentHeight - top.aboveCards).coerceAtLeast(0)).toDouble() / rowHeight
}
