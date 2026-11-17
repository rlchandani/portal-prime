/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ChipSize = 76.dp
private val ChipGap = 12.dp
private val ChipRowPaddingH = 28.dp
private val ChipRowPaddingV = 18.dp

/**
 * Weekday chips inside a settings card (title above, one 76 dp circle per day), shared by the
 * alarm and sunrise wake. [days] pairs each day id with its one-letter label; [names] are the
 * locale's full weekday names, indexed by day id, for accessibility.
 */
@Composable
internal fun SettingsDayChipsRow(
    title: String,
    days: List<Pair<Int, String>>,
    selected: Set<Int>,
    names: Array<String>,
    enabled: Boolean = true,
    onToggle: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ChipRowPaddingH, vertical = ChipRowPaddingV),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ApprovedSettingsLabel(title, null)
        Row(horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
            days.forEach { (day, label) ->
                val on = day in selected
                Box(
                    Modifier.size(ChipSize).background(if (on) IosBlue else IosSegmentTrack, CircleShape)
                        .semantics { contentDescription = names.getOrElse(day) { label }; this.selected = on }
                        .then(if (enabled) Modifier.tvFocusable(CircleShape, focusScale = 1f) { onToggle(day) }
                            else Modifier.semantics { disabled() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) MaterialTheme.colorScheme.onPrimary else IosLabel, fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
