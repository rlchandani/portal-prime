/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The control id Jarvis is pointing at on this screen (`hearthpane.highlight`), or null. */
internal val LocalJarvisPulse = compositionLocalOf<String?> { null }

private val Ember = Color(0xFFF0A65A)
private const val PULSE_MS = 800

/**
 * The mock's highlight ring: a steady ember outline with a soft glow breathing outward around the control Jarvis
 * points at. Only the pointed-at control runs the animation; every other call is a no-op modifier.
 */
@Composable
internal fun Modifier.jarvisPulse(active: Boolean, corner: Dp = 20.dp): Modifier {
  if (!active) return this
  val pulse = rememberInfiniteTransition(label = "jarvis-pulse").animateFloat(
      initialValue = 0f, targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse), label = "jarvis-pulse")
  return drawWithContent {
    drawContent()
    val radius = CornerRadius(corner.toPx())
    val ring = 3.dp.toPx()
    val glow = 10.dp.toPx() * pulse.value
    drawRoundRect(Ember.copy(alpha = 0.35f * (1f - pulse.value)), Offset(-glow, -glow),
        Size(size.width + glow * 2, size.height + glow * 2), CornerRadius(radius.x + glow), style = Stroke(ring + glow))
    drawRoundRect(Ember, Offset(ring / 2, ring / 2), Size(size.width - ring, size.height - ring), radius, style = Stroke(ring))
  }
}

/**
 * The target Jarvis is pointing at on [screen] (`hearthpane.highlight`), for [JarvisHighlight.SHOW_MS], else null.
 * Requests for other screens are left to them. A newer request replaces the one showing.
 */
@Composable
internal fun rememberJarvisTarget(screen: String): String? {
  var active by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(screen) {
    JarvisHighlight.current.collectLatest { request ->
      val target = request?.target?.takeIf { HearthpaneTools.screenOf(it) == screen } ?: return@collectLatest
      active = target
      try {
        delay(JarvisHighlight.SHOW_MS)
        JarvisHighlight.done(request)
      } finally {
        if (active == target) active = null
      }
    }
  }
  return active
}
