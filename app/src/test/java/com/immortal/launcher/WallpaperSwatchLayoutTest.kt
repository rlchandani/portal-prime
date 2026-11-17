/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperSwatchLayoutTest {
    @Test fun splitViewWidthsMatchThePortals() {
        assertEquals(595, SettingsSplitMetrics.sidebarWidthDp(1920))
        assertEquals(420, SettingsSplitMetrics.sidebarWidthDp(1280))
        // 1920: the 1324 dp detail is capped at 1120, less 2 × 56 padding.
        assertEquals(1008, SettingsSplitMetrics.pageContentWidthDp(1920))
        assertEquals(747, SettingsSplitMetrics.pageContentWidthDp(1280))
    }

    @Test fun columnsMatchTheOldMeasuredGrid() {
        // The card's inner width divided by 180 dp, as BoxWithConstraints computed it.
        assertEquals(5, WallpaperSwatchLayout.columns(1920))
        assertEquals(3, WallpaperSwatchLayout.columns(1280))
        assertEquals(1, WallpaperSwatchLayout.columnsForWidth(10))
        assertEquals(WallpaperSwatchLayout.MAX_COLUMNS, WallpaperSwatchLayout.columnsForWidth(5_000))
    }

    @Test fun rowsKeepEveryChoiceInOrder() {
        val items = (1..14).toList()
        val rows = WallpaperSwatchLayout.rows(items, 5)
        assertEquals(listOf(5, 5, 4), rows.map { it.size })
        assertEquals(items, rows.flatten())
        assertEquals(14, WallpaperSwatchLayout.rows(items, 0).size)
    }

    @Test fun rowPlaceholderIsSwatchPlusLabel() {
        val height = WallpaperSwatchLayout.rowHeightDp(1920)
        // 5 columns of (972 - 48) / 5 = 184.8 dp at 240:145, plus the label.
        assertEquals((184.8f / (240f / 145f)).toInt() + 34, height)
        assertTrue(WallpaperSwatchLayout.rowHeightDp(1280) > 0)
    }

    @Test fun previewColorsCoverGradientsAndLeavePhotosToTheirImage() {
        WallpaperConfig.GRADIENTS.forEach { (id, colors) -> assertEquals(colors, wallpaperPreviewColors(id)) }
        assertTrue(wallpaperPreviewColors(WallpaperConfig.DUSK_AURORA)!!.size > 1)
        assertNull(wallpaperPreviewColors(WallpaperConfig.DARK))
        assertNull(wallpaperPreviewColors(WallpaperConfig.PHOTOS.first().first))
    }
}
