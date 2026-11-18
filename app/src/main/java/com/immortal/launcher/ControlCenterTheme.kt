/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Control Center tokens, copied from the approved mock's CSS (`.app[data-theme=…]`): the refined
 * Home palette plus the plan's floor/wall/slab colours. Units in the mock are dp at 1920×1280;
 * [CcTheme] scales the whole screen to 75% on 1280×800-class Portals exactly like the mock's
 * compact layout, so every size in the screens is written as in the spec.
 */
internal data class CcColors(
    val dark: Boolean,
    val bg: Color, val rail: Color, val surface: Color, val surface2: Color,
    val text: Color, val sec: Color, val faint: Color, val div: Color,
    val accent: Color, val accentInk: Color, val sel: Color,
    val green: Color, val red: Color, val amber: Color,
    val amberBg: Color, val redBg: Color, val greenBg: Color,
    val floor: Color, val floor2: Color, val wall: Color, val wallFace: Color,
    val slab: Color, val slab2: Color, val planBg: Color, val dotOff: Color, val ink: Color,
    /** Flat translucent panel (no live blur on the Portal GPU). */
    val glass: Color, val scrim: Color,
    /** Segmented-control selected pill (dark uses a lighter grey than surface). */
    val segOn: Color,
    /** The 1 dp card outline the dark theme uses instead of a shadow. */
    val cardEdge: Color,
)

internal val CcDark = CcColors(
    dark = true,
    bg = Color(0xFF14161B), rail = Color(0xFF101216), surface = Color(0xFF24262B), surface2 = Color(0xFF31343A),
    text = Color.White, sec = Color(0xFFB8B8B8), faint = Color(0xFF7D8189), div = Color(0xFF3A3D44),
    accent = Color.White, accentInk = Color(0xFF14161B), sel = Color(0xFF414141),
    green = Color(0xFF66C49E), red = Color(0xFFFF747D), amber = Color(0xFFF0A65A),
    amberBg = Color(0xFF3B2A15), redBg = Color(0xFF43191E), greenBg = Color(0xFF173328),
    floor = Color(0xFF22252B), floor2 = Color(0xFF1E2126), wall = Color(0xFF5A606A), wallFace = Color(0xFF2E3238),
    slab = Color(0xFF191B1F), slab2 = Color(0xFF111316), planBg = Color(0xFF16181C), dotOff = Color(0xFF4A4F57), ink = Color.White,
    glass = Color(0xD1202227), scrim = Color(0x80000000), segOn = Color(0xFF4A4D54), cardEdge = Color(0x0AFFFFFF),
)

internal val CcLight = CcColors(
    dark = false,
    bg = Color(0xFFF2F3F7), rail = Color(0xFFE9EDF3), surface = Color.White, surface2 = Color(0xFFE5EBF2),
    text = Color(0xFF182C39), sec = Color(0xFF536674), faint = Color(0xFF8797A5), div = Color(0xFFDCE3EB),
    accent = Color(0xFF176ABB), accentInk = Color.White, sel = Color(0xFFD7E7F8),
    green = Color(0xFF288B61), red = Color(0xFFD33D4C), amber = Color(0xFFB9741A),
    amberBg = Color(0xFFFBEBD3), redBg = Color(0xFFFBE1E4), greenBg = Color(0xFFDDF1E7),
    floor = Color.White, floor2 = Color(0xFFF7F9FC), wall = Color(0xFF9AABBC), wallFace = Color(0xFFC9D4DF),
    slab = Color(0xFFB7C4D1), slab2 = Color(0xFFA5B3C2), planBg = Color(0xFFE6EBF1), dotOff = Color(0xFFC2CDD8), ink = Color(0xFF182C39),
    glass = Color(0xC7FFFFFF), scrim = Color(0x6114202C), segOn = Color.White, cardEdge = Color(0x0A182C39),
)

internal val LocalCc = staticCompositionLocalOf { CcDark }

/** Shared shapes, type and brand colours from the mock (`--r`, `--rs`, `.serif`, `.jbtn`). */
internal object CcStyle {
  val Ember = Color(0xFFD9773A)
  val EmberLight = Color(0xFFF0A65A)
  val EmberInk = Color(0xFF1B120B)
  val JarvisBrush = Brush.linearGradient(listOf(EmberLight, Ember))
  val Card = RoundedCornerShape(28.dp)
  val CardSmall = RoundedCornerShape(20.dp)
  val Tile = RoundedCornerShape(26.dp)
  val Sheet = RoundedCornerShape(40.dp)
  val Modal = RoundedCornerShape(44.dp)
  val Pill = RoundedCornerShape(50)
  val Serif: FontFamily = FontFamily.Serif
  val Sans: FontFamily = FontFamily.SansSerif
  val Mono: FontFamily = FontFamily.Monospace
  /** Rail, header and side-column sizes (`--railW`, `--hdrH`, `--sideW`). */
  val RailWidth = 132.dp
  val HeaderHeight = 108.dp
  val SideWidth = 540.dp
  /** Room sheet width (`.sheet{width:860px}`) and modal width (`.modal{width:820px}`). */
  val SheetWidth = 860.dp
  val ModalWidth = 820.dp
  /** Every touch target is at least this big (Hearthpane quality bar). */
  val MinTarget = 64.dp
  /** Mock design canvas width; compact Portals render it at [COMPACT_SCALE]. */
  const val DESIGN_WIDTH_DP = 1920
  const val COMPACT_BREAK_DP = 1700
  const val COMPACT_SCALE = 0.75f
  /** Person avatar colours, cycled by index (mock `.avs i`). */
  val Avatars = listOf(Color(0xFF8AB4F8), Color(0xFF7FD1AE), Color(0xFFF4A3C8), Color(0xFFF2C46D), Color(0xFFB9A5F4))
}

/** Applies the palette and, on 1280×800-class Portals, the mock's 75% compact scale. */
@Composable
internal fun CcTheme(dark: Boolean, content: @Composable () -> Unit) {
  val density = LocalDensity.current
  val widthDp = LocalConfiguration.current.screenWidthDp
  val scaled = if (widthDp in 1 until CcStyle.COMPACT_BREAK_DP)
    Density(density.density * CcStyle.COMPACT_SCALE, density.fontScale) else density
  CompositionLocalProvider(LocalCc provides if (dark) CcDark else CcLight, LocalDensity provides scaled, content = content)
}
