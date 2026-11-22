/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/**
 * The one vertical rhythm of a Settings page, in dp, shared by the page layout and every bespoke
 * block (pure, so tests can pin it):
 *  - [SECTION_GAP_DP] between sections (a header, its card(s) and footer);
 *  - [CARD_GAP_DP] between two cards inside one section (rows above a block's own card, or two
 *    cards a block stacks). Cards never touch;
 *  - [TEXT_INSET_DP] the start/end inset of section headers, block labels and footers, so every
 *    caption lines up with the text inside the cards (rows are inset 28 dp);
 *  - [HEADER_GAP_DP] between a header and its card, [FOOTER_GAP_DP] between a card and its footer.
 */
internal object SettingsSpacing {
    const val SECTION_GAP_DP = 34
    const val CARD_GAP_DP = 20
    const val TEXT_INSET_DP = 28
    const val HEADER_GAP_DP = 10
    const val FOOTER_GAP_DP = 10
}

/** Space between two sections of a page. */
@Composable
internal fun SettingsSectionGap() = Spacer(Modifier.height(SettingsSpacing.SECTION_GAP_DP.dp))

/** Space between two cards of one section; use it wherever a block stacks cards. */
@Composable
internal fun SettingsCardGap() = Spacer(Modifier.height(SettingsSpacing.CARD_GAP_DP.dp))

/**
 * [content] with a [SettingsSpacing.CARD_GAP_DP] above ([before]) and/or below ([after]), only when
 * it draws something: a block that is empty in its current state (Drop-ins off) leaves no gap.
 */
@Composable
internal fun SettingsCardGapsIfDrawn(before: Boolean, after: Boolean, content: @Composable () -> Unit) {
    if (!before && !after) { content(); return }
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val height = placeables.sumOf { it.height }
        val gap = SettingsSpacing.CARD_GAP_DP.dp.roundToPx()
        val top = if (before && height > 0) gap else 0
        val bottom = if (after && height > 0) gap else 0
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth),
            (top + height + bottom).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = top
            placeables.forEach { it.place(0, y); y += it.height }
        }
    }
}
