/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.ApprovedPalette
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * The dashboard's colours: [ApprovedPalette] surfaces (light and dark, following Appearance)
 * plus Control Center's status colours (`amber/amberBg/redBg/greenBg`, [CcLight]/[CcDark]).
 */
internal data class HaColors(
    val dark: Boolean, val bg: Color, val surface: Color, val surface2: Color, val text: Color, val sec: Color,
    val outline: Color, val accent: Color, val accentInk: Color, val sel: Color, val switchOff: Color,
    val green: Color, val red: Color, val redBg: Color, val amber: Color, val amberBg: Color, val amberLine: Color,
) {
  val ember: Color get() = HearthpaneMark.Ember

  companion object {
    private val AMBER_LINE_DARK = Color(0xFF7A5326)
    private val AMBER_LINE_LIGHT = Color(0xFFE7C48F)

    fun of(p: ApprovedPalette): HaColors {
      val cc = if (p.dark) CcDark else CcLight
      return HaColors(dark = p.dark, bg = p.background, surface = p.surface, surface2 = p.surface2, text = p.text,
          sec = p.secondary, outline = p.outline, accent = p.accent, accentInk = cc.accentInk, sel = p.selected,
          switchOff = p.switchOffTrack, green = p.green, red = cc.red, redBg = cc.redBg, amber = cc.amber,
          amberBg = cc.amberBg, amberLine = if (p.dark) AMBER_LINE_DARK else AMBER_LINE_LIGHT)
    }
  }
}

internal val LocalHaColors = staticCompositionLocalOf { HaColors.of(com.immortal.launcher.ui.theme.ApprovedDarkPalette) }
internal val LocalHaDims = staticCompositionLocalOf { HaDashboardDims.LARGE }

@Composable
internal fun HaDashboardTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalHaColors provides HaColors.of(LocalApprovedPalette.current),
      LocalHaDims provides HaDashboardDims.of(currentLayoutClass()), content = content)
}

/** Shared shapes and stroke (spec: tiles 26, rooms 22, scenes 20, 1.5 dp outline everywhere). */
internal object HaShape {
  val Tile = RoundedCornerShape(26.dp)
  val Room = RoundedCornerShape(22.dp)
  val Scene = RoundedCornerShape(20.dp)
  val Well = RoundedCornerShape(18.dp)
  val SmallWell = RoundedCornerShape(16.dp)
  val Pill = RoundedCornerShape(50)
  val Outline = 1.5.dp
}

/** [this] colour at [alpha] laid over [base], like the mock's `color-mix(in srgb, c x%, base)`. */
internal fun Color.mix(alpha: Float, base: Color): Color = copy(alpha = alpha).compositeOver(base)

/** One line of dashboard text. Serif for display type, Inter (theme default) otherwise. */
@Composable
internal fun HaText(text: String, size: Int, color: Color, modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal, serif: Boolean = false, lines: Int = 1, lineHeight: Float = 1.25f,
    align: TextAlign? = null, spacing: Float = 0f) {
  Text(text, modifier, color = color, fontSize = size.sp, lineHeight = (size * lineHeight).sp,
      fontWeight = if (serif) FontWeight.Medium else weight, fontFamily = if (serif) FontFamily.Serif else null,
      maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align,
      letterSpacing = if (serif) (-0.01f * size).sp else spacing.sp)
}

/** Section label: small caps, wide tracking ("ROOMS", "SCENES", "HOME"). */
@Composable
internal fun HaLabel(text: String, modifier: Modifier = Modifier) {
  val d = LocalHaDims.current
  HaText(text.uppercase(), d.label, LocalHaColors.current.sec, modifier, FontWeight.SemiBold, spacing = d.label * 0.08f)
}

/** A surface with the 1.5 dp outline. */
internal fun Modifier.haCard(c: HaColors, shape: Shape, fill: Color = c.surface, line: Color = c.outline): Modifier =
    clip(shape).background(fill, shape).border(HaShape.Outline, line, shape)

/** Round outlined button at the device's target size (72 Portal+, 64 Mini). */
@Composable
internal fun HaRoundButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    iconSize: Dp = 30.dp, fill: Color? = null, ink: Color? = null) {
  val c = LocalHaColors.current
  Box(modifier.size(LocalHaDims.current.target.dp).haCard(c, CircleShape, fill ?: c.surface, fill ?: c.outline)
      .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
      contentAlignment = Alignment.Center) {
    HaIcon(icon, ink ?: c.text, iconSize)
  }
}

/** Outlined (or [primary] filled) pill button with a leading glyph. */
@Composable
internal fun HaPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null,
    primary: Boolean = false, height: Dp? = null) {
  val c = LocalHaColors.current
  val d = LocalHaDims.current
  val fill = if (primary) c.accent else c.surface
  val ink = if (primary) c.accentInk else c.text
  Row(modifier.height(height ?: d.target.dp).haCard(c, HaShape.Pill, fill, if (primary) c.accent else c.outline)
      .clickable(role = Role.Button, onClickLabel = text, onClick = onClick).padding(horizontal = 24.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    icon?.let { HaIcon(it, ink, 24.dp) }
    HaText(text, d.button, ink, weight = FontWeight.SemiBold)
  }
}

/** Status dot with its soft halo (green live, amber connecting/offline, red sign-in). */
@Composable
internal fun HaDot(color: Color) {
  Box(Modifier.size(24.dp).drawBehind {
    drawCircle(color.copy(alpha = HALO_ALPHA), radius = size.minDimension / 2)
    drawCircle(color, radius = size.minDimension / 4)
  })
}

private const val HALO_ALPHA = 0.22f

/** The Hearthpane ember glyph at the pill's 18×22 size. */
@Composable
internal fun HaBrandGlyph() {
  Box(Modifier.size(width = 18.dp, height = 22.dp)
      .background(Brush.verticalGradient(listOf(HearthpaneMark.EmberLight, HearthpaneMark.Ember)), HearthpaneMark.GlyphShape))
}
