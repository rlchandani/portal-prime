/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val PrimeDarkColorScheme = darkColorScheme(
    primary              = PrimeBlue,
    onPrimary            = OnPrimeBlue,
    primaryContainer     = PrimeBlueDim,
    onPrimaryContainer   = PrimeBlueLight,
    secondary            = ContentSecondary,
    onSecondary          = ContentOnAccent,
    secondaryContainer   = SurfaceVariant,
    onSecondaryContainer = ContentPrimary,
    tertiary             = AccentGlow,
    onTertiary           = ContentOnAccent,
    background           = BackgroundDark,
    onBackground         = ContentPrimary,
    surface              = SurfaceDark,
    onSurface            = ContentPrimary,
    surfaceVariant       = SurfaceVariant,
    onSurfaceVariant     = ContentSecondary,
    surfaceContainer     = SurfaceContainer,
    outline              = GlassBorder,
    error                = Error,
    onError              = ContentOnAccent,
)

@Composable
fun PortalPrimeTheme(
    darkTheme: Boolean = true,  // Portal is always dark
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        else -> PrimeDarkColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
