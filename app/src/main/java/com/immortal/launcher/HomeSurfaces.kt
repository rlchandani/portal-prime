/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.immortal.launcher.ui.components.GlassCard
import com.immortal.launcher.ui.components.Glass
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.components.pollingState
import kotlinx.coroutines.flow.drop

// ─────────────────────────────────────────────────────────────────────────────
// Liquid Glass composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bare Liquid Glass surface — transparent glass, FLAT (no gradient): a single even translucent
 * DARK tint so the card reads a touch darker than the wallpaper (gives it body and makes the white
 * text pop), while still letting the wallpaper show through. A thin light border defines the glass
 * edge. No drop shadow (it bleeds through the translucent fill and muddies the centre), no gradient.
 */
@Composable
internal fun LiquidGlass(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Glass.Radius,
    content: @Composable BoxScope.() -> Unit = {},
) {
  // Thin alias over the ONE shared glass surface (ui/components/Glass.kt) — colors/radius live
  // there so every card in the app changes together.
  GlassCard(
      modifier = modifier, cornerRadius = cornerRadius, content = content)
}

/** A single day wash keeps chrome readable over every selected wallpaper; night is unchanged. */
@Composable
internal fun ApprovedHomeBackground() {
  val context = androidx.compose.ui.platform.LocalContext.current
  var wallpaper by remember { mutableStateOf(WallpaperConfig.load(context)) }
  OnResume { wallpaper = WallpaperConfig.load(context) }
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  Box(Modifier.fillMaxSize()) {
    if (wallpaper.mode == WallpaperConfig.SKY) {
      Box(Modifier.fillMaxSize()) {
        ApprovedHomeSky()
        ApprovedHomeBackgroundEffects(wallpaper.grain)
      }
    } else if (wallpaper.mode != WallpaperConfig.DUSK_AURORA) {
      HomeBackground(Modifier.fillMaxSize())
    } else {
      // Frozen revision04 has a static teal-to-ember background, with no idle animation.
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
          0f to Color(0xFF11212F), .7f to Color(0xFF213B42), 1f to Color(0xFF69453D)))) {
        Canvas(Modifier.fillMaxSize()) {
          drawRect(Brush.radialGradient(listOf(Color(0x5539646B), Color.Transparent),
              center = Offset(size.width * .3f, size.height * .6f), radius = size.width * .55f))
        }
        ApprovedHomeBackgroundEffects(wallpaper.grain)
      }
    }
    if (!palette.dark) Box(Modifier.matchParentSize().background(palette.background.copy(alpha = .92f)))
  }
}

@Composable
private fun ApprovedHomeBackgroundEffects(grain: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  if (grain) {
    val noise = remember {
      val random = java.util.Random(4)
      val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
      val pixels = IntArray(128 * 128) {
        val value = random.nextInt(256)
        android.graphics.Color.argb(28, value, value, value)
      }
      bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 128)
      bitmap
    }
    Canvas(Modifier.fillMaxSize()) {
      drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
          shader = android.graphics.BitmapShader(noise, android.graphics.Shader.TileMode.REPEAT,
              android.graphics.Shader.TileMode.REPEAT)
        }
        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
      }
    }
  }
  val warmth = pollingState(refreshMs = 60_000, retryMs = 60_000) { DisplayControl.currentWarmAlpha(context) }
  if ((warmth.value ?: 0f) > 0f)
    Box(Modifier.fillMaxSize().background(Color(0xFFFFB46A).copy(alpha = warmth.value ?: 0f)))
}
