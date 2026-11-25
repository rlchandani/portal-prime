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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * The one Remove action of Settings (list rows, startup entries, paired phones and the "Remove …?"
 * confirmations): a 68 dp tonal button with the palette's error-red label, in both themes. Pass a
 * null [onClick] when the enclosing row owns focus and the click ([ActionSettingRow]).
 */
@Composable
internal fun SettingsRemoveButton(
    label: String = "Remove",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    spoken: String = label,
    onClick: (() -> Unit)?,
) {
    val p = LocalApprovedPalette.current
    val shape = RoundedCornerShape(16.dp)
    Surface(color = p.surface2, shape = shape,
        // A spoken name that differs from the visible word tells a repeated "Remove" apart by its row.
        modifier = modifier.defaultMinSize(minHeight = 68.dp)
            .let { if (spoken != label) it.ccSpeak(spoken) else it }
            .then(if (onClick != null) Modifier.tvFocusable(shape, focusScale = 1f, ringColor = p.accent,
                enabled = enabled, onClick = onClick) else Modifier)) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) p.error else p.secondary)
        }
    }
}

/**
 * The one "can't be changed" value of Settings (Calls privacy, People › Privacy & Camera): a lock
 * glyph and the value in a secondary pill, so every locked row reads the same on every page.
 */
@Composable
internal fun SettingsLockedValue(text: String = "Always") {
    val p = LocalApprovedPalette.current
    Row(Modifier.height(52.dp).background(p.surface2, RoundedCornerShape(50)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ApprovedIcon("lock", Modifier.size(24.dp), p.secondary)
        Text(text, color = p.secondary, fontSize = Sizes.rowValue, fontWeight = FontWeight.SemiBold)
    }
}
