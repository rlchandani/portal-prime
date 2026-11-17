/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.SettingsChoiceStyle
import com.immortal.launcher.settings.SettingsChoicePolicy

private const val THEME_DARK = "dark"
private const val THEME_LIGHT = "light"

/**
 * A registry choice drawn as the approved mock does: Light/Dark previews, big one-tap tiles for
 * short sets, or a value row with a list for long ones. Writes go through [onSelect] only.
 */
@Composable
internal fun SettingsChoiceControl(
    domainId: String,
    key: String,
    title: String,
    help: String?,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    applyCurrentSelection: Boolean,
    onSelect: (String) -> Unit,
) {
    fun choose(value: String) {
        if (value != selected || applyCurrentSelection) onSelect(value)
    }
    val labels = options.map { (value, label) -> SettingsChoicePolicy.tileLabel(domainId, key, value, label) }
    when (SettingsChoicePolicy.enumStyle(domainId, key, labels)) {
        SettingsChoiceStyle.APPEARANCE ->
            SettingsAppearanceTiles(selected == THEME_DARK, enabled) { dark -> choose(if (dark) THEME_DARK else THEME_LIGHT) }
        SettingsChoiceStyle.TILES -> SettingsChoiceTilesRow(title, help,
            options.mapIndexed { index, (value, _) -> SettingsTile(labels[index], value == selected) }, enabled) { index ->
            choose(options[index].first)
        }
        SettingsChoiceStyle.LIST -> PickerRow(title, selected, options.map { (value, label) -> PickerOption(value, label) },
            enabled = enabled, applyCurrentSelection = applyCurrentSelection, onSelect = ::choose)
    }
}

/** A registry number: a slider for levels, preset tiles (with Custom) where the mock has them, else the editor row. */
@Composable
internal fun <S> SettingsNumberControl(
    domainId: String,
    spec: IntSpec<S>,
    title: String,
    value: Int,
    help: String?,
    enabled: Boolean,
    onApply: (Int) -> Unit,
) {
    when {
        SettingsChoicePolicy.isSlider(domainId, spec.key) ->
            SettingsSliderRow(title, help, value, spec.min, spec.max, spec.step, spec.format, enabled, onApply)
        SettingsChoicePolicy.hasPresets(domainId, spec.key) -> SettingsPresetControl(domainId, spec, title, value, help, enabled, onApply)
        else -> ApprovedIntSetting(spec, value, help, enabled, title, onApply)
    }
}

@Composable
private fun <S> SettingsPresetControl(
    domainId: String,
    spec: IntSpec<S>,
    title: String,
    value: Int,
    help: String?,
    enabled: Boolean,
    onApply: (Int) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    val tiles = remember(domainId, spec, value) {
        SettingsChoicePolicy.presetTiles(domainId, spec.key, spec.min, spec.max, spec.step, value, spec.format)
    }
    val selected = SettingsChoicePolicy.selectedTile(tiles, value)
    SettingsChoiceTilesRow(title, help, tiles.mapIndexed { index, tile -> SettingsTile(tile.label, index == selected) },
        enabled) { index ->
        val tile = tiles[index]
        when {
            tile.custom -> custom = true
            tile.value != value -> onApply(tile.value)
        }
    }
    if (custom && enabled) ApprovedIntDialog(spec, value, help, enabled, title, onDismiss = { custom = false }, onApply = onApply)
}
