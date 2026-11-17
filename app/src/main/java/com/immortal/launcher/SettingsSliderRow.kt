/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.SettingsChoicePolicy

private val SliderRowPaddingH = 28.dp
private val SliderRowPaddingV = 18.dp

/**
 * A level as the approved mock draws it: title and value on one line, a full-width slider below.
 * The finger only moves local state; [onCommit] runs once, when it lifts, so dragging never
 * floods the settings writers. The value snaps to the editor's own [step] and bounds.
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    help: String?,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    format: (Int) -> String,
    enabled: Boolean = true,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember(value) { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    Column(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight)
            .padding(horizontal = SliderRowPaddingH, vertical = SliderRowPaddingV),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApprovedSettingsLabel(title, help, Modifier.weight(1f).padding(end = 24.dp))
            Text(format(shown), color = IosSecondary, fontSize = Sizes.rowValue)
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = SettingsChoicePolicy.snap(it, min, max, step) },
            onValueChangeFinished = {
                // Keep showing the chosen level until the saved value catches up (remember(value)).
                val chosen = dragging
                if (chosen == null || chosen == value) dragging = null else onCommit(chosen)
            },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = IosBlue, activeTrackColor = IosBlue, inactiveTrackColor = IosDivider),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Adjust ${title.lowercase()}" },
        )
    }
}
