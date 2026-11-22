/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Width (px) a row's trailing value gets beside its title/help column. The [capPx] share
 * ([settingsRowValueMaxWidthDp]) only limits the value when the text column needs that room: beside
 * a short title ("Selected") a value like "0 · 17 photos and videos" keeps one line, while a long
 * help text still keeps all but [capPx] of the row.
 */
internal fun settingsRowValueWidthPx(availablePx: Int, textIntrinsicPx: Int, valueIntrinsicPx: Int, capPx: Int): Int {
    if (valueIntrinsicPx <= 0 || availablePx <= 0) return 0
    val besideText = availablePx - textIntrinsicPx
    return minOf(valueIntrinsicPx, maxOf(capPx, besideText), availablePx)
}

/**
 * A title/help column ([content]'s first child) and an optional trailing value (its second child),
 * centred vertically: the value takes [settingsRowValueWidthPx], the column the rest of the width.
 */
@Composable
internal fun SettingsTitleValueLayout(valueCapDp: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val text = measurables[0]
        val value = measurables.getOrNull(1)
        val valueWidth = if (value == null) 0 else settingsRowValueWidthPx(available,
            text.maxIntrinsicWidth(Constraints.Infinity), value.maxIntrinsicWidth(Constraints.Infinity),
            valueCapDp.dp.roundToPx())
        val valuePlaceable = value?.measure(Constraints(maxWidth = valueWidth))
        val textWidth = (available - (valuePlaceable?.width ?: 0)).coerceAtLeast(0)
        val textPlaceable = text.measure(Constraints(minWidth = textWidth, maxWidth = textWidth))
        val height = maxOf(textPlaceable.height, valuePlaceable?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(available, height) {
            textPlaceable.place(0, (height - textPlaceable.height) / 2)
            valuePlaceable?.let { it.place(available - it.width, (height - it.height) / 2) }
        }
    }
}
