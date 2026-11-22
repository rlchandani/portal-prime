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

private val TrackRadius = 18.dp
private val TrackPadding = 5.dp
private val TileGap = 4.dp
private val TileRadius = 14.dp
private val TileMinHeight = 76.dp
private val TileMinWidth = 96.dp
private val InlineTrackMinWidth = 420.dp
/** Inline tracks stop here so the title beside them keeps its width (tiles share it evenly). */
private val InlineTrackMaxWidth = 520.dp
private val TileLabelSize = 24.sp
private val RowPaddingH = 28.dp
private val RowPaddingV = 18.dp
private val StackGap = 14.dp
private const val DISABLED_ALPHA = .45f
/** Selected tile on the dark track: the mock's lifted grey (the card colour is too close to the track). */
private val DarkSelectedTile = Color(0xFF4A4E57)

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

/** The track and its tiles. Each tile is its own focus target for the remote. */
@Composable
internal fun SettingsTileTrack(
    tiles: List<SettingsTile>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val selectedFill = if (LocalApprovedPalette.current.dark) DarkSelectedTile else IosCard
    val shape = RoundedCornerShape(TileRadius)
    Row(
        modifier.background(IosSegmentTrack, RoundedCornerShape(TrackRadius)).padding(TrackPadding)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        tiles.forEachIndexed { index, tile ->
            Box(
                Modifier.weight(1f).defaultMinSize(minWidth = TileMinWidth, minHeight = TileMinHeight)
                    .background(if (tile.selected) selectedFill else Color.Transparent, shape)
                    .tvFocusable(shape, focusScale = 1f, ringColor = IosBlue, enabled = enabled, role = null) {
                        if (enabled) onSelect(index)
                    }
                    .semantics { role = Role.RadioButton; selected = tile.selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(tile.label, color = IosLabel, fontSize = TileLabelSize, maxLines = 1, textAlign = TextAlign.Center,
                    fontWeight = if (tile.selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}
