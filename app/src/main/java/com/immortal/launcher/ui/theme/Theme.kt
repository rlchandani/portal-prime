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
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ImmortalSettings

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
    outline              = ContentTertiary,
    outlineVariant       = GlassBorder,
    error                = Error,
    onError              = ContentOnAccent,
)

@Composable
fun PortalPrimeTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var appearance by remember(context) { mutableStateOf(ImmortalSettings.settingsTheme(context)) }
    var advanced by remember(context) { mutableStateOf(ImmortalSettings.advancedSettings(context)) }
    DisposableEffect(context) {
        val stopObserving = ImmortalSettings.observePresentation(context) {
            appearance = ImmortalSettings.settingsTheme(context)
            advanced = ImmortalSettings.advancedSettings(context)
        }
        onDispose { stopObserving() }
    }
    // Home/ambient surfaces stay dark. Settings and editors follow the persisted appearance.
    val useDark = darkTheme || appearance == "dark"
    val palette = if (useDark) ApprovedDarkPalette else ApprovedLightPalette
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        useDark -> darkColorScheme(
            primary = palette.accent, onPrimary = palette.background,
            primaryContainer = palette.selected, onPrimaryContainer = palette.text,
            secondary = palette.secondary, background = palette.background,
            onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surface2, onSurfaceVariant = palette.secondary,
            surfaceContainer = palette.surface, outline = palette.secondary,
            outlineVariant = palette.divider, error = palette.error)
        else -> lightColorScheme(
            primary = palette.accent, onPrimary = palette.surface,
            primaryContainer = palette.selected, onPrimaryContainer = palette.text,
            secondary = palette.secondary, background = palette.background,
            onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surface2, onSurfaceVariant = palette.secondary,
            surfaceContainer = palette.surface, outline = palette.secondary,
            outlineVariant = palette.divider, error = palette.error)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
    ) {
        CompositionLocalProvider(
            LocalApprovedPalette provides palette,
            LocalAdvancedSettings provides advanced,
            LocalTextStyle provides LocalTextStyle.current.copy(
                fontFamily = InterFontFamily, letterSpacing = 0.sp),
            content = content,
        )
    }
}
