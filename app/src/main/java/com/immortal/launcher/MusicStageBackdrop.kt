/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay

/** One drifting colour blob of the wash, in shares of the wash box (the mock's `.blob.a…d`). */
private data class Blob(
    val left: Float, val top: Float, val dx: Float, val dy: Float, val scale: Float, val periodS: Float,
)

private val BLOBS = listOf(
    Blob(-0.04f, -0.06f, 0.22f, 0.16f, 1.15f, 34f),
    Blob(0.44f, 0.04f, -0.24f, 0.20f, 0.90f, 41f),
    Blob(0.18f, 0.44f, 0.26f, -0.18f, 1.20f, 38f),
    Blob(0.34f, 0.40f, -0.18f, -0.22f, 1.05f, 46f),
)
private const val WASH_INSET = 0.18f
private const val BLOB_W = 0.62f
private const val BLOB_H = 0.68f
private const val SOFT_STOP = 0.5f
private const val SOFT_ALPHA = 0.55f
/** The wash drifts over tens of seconds; 20 updates a second is smooth and cheap on the Portal GPU. */
private const val DRIFT_FRAME_MS = 50L
private const val COLOR_FADE_MS = 1_200

/**
 * The artwork-coloured background: four soft radial blobs drifting slowly, then a veil. Colours
 * change once per song (a short fade); per frame only positions move, and only in the draw phase
 * of this one layer. Reduce motion holds them still.
 */
@Composable
internal fun MusicStageBackdrop(ink: MusicStageInk, reduced: Boolean, modifier: Modifier = Modifier) {
  val fade = tween<Color>(COLOR_FADE_MS, easing = FastOutSlowInEasing)
  val base = animateColorAsState(ink.base, fade, label = "wash-base")
  val blobs = ink.blobs.mapIndexed { i, c -> animateColorAsState(c, fade, label = "wash-$i") }
  val clock = remember { mutableFloatStateOf(0f) }
  if (!reduced) {
    LaunchedEffect(Unit) {
      val start = withFrameMillis { it }
      while (true) {
        delay(DRIFT_FRAME_MS)
        withFrameMillis { clock.floatValue = (it - start) / 1000f }
      }
    }
  }
  val veil = Brush.verticalGradient(listOf(ink.veilTop, ink.veilBottom))
  Spacer(modifier.fillMaxSize().graphicsLayer().drawBehind {
    drawRect(base.value)
    drawWash(blobs, clock.floatValue)
    drawRect(veil)
  })
}

private fun DrawScope.drawWash(colors: List<State<Color>>, seconds: Float) {
  val washX = -size.width * WASH_INSET
  val washY = -size.height * WASH_INSET
  val washW = size.width * (1 + 2 * WASH_INSET)
  val washH = size.height * (1 + 2 * WASH_INSET)
  val w = washW * BLOB_W
  val h = washH * BLOB_H
  BLOBS.forEachIndexed { i, b ->
    val t = pingPong(seconds, b.periodS)
    val cx = washX + washW * b.left + w / 2 + b.dx * w * t
    val cy = washY + washH * b.top + h / 2 + b.dy * h * t
    val radius = (w + h) / 4f * (1f + (b.scale - 1f) * t)
    val c = colors[i].value
    drawCircle(Brush.radialGradient(0f to c, SOFT_STOP to c.copy(alpha = c.alpha * SOFT_ALPHA), 1f to Color.Transparent,
        center = Offset(cx, cy), radius = radius), radius = radius, center = Offset(cx, cy))
  }
}

/** 0 → 1 → 0 over two [periodS], eased in and out (CSS `ease-in-out infinite alternate`). */
private fun pingPong(seconds: Float, periodS: Float): Float {
  val phase = (seconds / periodS) % 2f
  val linear = if (phase <= 1f) phase else 2f - phase
  return ((1 - cos(linear * PI)) / 2).toFloat()
}
