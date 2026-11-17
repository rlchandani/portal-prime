/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the two miniature Settings screens (background, sidebar block, cards). */
private data class AppearanceSwatch(val label: String, val dark: Boolean, val background: Color, val block: Color)

private val Swatches = listOf(
    AppearanceSwatch("Light", dark = false, background = Color(0xFFEEF0F4), block = Color.White),
    AppearanceSwatch("Dark", dark = true, background = Color(0xFF121418), block = Color(0xFF26292F)),
)
private val PreviewWidth = 220.dp
private val PreviewHeight = 146.dp
private val PreviewRadius = 18.dp
private val BlockRadius = 8.dp
private val PreviewInset = 14.dp
private val RingSize = 36.dp
private val RingStroke = 3.dp
private val RingDot = 16.dp
private val SelectedStroke = 4.dp
private val PreviewOutline = Color(0x4D7F7F7F)

/** Light / Dark as two tappable previews, as in the approved mock (one tap, readable from afar). The
 * caller decides whether tapping the current choice writes (a displayed fallback may need persisting). */
@Composable
internal fun SettingsAppearanceTiles(dark: Boolean, enabled: Boolean, onSelect: (dark: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally)) {
        Swatches.forEach { swatch ->
            val selected = swatch.dark == dark
            Column(
                Modifier.tvFocusable(RoundedCornerShape(PreviewRadius), focusScale = 1f, ringColor = IosBlue,
                    enabled = enabled) { if (enabled) onSelect(swatch.dark) }
                    .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = swatch.label }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppearancePreview(swatch, selected)
                Text(swatch.label, color = IosLabel, fontSize = 24.sp, modifier = Modifier.padding(top = 14.dp))
                AppearanceRing(selected)
            }
        }
    }
}

@Composable
private fun AppearancePreview(swatch: AppearanceSwatch, selected: Boolean) {
    val shape = RoundedCornerShape(PreviewRadius)
    Row(
        Modifier.size(PreviewWidth, PreviewHeight).background(swatch.background, shape)
            .border(if (selected) SelectedStroke else 1.dp, if (selected) IosBlue else PreviewOutline, shape)
            .padding(PreviewInset),
        horizontalArrangement = Arrangement.spacedBy(PreviewInset),
    ) {
        Box(Modifier.weight(.9f).fillMaxHeight().background(swatch.block, RoundedCornerShape(BlockRadius)))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(PreviewInset)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(swatch.block, RoundedCornerShape(BlockRadius)))
            Box(Modifier.weight(1f).fillMaxWidth().background(swatch.block, RoundedCornerShape(BlockRadius)))
        }
    }
}

@Composable
private fun AppearanceRing(selected: Boolean) {
    Box(
        Modifier.padding(top = 10.dp).size(RingSize)
            .then(if (selected) Modifier.background(IosBlue, CircleShape)
                else Modifier.border(RingStroke, IosSecondary, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(RingDot).background(Color.White, CircleShape))
    }
}
