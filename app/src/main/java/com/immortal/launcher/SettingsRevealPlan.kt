/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which entries of a newly opened page compose in the switch frame. Profiling on the Family Portal
 * put a Text at about 1.15 ms to compose and measure, so the switch frame takes a few cheap
 * entries from the first visible one down; everything else (every bespoke block, and rows past
 * the budget) is precomposed over the next frames. Pure: weights are Text counts.
 */
internal object SettingsRevealPlan {
    /** About 9 ms of Text work, so the switch frame stays well inside the 40 ms target. */
    const val FIRST_FRAME_TEXT_BUDGET = 8

    /**
     * Rows a long in-card list (Start on boot's apps) adds per frame when its data arrives after the
     * page settled, so that frame never composes the whole list (two Texts and a switch per row).
     */
    const val LIST_ROWS_PER_FRAME = 2

    /** Never composed in the switch frame: a block's cost is its whole editor. */
    const val DEFERRED = Int.MAX_VALUE

    private const val LEDE_TEXTS = 3
    private const val TEXTS_PER_ROW = 2
    private const val HINT_TEXTS = 2

    // Placeholder heights (dp) while an entry is precomposed; close to the real height so the list
    // composes about as many entries as will actually fit.
    private const val LEDE_DP = 150
    private const val HEADER_DP = 38
    private const val FOOTER_DP = 44
    private const val ROW_DP = 100
    private const val NOTICE_DP = 110
    private const val HINT_DP = 110
    private const val GAP_DP = SettingsSpacing.SECTION_GAP_DP
    private const val BLOCK_DP = 420

    fun weight(entry: SettingsPageEntry): Int = when (entry.kind) {
        SettingsEntryKind.LEDE -> LEDE_TEXTS
        SettingsEntryKind.HEADER, SettingsEntryKind.FOOTER, SettingsEntryKind.NOTICE, SettingsEntryKind.LINK -> 1
        SettingsEntryKind.EXTERNAL -> if ((entry.item as? SettingsExternalItem)?.subtitle.isNullOrEmpty()) 1 else 2
        SettingsEntryKind.ROWS -> TEXTS_PER_ROW * ((entry.item as? SettingsDomainRows)?.keys?.size ?: 1)
        SettingsEntryKind.ADVANCED_HINT -> HINT_TEXTS
        SettingsEntryKind.GAP -> 0
        SettingsEntryKind.BLOCK -> DEFERRED
    }

    fun placeholderDp(entry: SettingsPageEntry): Int = when (entry.kind) {
        SettingsEntryKind.LEDE -> LEDE_DP
        SettingsEntryKind.HEADER -> HEADER_DP
        SettingsEntryKind.FOOTER -> FOOTER_DP
        SettingsEntryKind.NOTICE -> NOTICE_DP
        SettingsEntryKind.LINK, SettingsEntryKind.EXTERNAL -> ROW_DP
        SettingsEntryKind.ROWS -> ROW_DP * ((entry.item as? SettingsDomainRows)?.keys?.size ?: 1)
        SettingsEntryKind.ADVANCED_HINT -> HINT_DP
        SettingsEntryKind.GAP -> GAP_DP
        SettingsEntryKind.BLOCK -> BLOCK_DP +
            SettingsSpacing.CARD_GAP_DP * ((if (entry.gapBefore) 1 else 0) + (if (entry.gapAfter) 1 else 0))
    }

    /**
     * Keys composed directly in the switch frame: consecutive entries from [start] while their
     * weights fit [budget]. It stops at the first entry that does not fit, so nothing below a
     * pending entry appears before it.
     */
    fun immediateKeys(entries: List<SettingsPageEntry>, start: Int, budget: Int = FIRST_FRAME_TEXT_BUDGET): Set<String> {
        val keys = LinkedHashSet<String>()
        var spent = 0
        for (index in start.coerceAtLeast(0) until entries.size) {
            val weight = weight(entries[index])
            if (weight == DEFERRED || spent + weight > budget) break
            spent += weight
            keys += entries[index].key
        }
        return keys
    }

    /** How many of a late list's [total] rows show next frame, given [shown] now. */
    fun nextListRowCount(shown: Int, total: Int, perFrame: Int = LIST_ROWS_PER_FRAME): Int =
        (shown.coerceAtLeast(0) + perFrame.coerceAtLeast(1)).coerceAtMost(total.coerceAtLeast(0))
}
