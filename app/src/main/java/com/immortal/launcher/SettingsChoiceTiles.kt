/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsChoicePolicy
import com.immortal.launcher.ui.theme.LocalApprovedPalette

private val InlineTrackMinWidth = 420.dp
/** Inline tracks stop here so the title beside them keeps its width (tiles share it evenly). */
private val InlineTrackMaxWidth = 520.dp
private val TileLabelSize = 24.sp
private val DenseTileLabelSize = 21.sp
private const val DENSE_TRACK_TILES = 5
/** Small enough that five tiles (Clock Faces) keep "Hearthpane" whole on the Mini's 744 dp cards. */
private val RowPaddingH = 28.dp
private val RowPaddingV = 18.dp
private val StackGap = 14.dp

/** One tile: what it says, and whether it is the current value. */
internal data class SettingsTile(val label: String, val selected: Boolean)

/**
 * The approved big segmented control: 2–5 one-tap tiles, 76 dp tall so they work by touch from
 * across the room. Short sets sit beside the title; longer ones get the full width below it.
 */
@Composable
internal fun SettingsChoiceTilesRow(
    title: String,
    help: String?,
    tiles: List<SettingsTile>,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    val cardWidth = SettingsSplitMetrics.pageContentWidthDp(LocalConfiguration.current.screenWidthDp,
        LocalSettingsSinglePane.current)
    val stacked = SettingsChoicePolicy.stacked(tiles.map { it.label }, title, cardWidth, help)
    val padding = Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
        .padding(horizontal = RowPaddingH, vertical = RowPaddingV)
    if (stacked) {
        Column(padding, verticalArrangement = Arrangement.spacedBy(StackGap)) {
            ApprovedSettingsLabel(title, help)
            SettingsTileTrack(tiles, enabled, Modifier.fillMaxWidth(), onSelect)
        }
    } else {
        Row(padding, verticalAlignment = Alignment.CenterVertically) {
            ApprovedSettingsLabel(title, help, Modifier.weight(1f).padding(end = 24.dp))
            SettingsTileTrack(tiles, enabled, Modifier.widthIn(min = InlineTrackMinWidth, max = InlineTrackMaxWidth), onSelect)
        }
    }
}

/**
 * The track and its tiles. A thin adapter over the shared [HearthSegmented] using the **accent**
 * variant: the fidelity mock approved a solid-accent (blue) selected tile with [accentInk] text for
 * these one-choice rows. The selected tile now glides between options. Each tile keeps its own
 * RadioButton focus target for the remote (via [tvFocusable] with the outer `semantics` role).
 */
@Composable
internal fun SettingsTileTrack(
    tiles: List<SettingsTile>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    // Five tiles on the Mini's cards (Clock Faces) need the smaller label to keep "Hearthpane" whole.
    val cardWidth = SettingsSplitMetrics.pageContentWidthDp(LocalConfiguration.current.screenWidthDp,
        LocalSettingsSinglePane.current)
    val labelSize = if (tiles.size >= DENSE_TRACK_TILES && SettingsSplitMetrics.compactCards(cardWidth)) DenseTileLabelSize
        else TileLabelSize
    val pillShape = RoundedCornerShape(HearthSegmentedTokens.PillCorner)
    HearthSegmented(
        segments = tiles.map { HearthSegment(it.label) },
        selectedIndex = tiles.indexOfFirst { it.selected },
        colors = HearthSegmentedDefaults.colors(LocalApprovedPalette.current, HearthSegmentedVariant.ACCENT),
        modifier = modifier,
        enabled = enabled,
        fillWidth = true,
        labelSize = labelSize,
        cellModifier = { index, sel, _ ->
            Modifier.tvFocusable(pillShape, focusScale = 1f, ringColor = IosBlue, enabled = enabled, role = null) {
                if (enabled) onSelect(index)
            }.semantics { role = Role.RadioButton; selected = sel }
        },
    )
}
