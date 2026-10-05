/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

data class ApprovedPalette(
    val background: Color, val sidebar: Color, val surface: Color, val surface2: Color,
    val text: Color, val secondary: Color, val divider: Color, val accent: Color,
    val selected: Color, val green: Color, val dark: Boolean,
    val error: Color = Color(0xFFD33D4C))

val ApprovedLightPalette = ApprovedPalette(
    Color(0xFFF2F3F7), Color(0xFFE9EDF3), Color.White, Color(0xFFE5EBF2),
    Color(0xFF182C39), Color(0xFF536674), Color(0xFFDCE3EB), Color(0xFF176ABB),
    Color(0xFFD7E7F8), Color(0xFF288B61), false)
val ApprovedDarkPalette = ApprovedPalette(
    Color(0xFF14262D), Color(0xFF162B34), Color(0xFF213840), Color(0xFF2B4650),
    Color(0xFFF4F7F9), Color(0xFFB8C9D0), Color(0xFF38505B), Color(0xFF9ACAFF),
    Color(0xFF34556F), Color(0xFF66C49E), true, Color(0xFFFF747D))
val LocalApprovedPalette = staticCompositionLocalOf { ApprovedLightPalette }
val LocalAdvancedSettings = compositionLocalOf { false }
