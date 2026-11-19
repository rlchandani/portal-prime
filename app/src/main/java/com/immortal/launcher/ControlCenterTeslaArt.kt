/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

/** How the art shows the cable: none, plugged in (still), or charging (energy flowing into the port). */
internal enum class CcCableLook { NONE, PLUGGED, CHARGING }

/**
 * The car as a Model Y profile (vector, no photos) in its HA paint, with the Wall Connector and,
 * while charging, energy flowing along the cable. Paths and brushes are built once per size, theme
 * and paint (drawWithCache); only the flow layer reads the animation clock, and it exists only
 * while charging with motion allowed — a parked or dimmed screen draws no frames at all.
 */
@Composable
internal fun CcCarArt(paint: CcPaint, cable: CcCableLook, charger: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val look = remember(c, paint) { CcArtColors.of(c, paint) }
  Box(modifier) {
    Spacer(Modifier.matchParentSize().drawWithCache {
      val g = CcArtGeometry(size)
      val car = CcModelYPaths(g, look)
      val wire = cablePath(g)
      onDrawBehind {
        drawGround(g, look)
        if (charger) drawCharger(g, look, cable == CcCableLook.CHARGING)
        car.draw(this)
        if (cable != CcCableLook.NONE) drawCable(g, wire, look, cable == CcCableLook.CHARGING)
      }
    })
    if (cable == CcCableLook.CHARGING && animate) Flow(look, Modifier.matchParentSize())
  }
}

@Composable
private fun Flow(look: CcArtColors, modifier: Modifier) {
  val phase = rememberInfiniteTransition(label = "charge").animateFloat(0f, 1f,
      infiniteRepeatable(tween(FLOW_MS, easing = LinearEasing), RepeatMode.Restart), label = "flow")
  Spacer(modifier.graphicsLayer { }.drawWithCache {
    val g = CcArtGeometry(size)
    val measure = PathMeasure().apply { setPath(cablePath(g), false) }
    onDrawBehind { drawFlow(g, measure, phase, look) }
  })
}

/** A soft contact shadow under the car and each tyre, so it sits on the card instead of floating. */
private fun DrawScope.drawGround(g: CcArtGeometry, k: CcArtColors) {
  val s = g.scale
  drawOval(k.shadow.copy(alpha = k.shadow.alpha * SOFT), g.o(40f, 336f), Size(960f * s, 36f * s))
  drawOval(k.shadow, g.o(110f, 344f), Size(820f * s, 18f * s))
  listOf(WHEEL_FRONT, WHEEL_REAR).forEach { x -> drawOval(k.shadow, g.o(x - 84f, 352f), Size(168f * s, 14f * s)) }
}

/** Gen 3 Wall Connector: a tall rounded glass slab with its light strip (green while delivering). */
private fun DrawScope.drawCharger(g: CcArtGeometry, k: CcArtColors, charging: Boolean) {
  val s = g.scale
  drawRoundRect(k.pillar, g.o(1040f, 104f), Size(58f * s, 160f * s), CornerRadius(22f * s))
  drawRoundRect(k.edge, g.o(1040f, 104f), Size(58f * s, 160f * s), CornerRadius(22f * s), style = Stroke(2f * s))
  drawLine(if (charging) k.flow else k.rim, g.o(1069f, 136f), g.o(1069f, 176f), 5f * s, StrokeCap.Round)
}

private fun DrawScope.drawCable(g: CcArtGeometry, wire: androidx.compose.ui.graphics.Path, k: CcArtColors, charging: Boolean) {
  val s = g.scale
  drawPath(wire, k.cable, style = Stroke(9f * s, cap = StrokeCap.Round))
  drawRoundRect(if (charging) k.flow else k.cable, g.o(PORT_X - 12f, PORT_Y - 8f), Size(24f * s, 16f * s), CornerRadius(6f * s))
}

private fun DrawScope.drawFlow(g: CcArtGeometry, measure: PathMeasure, phase: State<Float>, k: CcArtColors) {
  val t = phase.value
  val len = measure.length
  for (i in 0 until FLOW_DOTS) {
    val f = (t + i.toFloat() / FLOW_DOTS) % 1f
    drawCircle(k.flow.copy(alpha = DOT_ALPHA * (1f - f * FADE)), DOT_R * g.scale, measure.getPosition(f * len))
  }
  drawCircle(k.flow.copy(alpha = GLOW_ALPHA * (1f - t)), (GLOW_R + GLOW_GROW * t) * g.scale, g.o(PORT_X, PORT_Y))
}

/** From the Wall Connector's handle, down along the floor and up into the charge port (rear left). */
private fun cablePath(g: CcArtGeometry) = androidx.compose.ui.graphics.Path().apply {
  moveTo(g.x(1069f), g.y(240f))
  cubicTo(g.x(1072f), g.y(356f), g.x(946f), g.y(356f), g.x(PORT_X), g.y(PORT_Y + 6f))
}

private const val SOFT = 0.45f
private const val FLOW_MS = 1600
private const val FLOW_DOTS = 5
private const val DOT_R = 7f
private const val DOT_ALPHA = 0.95f
private const val FADE = 0.4f
private const val GLOW_R = 14f
private const val GLOW_GROW = 22f
private const val GLOW_ALPHA = 0.45f
internal const val PORT_X = 930f
internal const val PORT_Y = 182f
internal const val WHEEL_FRONT = 255f
internal const val WHEEL_REAR = 791f

/** Maps the art's 1120×380 design units into a size, centred and aspect-fit. */
internal class CcArtGeometry(size: Size) {
  val scale = minOf(size.width / ART_W, size.height / ART_H)
  private val dx = (size.width - ART_W * scale) / 2
  private val dy = (size.height - ART_H * scale) / 2
  fun x(v: Float) = dx + v * scale
  fun y(v: Float) = dy + v * scale
  fun o(x: Float, y: Float) = Offset(x(x), y(y))
  fun rect(l: Float, t: Float, r: Float, b: Float) = androidx.compose.ui.geometry.Rect(x(l), y(t), x(r), y(b))

  private companion object {
    const val ART_W = 1120f
    const val ART_H = 380f
  }
}

@androidx.compose.runtime.Immutable
internal data class CcArtColors(
    val bodyTop: Color, val bodyBottom: Color, val glass: Color, val edge: Color, val tyre: Color, val rim: Color, val rimFace: Color,
    val trim: Color, val shadow: Color, val tail: Color, val head: Color, val pillar: Color, val cable: Color, val flow: Color,
) {
  companion object {
    fun of(c: CcColors, p: CcPaint) = CcArtColors(
        bodyTop = Color(OPAQUE or p.top), bodyBottom = Color(OPAQUE or p.bottom),
        glass = if (c.dark) Color(0xFF0A0C10) else Color(0xFF1E2731), edge = Color(0x40FFFFFF),
        tyre = Color(0xFF0B0C0E), rim = Color(0xFFB9BFC7), rimFace = Color(0xFF1B1D21), trim = Color(0xFF16181B),
        shadow = if (c.dark) Color(0x8C000000) else Color(0x40182C39), tail = Color(0xFFE5404C), head = Color(0xFFEAF2FF),
        pillar = c.surface2, cable = if (c.dark) Color(0xFF5A606A) else Color(0xFF8797A5), flow = c.green)

    private const val OPAQUE = 0xFF shl 24
  }
}
