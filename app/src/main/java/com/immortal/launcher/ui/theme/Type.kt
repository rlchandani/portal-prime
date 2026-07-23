/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.immortal.launcher.R

private val InterFontFamily = FontFamily(
    Font(R.font.inter,        weight = FontWeight.Normal),
    Font(R.font.inter_medium, weight = FontWeight.Medium),
    Font(R.font.inter_bold,   weight = FontWeight.Bold),
)

val Typography = Typography(
    // ── Display — used for clock faces and ambient large numbers ──────────────
    displayLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 57.sp,
        lineHeight  = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 45.sp,
        lineHeight  = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 36.sp,
        lineHeight  = 44.sp,
        letterSpacing = 0.sp,
    ),
    // ── Headline — section headers, tile labels ───────────────────────────────
    headlineLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 32.sp,
        lineHeight  = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 28.sp,
        lineHeight  = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 24.sp,
        lineHeight  = 32.sp,
    ),
    // ── Title — cards, sheet headers ─────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 22.sp,
        lineHeight  = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 18.sp,
        lineHeight  = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
    ),
    // ── Body ──────────────────────────────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 18.sp,
        lineHeight  = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 16.sp,
        lineHeight  = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
    ),
    // ── Label ─────────────────────────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 16.sp,
        lineHeight  = 24.sp,
    ),
    labelMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 12.sp,
        lineHeight  = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
