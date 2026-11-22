/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The approved mock's size tokens (mocks/ha-dashboard-20261122, `tokens(dev)`), dp and sp as plain
 * numbers so they're unit-testable. Portal+ uses Control Center's spec sizes; the Mini keeps its
 * own held floor (names 20, status 17) instead of CC's 0.75 shrink.
 */
data class HaDashboardDims(
    val pad: Int, val sideGap: Int, val gap: Int, val columns: Int, val rowHeight: Int, val sideWidth: Int,
    val headerHeight: Int, val tilePad: Int, val well: Int, val art: Int, val dial: Int,
    val label: Int, val title: Int, val sub: Int, val room: Int, val roomTitle: Int, val name: Int,
    val status: Int, val small: Int, val button: Int, val big: Int, val huge: Int, val body: Int,
    val empty: Int, val modal: Int,
    /** Round buttons, pills, chips: the visual (not just hit-area) target size. */
    val target: Int,
    val roomRow: Int, val sceneHeight: Int,
    /** Scenes shown in the side column (the portrait strip scrolls through all of them). */
    val sideScenes: Int,
    /** Portrait: rooms and scenes become two sideways strips over a full-width grid. */
    val portrait: Boolean,
) {
  companion object {
    /** Portal+ (1920×1219 dp). */
    val LARGE = HaDashboardDims(pad = 40, sideGap = 32, gap = 20, columns = 4, rowHeight = 176, sideWidth = 440,
        headerHeight = 148, tilePad = 22, well = 56, art = 132, dial = 270,
        label = 21, title = 54, sub = 21, room = 23, roomTitle = 46, name = 22, status = 19, small = 18, button = 22,
        big = 46, huge = 58, body = 24, empty = 44, modal = 50, target = 72, roomRow = 84, sceneHeight = 76,
        sideScenes = 6, portrait = false)

    /** Portal Mini turned (1280×800 class). */
    val COMPACT_LANDSCAPE = HaDashboardDims(pad = 28, sideGap = 24, gap = 16, columns = 3, rowHeight = 156,
        sideWidth = 330, headerHeight = 116, tilePad = 18, well = 52, art = 104, dial = 196,
        label = 17, title = 42, sub = 18, room = 21, roomTitle = 36, name = 20, status = 17, small = 16, button = 20,
        big = 40, huge = 44, body = 20, empty = 36, modal = 40, target = 64, roomRow = 72, sceneHeight = 64,
        sideScenes = 4, portrait = false)

    /** Portal Mini upright (800×1280 class). */
    val COMPACT_PORTRAIT = HaDashboardDims(pad = 24, sideGap = 16, gap = 16, columns = 2, rowHeight = 160,
        sideWidth = 0, headerHeight = 124, tilePad = 18, well = 52, art = 104, dial = 210,
        label = 17, title = 42, sub = 18, room = 20, roomTitle = 36, name = 20, status = 17, small = 16, button = 20,
        big = 40, huge = 46, body = 20, empty = 36, modal = 40, target = 64, roomRow = 64, sceneHeight = 64,
        sideScenes = Int.MAX_VALUE, portrait = true)

    fun of(layout: LayoutClass): HaDashboardDims = when (layout) {
      LayoutClass.LARGE -> LARGE
      LayoutClass.COMPACT_LANDSCAPE -> COMPACT_LANDSCAPE
      LayoutClass.COMPACT_PORTRAIT -> COMPACT_PORTRAIT
    }
  }
}
