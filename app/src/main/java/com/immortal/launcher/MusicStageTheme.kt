/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.ui.theme.ApprovedPalette
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Type/control sizes for the current Portal; provided by the stage. */
internal val LocalMusicStageDims = staticCompositionLocalOf { MusicStageDims.LARGE }

/** The stage's colours (the mock's tokens) for one song palette and the app's light/dark theme. */
internal data class MusicStageInk(
    val dark: Boolean,
    val base: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val fill: Color,
    val fill2: Color,
    val selected: Color,
    val track: Color,
    val card: Color,
    val inverse: Color,
    val ember: Color,
    val sheet: Color,
    val blobs: List<Color>,
    val veilTop: Color,
    val veilBottom: Color,
)

internal object MusicStageColors {
  private val LightBase = Color(0xFFF3F0EC)
  private val LightCard = Color(0xFFFBFAF8)
  private val LightSheet = Color(0xEDFAF8F5)
  private val LightInk = Color(0xFF141216)
  private val LightEmberText = Color(0xFFB4561C)
  private val DarkBase = Color(0xFF060508)
  private val DarkCard = Color(0xFF1C1A20)
  private val DarkSheet = Color(0xEB16141A)
  private val LightBlobShare = listOf(0.60f, 0.55f, 0.55f, 0.55f)
  private val DarkBlobShare = listOf(0.80f, 0.72f, 0.62f, 0.70f)

  /** Mixes follow the mock's `color-mix(in oklab, …)`; Compose's [lerp] blends in Oklab too. */
  fun of(palette: MusicArtPalette, dark: Boolean): MusicStageInk {
    val c = palette.colors.map { Color(it) }
    val c1 = c.first()
    return if (dark) {
      val ink = Color.White
      MusicStageInk(dark = true, base = lerp(DarkBase, c1, 0.34f), ink = ink, muted = ink.copy(alpha = 0.64f),
          faint = ink.copy(alpha = 0.36f), line = ink.copy(alpha = 0.14f), fill = ink.copy(alpha = 0.08f),
          fill2 = ink.copy(alpha = 0.08f), selected = ink.copy(alpha = 0.20f), track = ink.copy(alpha = 0.22f),
          card = lerp(DarkCard, c1, 0.22f), inverse = LightInk, ember = HearthpaneMark.EmberLight,
          sheet = lerp(DarkSheet, c1.copy(alpha = DarkSheet.alpha), 0.22f),
          blobs = c.mapIndexed { i, col -> lerp(Color.Black, col, DarkBlobShare[i]) },
          veilTop = Color.Black.copy(alpha = 0.16f), veilBottom = Color.Black.copy(alpha = 0.46f))
    } else {
      val ink = LightInk
      MusicStageInk(dark = false, base = lerp(LightBase, c1, 0.16f), ink = ink, muted = ink.copy(alpha = 0.60f),
          faint = ink.copy(alpha = 0.34f), line = ink.copy(alpha = 0.12f), fill = Color.White.copy(alpha = 0.52f),
          fill2 = ink.copy(alpha = 0.06f), selected = Color.White, track = ink.copy(alpha = 0.16f),
          card = lerp(LightCard, c1, 0.06f), inverse = LightCard, ember = LightEmberText,
          sheet = lerp(LightSheet, c1.copy(alpha = LightSheet.alpha), 0.08f),
          blobs = c.mapIndexed { i, col -> lerp(Color.White, col, LightBlobShare[i]) },
          veilTop = Color.White.copy(alpha = 0.30f), veilBottom = Color.White.copy(alpha = 0.50f))
    }
  }

  /** The launcher palette re-pointed at the stage's colours, so shared music widgets match. */
  fun approved(ink: MusicStageInk, app: ApprovedPalette): ApprovedPalette = app.copy(
      background = ink.base, sidebar = ink.base, surface = ink.card, surface2 = ink.fill2, text = ink.ink,
      secondary = ink.muted, divider = ink.line, accent = ink.ember, selected = ink.selected, outline = ink.line)
}

/** The stage's colours, for parts that draw with them directly (backdrop, sheet, transport). */
internal val LocalMusicStageInk = staticCompositionLocalOf { MusicStageColors.of(MusicArtPalette.IDLE, dark = true) }

/**
 * Provides [ink] (changes once per song, never per frame) and [dims] to the stage. Shared widgets
 * read [LocalApprovedPalette], so it is re-pointed at the stage colours too.
 */
@Composable
internal fun MusicStageTheme(ink: MusicStageInk, dims: MusicStageDims, content: @Composable () -> Unit) {
  val app = LocalApprovedPalette.current
  val approved = remember(ink, app) { MusicStageColors.approved(ink, app) }
  CompositionLocalProvider(LocalMusicStageInk provides ink, LocalMusicStageDims provides dims,
      LocalApprovedPalette provides approved, content = content)
}

/** True when the system's animator scale is off (Reduce motion): springs snap, the wash holds still. */
@Composable
internal fun rememberReducedMotion(): Boolean {
  val context = LocalContext.current
  return remember(context) {
    runCatching {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
  }
}
