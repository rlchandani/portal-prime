/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import org.json.JSONObject

/** Shares the registry-backed appearance and its existing observer with Settings and the remote. */
@Composable
internal fun ApprovedThemeButton() {
    val context = LocalContext.current
    val palette = LocalApprovedPalette.current
    val label = if (palette.dark) "Switch to day theme" else "Switch to night theme"
    Box(Modifier.size(68.dp).clip(CircleShape).background(palette.surface2).approvedOutline(CircleShape)
        .semantics { contentDescription = label }
        .tvFocusable(CircleShape, focusScale = 1f, ringColor = palette.accent) {
            val value = if (palette.dark) "light" else "dark"
            val applied = runCatching {
                SettingsDomains.immortal.apply(context, JSONObject().put("settingsTheme", value))
            }.getOrDefault(emptySet())
            if ("settingsTheme" !in applied) {
                Toast.makeText(context, "Appearance could not be changed.", Toast.LENGTH_SHORT).show()
            }
        }, contentAlignment = Alignment.Center) {
        ApprovedIcon(if (palette.dark) "theme_day" else "theme_night", tint = palette.text)
    }
}
