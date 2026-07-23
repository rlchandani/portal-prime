/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.GlassBorder
import com.immortal.launcher.ui.theme.GlassHighlight
import com.immortal.launcher.ui.theme.GlassSurface

object GlassTileDefaults {
    val CornerRadius: Dp = 20.dp
    val BorderWidth: Dp = 1.dp
}

/**
 * A frosted-glass tile surface. On API 31+ the background blurs the content
 * behind it. On API <31 (Portal's Android 9/10) it degrades to a translucent
 * dark fill — still premium-looking, just without the blur.
 *
 * Usage:
 *   GlassTile(modifier = Modifier.size(96.dp), onClick = { ... }) {
 *       Icon(...)
 *       Text(...)
 *   }
 */
@Composable
fun GlassTile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = GlassTileDefaults.CornerRadius,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }

    // On API <31: blur modifier is a no-op so we use a slightly more opaque fill
    val fillColor = if (Build.VERSION.SDK_INT >= 31) GlassSurface
                    else Color(0x22FFFFFF)  // 13% white — a touch more visible without blur

    val blurModifier = if (Build.VERSION.SDK_INT >= 31) {
        Modifier.blur(radius = 24.dp)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(blurModifier)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassHighlight,  // subtle highlight on top edge
                        fillColor,
                    )
                ),
                shape = shape,
            )
            .border(
                width = GlassTileDefaults.BorderWidth,
                color = GlassBorder,
                shape = shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
