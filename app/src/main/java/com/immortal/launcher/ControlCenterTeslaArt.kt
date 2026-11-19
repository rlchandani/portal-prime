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
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

/** How the art shows the cable: none, plugged in (still), or charging (energy flowing into the port). */
internal enum class CcCableLook { NONE, PLUGGED, CHARGING }

/**
 * The car in profile (vector, no photos) with the Wall Connector and, while charging, energy
 * flowing along the cable. The car and cable are built once per size and theme (drawWithCache);
 * only the flow layer reads the animation clock, and it exists only while charging with motion
 * allowed — so a parked or dimmed screen draws no frames at all.
 */
@Composable
internal fun CcCarArt(cable: CcCableLook, charger: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val look = remember(c) { ArtColors.of(c) }
  Box(modifier) {
    Spacer(Modifier.matchParentSize().drawWithCache {
      val g = Geometry(size)
      val paths = carPaths(g)
      val cablePath = cablePath(g)
      onDrawBehind { drawStatic(g, paths, cablePath, look, cable, charger) }
    })
    if (cable == CcCableLook.CHARGING && animate) Flow(look, Modifier.matchParentSize())
  }
}

@Composable
private fun Flow(look: ArtColors, modifier: Modifier) {
  val phase = rememberInfiniteTransition(label = "charge").animateFloat(0f, 1f,
      infiniteRepeatable(tween(FLOW_MS, easing = LinearEasing), RepeatMode.Restart), label = "flow")
  Spacer(modifier.graphicsLayer { }.drawWithCache {
    val g = Geometry(size)
    val measure = PathMeasure().apply { setPath(cablePath(g), false) }
    onDrawBehind { drawFlow(g, measure, phase, look) }
  })
}

@androidx.compose.runtime.Immutable
private data class ArtColors(val bodyTop: Color, val bodyBottom: Color, val glass: Color, val edge: Color, val tyre: Color, val rim: Color,
                             val shadow: Color, val tail: Color, val head: Color, val pillar: Color, val cable: Color, val flow: Color) {
  companion object {
    fun of(c: CcColors) = if (c.dark) ArtColors(Color(0xFF4A4F58), Color(0xFF1F2227), Color(0xFF0C0E11), Color(0x2EFFFFFF), Color(0xFF0B0C0E),
        Color(0xFF6B717B), Color(0x66000000), Color(0xFFE0404C), Color(0xFFE8F1FF), c.surface2, Color(0xFF5A606A), c.green)
    else ArtColors(Color(0xFFE6EBF0), Color(0xFFB4BEC9), Color(0xFF2A3440), Color(0x99FFFFFF), Color(0xFF1C2228),
        Color(0xFF9AA5B1), Color(0x33182C39), Color(0xFFD33D4C), Color(0xFFFFFFFF), c.surface2, Color(0xFF8797A5), c.green)
  }
}

/** Maps the art's 1120×380 design units into [size], centred and aspect-fit. */
private class Geometry(size: Size) {
  val scale = minOf(size.width / ART_W, size.height / ART_H)
  private val dx = (size.width - ART_W * scale) / 2
  private val dy = (size.height - ART_H * scale) / 2
  fun x(v: Float) = dx + v * scale
  fun y(v: Float) = dy + v * scale
  fun o(x: Float, y: Float) = Offset(x(x), y(y))
  fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(x(l), y(t), x(r), y(b))
}

private class CarPaths(val body: Path, val glass: Path, val highlight: Path, val seams: Path)

private fun CacheDrawScope.carPaths(g: Geometry): CarPaths {
  val body = Path().apply {
    moveTo(g.x(60f), g.y(292f))
    cubicTo(g.x(36f), g.y(290f), g.x(28f), g.y(262f), g.x(34f), g.y(240f))
    cubicTo(g.x(40f), g.y(222f), g.x(60f), g.y(210f), g.x(96f), g.y(204f))
    lineTo(g.x(300f), g.y(178f))
    cubicTo(g.x(350f), g.y(150f), g.x(420f), g.y(112f), g.x(480f), g.y(100f))
    cubicTo(g.x(560f), g.y(86f), g.x(680f), g.y(88f), g.x(740f), g.y(100f))
    cubicTo(g.x(820f), g.y(116f), g.x(890f), g.y(150f), g.x(930f), g.y(176f))
    cubicTo(g.x(955f), g.y(190f), g.x(968f), g.y(205f), g.x(968f), g.y(228f))
    lineTo(g.x(966f), g.y(270f))
    cubicTo(g.x(964f), g.y(288f), g.x(950f), g.y(296f), g.x(930f), g.y(296f))
    lineTo(g.x(874f), g.y(296f))
    arcTo(g.rect(706f, 212f, 874f, 380f), 0f, -180f, false)
    lineTo(g.x(334f), g.y(296f))
    arcTo(g.rect(166f, 212f, 334f, 380f), 0f, -180f, false)
    close()
  }
  val glass = Path().apply {
    moveTo(g.x(326f), g.y(182f))
    cubicTo(g.x(380f), g.y(150f), g.x(432f), g.y(122f), g.x(488f), g.y(112f))
    cubicTo(g.x(560f), g.y(100f), g.x(670f), g.y(100f), g.x(728f), g.y(110f))
    cubicTo(g.x(790f), g.y(122f), g.x(848f), g.y(150f), g.x(884f), g.y(176f))
    close()
  }
  val highlight = Path().apply {
    moveTo(g.x(110f), g.y(206f)); lineTo(g.x(300f), g.y(184f))
    moveTo(g.x(330f), g.y(190f)); cubicTo(g.x(520f), g.y(186f), g.x(760f), g.y(184f), g.x(944f), g.y(194f))
  }
  val seams = Path().apply {
    moveTo(g.x(338f), g.y(192f)); lineTo(g.x(346f), g.y(286f))
    moveTo(g.x(604f), g.y(186f)); lineTo(g.x(604f), g.y(296f))
    moveTo(g.x(612f), g.y(104f)); lineTo(g.x(606f), g.y(182f))
  }
  return CarPaths(body, glass, highlight, seams)
}

/** From the Wall Connector's handle, down along the floor and up into the charge port. */
private fun cablePath(g: Geometry): Path = Path().apply {
  moveTo(g.x(1066f), g.y(236f))
  cubicTo(g.x(1070f), g.y(352f), g.x(948f), g.y(352f), g.x(936f), g.y(212f))
}

private fun DrawScope.drawStatic(g: Geometry, p: CarPaths, cable: Path, k: ArtColors, look: CcCableLook, charger: Boolean) {
  val s = g.scale
  drawOval(k.shadow, g.o(70f, 350f), Size(900f * s, 26f * s))
  if (charger) drawRoundRect(k.pillar, g.o(1040f, 120f), Size(56f * s, 150f * s), CornerRadius(18f * s))
  if (charger) drawCircle(if (look == CcCableLook.CHARGING) k.flow else k.rim, 5f * s, g.o(1068f, 150f))
  drawPath(p.body, Brush.verticalGradient(listOf(k.bodyTop, k.bodyBottom), g.y(90f), g.y(300f)))
  drawPath(p.glass, k.glass)
  drawPath(p.highlight, k.edge, style = Stroke(3f * s, cap = StrokeCap.Round))
  drawPath(p.seams, k.shadow, style = Stroke(2f * s))
  drawLine(k.head, g.o(42f, 230f), g.o(112f, 214f), 5f * s, StrokeCap.Round)
  drawLine(k.tail, g.o(902f, 186f), g.o(962f, 206f), 6f * s, StrokeCap.Round)
  wheel(g, 250f, k); wheel(g, 790f, k)
  if (look != CcCableLook.NONE) {
    drawPath(cable, k.cable, style = Stroke(9f * s, cap = StrokeCap.Round))
    drawRoundRect(if (look == CcCableLook.CHARGING) k.flow else k.cable, g.o(924f, 200f), Size(24f * s, 18f * s), CornerRadius(6f * s))
  }
}

private fun DrawScope.wheel(g: Geometry, cx: Float, k: ArtColors) {
  val s = g.scale
  val at = g.o(cx, 296f)
  drawCircle(k.tyre, 70f * s, at)
  drawCircle(k.rim, 46f * s, at)
  drawCircle(k.tyre, 38f * s, at)
  drawCircle(k.rim, 30f * s, at, style = Stroke(4f * s))
  drawCircle(k.rim, 9f * s, at)
}

private fun DrawScope.drawFlow(g: Geometry, measure: PathMeasure, phase: State<Float>, k: ArtColors) {
  val t = phase.value
  val len = measure.length
  for (i in 0 until FLOW_DOTS) {
    val f = (t + i.toFloat() / FLOW_DOTS) % 1f
    drawCircle(k.flow.copy(alpha = DOT_ALPHA * (1f - f * FADE)), DOT_R * g.scale, measure.getPosition(f * len))
  }
  drawCircle(k.flow.copy(alpha = GLOW_ALPHA * (1f - t)), (GLOW_R + GLOW_GROW * t) * g.scale, g.o(936f, 209f))
}

private const val ART_W = 1120f
private const val ART_H = 380f
private const val FLOW_MS = 1600
private const val FLOW_DOTS = 5
private const val DOT_R = 7f
private const val DOT_ALPHA = 0.95f
private const val FADE = 0.4f
private const val GLOW_R = 14f
private const val GLOW_GROW = 22f
private const val GLOW_ALPHA = 0.45f
