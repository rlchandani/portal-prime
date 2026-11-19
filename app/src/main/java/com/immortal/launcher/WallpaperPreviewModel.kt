/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Wallpaper › Background swatch grid, pure: columns come from the page width the split view gives
 * the card (no measure-time subcomposition), so the grid composes as plain rows that a page can
 * precompose row by row.
 */
internal object WallpaperSwatchLayout {
    const val MIN_CELL_DP = 180
    const val MAX_COLUMNS = 6
    /** The card's inner padding on each side. */
    const val CARD_PADDING_DP = 18

    fun columnsForWidth(widthDp: Int): Int = (widthDp / MIN_CELL_DP).coerceIn(1, MAX_COLUMNS)

    /** Columns for a split-view page on a screen [screenWidthDp] wide (5 at 1920 dp, 3 at 1280 dp). */
    fun columns(screenWidthDp: Int, singlePane: Boolean = false): Int =
        columnsForWidth(SettingsSplitMetrics.pageContentWidthDp(screenWidthDp, singlePane) - 2 * CARD_PADDING_DP)

    const val GAP_DP = 12
    private const val SWATCH_ASPECT = 240f / 145f
    /** Label line and its top padding under a swatch. */
    private const val LABEL_DP = 34

    /** One row's height (swatch plus label), for its placeholder while it is precomposed. */
    fun rowHeightDp(screenWidthDp: Int, columns: Int = columns(screenWidthDp), singlePane: Boolean = false): Int {
        val inner = SettingsSplitMetrics.pageContentWidthDp(screenWidthDp, singlePane) - 2 * CARD_PADDING_DP
        val cell = (inner - (columns - 1) * GAP_DP).toFloat() / columns.coerceAtLeast(1)
        return (cell / SWATCH_ASPECT).toInt() + LABEL_DP
    }

    /** [items] split into rows of [columns]; the last row may be short. */
    fun <T> rows(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))
}

/** Gradient stops for a background's swatch and mini-Home preview; null for photos and Dark. */
internal fun wallpaperPreviewColors(id: String): List<Long>? = when (id) {
    WallpaperConfig.DUSK_AURORA -> listOf(0xFF050810, 0xFF12234A, 0xFF27436E, 0xFFC98A5E)
    WallpaperConfig.SCREENSAVER -> listOf(0xFF2C3E50, 0xFF4CA1AF)
    WallpaperConfig.SKY -> listOf(0xFF3A7BD5, 0xFF8FD3F4)
    WallpaperConfig.STARFIELD -> listOf(0xFF05060F, 0xFF0C1430)
    else -> WallpaperConfig.gradientColors(id)
}
