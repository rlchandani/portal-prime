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
    val error: Color = Color(0xFFD33D4C),
    /** Slim trim around cards and round chrome buttons so they hold their edge on the page. */
    val outline: Color = divider,
    /** Track of a switch that is off: on light cards it must stay visible behind the white thumb. */
    val switchOffTrack: Color = surface2,
    /**
     * Selected pill of a segmented control (the raised neutral tile on its track). Reconciled with
     * Control Center's `CcColors.segOn` so one shared control draws the same pill everywhere:
     * dark uses a grey a step lighter than [surface2]; light is a plain white tile.
     */
    val segOn: Color = surface,
    /** Label/content colour on top of an [accent]-filled surface (white on blue; dark ink on the dark theme's white accent). */
    val accentInk: Color = Color.White,
    /** The 1 dp hairline the dark theme draws around a raised tile instead of a shadow. */
    val cardEdge: Color = outline)

val ApprovedLightPalette = ApprovedPalette(
    Color(0xFFF2F3F7), Color(0xFFE9EDF3), Color.White, Color(0xFFE5EBF2),
    Color(0xFF182C39), Color(0xFF536674), Color(0xFFDCE3EB), Color(0xFF176ABB),
    Color(0xFFD7E7F8), Color(0xFF288B61), false, outline = Color(0xFFC9D2DC), switchOffTrack = Color(0xFF8593A1),
    segOn = Color.White, accentInk = Color.White, cardEdge = Color(0x0A182C39))
val ApprovedDarkPalette = ApprovedPalette(
    Color(0xFF14161B), Color(0xFF14161B), Color(0xFF2B2B2B), Color(0xFF363636),
    Color.White, Color(0xFFB8B8B8), Color(0xFF484848), Color.White,
    Color(0xFF414141), Color(0xFF66C49E), true, Color(0xFFFF747D), outline = Color(0xFF555860),
    segOn = Color(0xFF4A4D54), accentInk = Color(0xFF14161B), cardEdge = Color(0x0AFFFFFF))
val LocalApprovedPalette = staticCompositionLocalOf { ApprovedLightPalette }
val LocalAdvancedSettings = compositionLocalOf { false }
