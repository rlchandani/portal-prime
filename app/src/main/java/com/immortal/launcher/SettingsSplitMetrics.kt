/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The split view's widths in dp, pure so editors can size grids without measuring first: the left
 * column is about a third of the screen (600 dp at 1920, 420 dp at 1280), the page is capped and
 * inset on both sides.
 */
internal object SettingsSplitMetrics {
    const val SIDEBAR_FRACTION = .31f
    const val SIDEBAR_MIN_DP = 420
    const val SIDEBAR_MAX_DP = 600
    const val DIVIDER_DP = 1
    const val PAGE_MAX_WIDTH_DP = 1120
    const val PAGE_SIDE_PADDING_DP = 56
    /** Single-pane Settings (narrow windows, see [settingsSinglePane]): the page has the full width. */
    const val SINGLE_PANE_SIDE_PADDING_DP = 28

    /**
     * Below this card width (dp: the Mini's 744 dp cards, either way up) side-by-side content in a
     * page (a preview beside a form, two tiles a row) would squeeze its text; it stacks instead.
     */
    const val COMPACT_CARDS_BELOW_DP = 960

    fun compactCards(cardWidthDp: Int): Boolean = cardWidthDp in 1 until COMPACT_CARDS_BELOW_DP

    /** A left column narrower than this (the Mini turned: 420 dp) uses the compact row type. */
    const val SIDEBAR_COMPACT_BELOW_DP = 500

    /** Whether the split view's left column is narrow enough for the compact row type. */
    fun sidebarCompact(screenWidthDp: Int, singlePane: Boolean): Boolean =
        !singlePane && sidebarWidthDp(screenWidthDp) < SIDEBAR_COMPACT_BELOW_DP

    fun sidebarWidthDp(screenWidthDp: Int): Int =
        (screenWidthDp * SIDEBAR_FRACTION).toInt().coerceIn(SIDEBAR_MIN_DP, SIDEBAR_MAX_DP)

    /** Width of a page's cards (inside the page's side padding). */
    fun pageContentWidthDp(screenWidthDp: Int, singlePane: Boolean = false): Int {
        if (singlePane)
            return (screenWidthDp.coerceAtMost(PAGE_MAX_WIDTH_DP) - 2 * SINGLE_PANE_SIDE_PADDING_DP).coerceAtLeast(0)
        val detail = (screenWidthDp - sidebarWidthDp(screenWidthDp) - DIVIDER_DP).coerceAtMost(PAGE_MAX_WIDTH_DP)
        return (detail - 2 * PAGE_SIDE_PADDING_DP).coerceAtLeast(0)
    }
}
