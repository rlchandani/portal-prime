/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.GlassCard
import com.immortal.launcher.ui.components.Glass
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.components.pollingState
import kotlinx.coroutines.flow.drop

@Composable
private fun FadingDivider() {
  Box(
      modifier = Modifier
          .fillMaxHeight()
          .padding(horizontal = 10.dp),
      contentAlignment = Alignment.Center
  ) {
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.30f),
                        Color.Transparent
                    )
                )
            )
    )
  }
}

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

/** Liquid Glass widget tile (rounded 22dp). */
@Composable
internal fun LiquidGlassWidget(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
  LiquidGlass(modifier = modifier, cornerRadius = 22.dp, content = content)
}

/** App icon in the grid with scale animation on tap and reorderable drag handle. */
@Composable
private fun LiquidGlassAppIconContent(
    app: AppEntry,
    editMode: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
  val scale = remember { Animatable(1f) }

  Box(
      modifier = Modifier
          .fillMaxWidth()
          .jiggle(editMode && !isDragging, app.component.packageName.hashCode()),
      contentAlignment = Alignment.Center
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
              scaleX = if (isDragging) 1.05f else scale.value
              scaleY = if (isDragging) 1.05f else scale.value
              shadowElevation = if (isDragging) 16.dp.toPx() else 0f
              shape = RoundedCornerShape(26.dp)
              clip = false
              // Disable Offscreen compositing while dragging for perf
              compositingStrategy = if (isDragging) CompositingStrategy.Auto else CompositingStrategy.Offscreen
            }
            .then(dragHandleModifier)
            .then(
                if (!editMode) {
                  // Non-edit mode: standard tap gestures with press-scale
                  Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                          scale.animateTo(0.96f, tween(90))
                          tryAwaitRelease()
                          scale.animateTo(1f, spring())
                        },
                        onTap = { onClick() },
                        onLongPress = { onLongPress() },
                    )
                  }
                } else {
                  // In edit mode the drag handle owns the gesture; a plain tap does nothing.
                  Modifier
                }
            ),
    ) {
      // Pad the wrapper so the minus badge sits at the tile's top-left corner and is FULLY
      // visible (not clipped by the grid cell). The tile is inset by the overhang amount.
      Box(modifier = Modifier.padding(top = 11.dp, start = 11.dp)) {
        // iOS-style icon: rounded-squircle corners, soft drop shadow (3D depth), and a glossy
        // top highlight sheen — applied uniformly so even square drawables read as premium
        // rounded tiles.
        val iconShape = RoundedCornerShape(26.dp)
        Box(
            modifier = Modifier
                .size(110.dp)
                .shadow(elevation = 8.dp, shape = iconShape, clip = false)
                .clip(iconShape),
            contentAlignment = Alignment.Center,
        ) {
          Image(
              bitmap = app.icon,
              contentDescription = app.label,
              modifier = Modifier.size(110.dp),
              filterQuality = FilterQuality.High,
          )
          // Glossy top-down sheen for the "candy"/3D finish.
          Box(
              modifier = Modifier.matchParentSize().background(
                  Brush.verticalGradient(
                      colors = listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF), Color.Transparent),
                      endY = 130f,
                  )
              )
          )
        }
        if (editMode && app.uninstallable) {
          // Badge sits at the tile's top-left corner, fully inside the padded wrapper (so it
          // isn't clipped) — straddling the edge visually. Offset into the padding room.
          MinusBadge(
              modifier = Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp),
          ) { onRemove() }
        }
      }
      Spacer(Modifier.height(6.dp))
      Text(
          text = app.label,
          color = Color.White.copy(alpha = 0.82f),
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = 110.dp),
      )
    }
  }
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
