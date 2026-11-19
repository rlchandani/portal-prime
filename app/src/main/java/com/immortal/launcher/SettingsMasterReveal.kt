/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/**
 * When Settings opens, its master column (title, search, profile card and the grouped pages) is built
 * over a few frames and then appears at once. Built in one frame it was the slowest frame of opening
 * Settings (Portal Mini 54 ms, Portal+ 80 ms of main-thread work). The first frame composes the title;
 * each later one adds the search field and [SettingsMasterRevealPlan.ENTRIES_PER_FRAME] list entries,
 * until the list fills its viewport. Until then the column draws only its backdrop, so nothing appears
 * row by row. Only the master column composed while Settings opens is staged; one composed later
 * (single-pane Back to the list) composes in full at once.
 */
internal class SettingsMasterReveal(staged: Boolean) {
    /** Read in the draw phase only, so revealing redraws the column and recomposes nothing. */
    var shown by mutableStateOf(!staged)
        private set

    /** The search field is composed (it stands in as a same-height spacer before). */
    var searchComposed by mutableStateOf(!staged)
        private set

    /** How many sidebar list entries are composed. */
    var listLimit by mutableIntStateOf(if (staged) 0 else Int.MAX_VALUE)
        private set

    /** Runs while the master column is first composed. If cancelled, everything composes at once. */
    suspend fun run(list: LazyListState, totalEntries: Int) {
        try {
            while (!shown) {
                withFrameNanos {
                    val next = SettingsMasterRevealPlan.next(listLimit, totalEntries, viewportFilled(list.layoutInfo))
                    if (next == null) shown = true else {
                        searchComposed = true
                        listLimit = next
                    }
                }
            }
        } finally {
            searchComposed = true
            listLimit = Int.MAX_VALUE
            shown = true
        }
    }

    private fun viewportFilled(info: LazyListLayoutInfo): Boolean {
        val last = info.visibleItemsInfo.lastOrNull() ?: return false
        return last.offset + last.size >= info.viewportEndOffset
    }
}

/** Pure steps of [SettingsMasterReveal]. */
internal object SettingsMasterRevealPlan {
    /** About 3 ms of composition, measurement and drawing each on the Portal Mini: a step fits one frame. */
    const val ENTRIES_PER_FRAME = 3

    /** The next entry limit, or null once the list is complete: every entry is in or it fills its viewport. */
    fun next(limit: Int, total: Int, viewportFilled: Boolean): Int? =
        if (viewportFilled || limit >= total) null else minOf(total, limit + ENTRIES_PER_FRAME)
}

/** Staged when the master column first composes in the opening's [SettingsOpenStage.MASTER] frame. */
@Composable
internal fun rememberSettingsMasterReveal(stage: SettingsOpenStage): SettingsMasterReveal =
    remember { SettingsMasterReveal(staged = stage == SettingsOpenStage.MASTER) }

/** Starts [reveal] when the master column first composes; a no-op once it is shown. */
@Composable
internal fun SettingsMasterRevealEffect(reveal: SettingsMasterReveal, list: LazyListState, totalEntries: Int) {
    LaunchedEffect(reveal) { if (!reveal.shown) reveal.run(list, totalEntries) }
}

/** Draws the content (not what this modifier's predecessors draw, such as a background) once [reveal] is shown. */
internal fun Modifier.settingsRevealed(reveal: SettingsMasterReveal): Modifier =
    drawWithContent { if (reveal.shown) drawContent() }
