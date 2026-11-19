/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Model Y in profile, facing left, in the art's 1120×380 units: crossover ride height, tall
 * roof peaking over the B-pillar, a sloped hatch with a short tail and spoiler lip, black arch and
 * sill cladding, and 19" twin-spoke wheels. Every path and brush is built once (in the art's
 * drawWithCache); [draw] only replays them.
 */
internal class CcModelYPaths(private val g: CcArtGeometry, private val k: CcArtColors) {
  private val s = g.scale

  private val body = Path().apply {
    moveTo(g.x(80f), g.y(288f))
    cubicTo(g.x(50f), g.y(286f), g.x(34f), g.y(264f), g.x(36f), g.y(240f))
    cubicTo(g.x(38f), g.y(216f), g.x(52f), g.y(202f), g.x(86f), g.y(194f))
    cubicTo(g.x(170f), g.y(176f), g.x(270f), g.y(162f), g.x(344f), g.y(152f))
    cubicTo(g.x(402f), g.y(120f), g.x(454f), g.y(94f), g.x(514f), g.y(82f))
    cubicTo(g.x(582f), g.y(70f), g.x(682f), g.y(70f), g.x(750f), g.y(82f))
    cubicTo(g.x(832f), g.y(98f), g.x(894f), g.y(128f), g.x(936f), g.y(152f))
    lineTo(g.x(950f), g.y(158f))
    cubicTo(g.x(962f), g.y(170f), g.x(968f), g.y(192f), g.x(968f), g.y(216f))
    lineTo(g.x(966f), g.y(262f))
    cubicTo(g.x(964f), g.y(280f), g.x(952f), g.y(290f), g.x(932f), g.y(290f))
    lineTo(g.x(882f), g.y(290f))
    arcTo(g.rect(WHEEL_REAR - ARCH, AXLE - ARCH, WHEEL_REAR + ARCH, AXLE + ARCH), 0f, -180f, false)
    lineTo(g.x(WHEEL_FRONT + ARCH), g.y(290f))
    arcTo(g.rect(WHEEL_FRONT - ARCH, AXLE - ARCH, WHEEL_FRONT + ARCH, AXLE + ARCH), 0f, -180f, false)
    close()
  }

  private val glass = Path().apply {
    moveTo(g.x(358f), g.y(160f))
    cubicTo(g.x(410f), g.y(130f), g.x(460f), g.y(102f), g.x(518f), g.y(92f))
    cubicTo(g.x(586f), g.y(82f), g.x(678f), g.y(82f), g.x(744f), g.y(94f))
    cubicTo(g.x(812f), g.y(108f), g.x(868f), g.y(132f), g.x(908f), g.y(154f))
    lineTo(g.x(358f), g.y(168f))
    close()
  }

  /** Lower cladding: wheel-arch trims and the sill between them. */
  private val cladding = Path().apply {
    arcTo(g.rect(WHEEL_FRONT - ARCH, AXLE - ARCH, WHEEL_FRONT + ARCH, AXLE + ARCH), -180f, 180f, true)
    arcTo(g.rect(WHEEL_REAR - ARCH, AXLE - ARCH, WHEEL_REAR + ARCH, AXLE + ARCH), -180f, 180f, true)
  }

  private val pillars = Path().apply {
    moveTo(g.x(620f), g.y(84f)); lineTo(g.x(634f), g.y(84f)); lineTo(g.x(630f), g.y(166f)); lineTo(g.x(616f), g.y(166f)); close()
    moveTo(g.x(812f), g.y(104f)); lineTo(g.x(822f), g.y(108f)); lineTo(g.x(808f), g.y(160f)); lineTo(g.x(798f), g.y(160f)); close()
  }

  private val shoulder = Path().apply {
    moveTo(g.x(96f), g.y(200f))
    cubicTo(g.x(300f), g.y(176f), g.x(620f), g.y(172f), g.x(946f), g.y(168f))
  }

  private val seams = Path().apply {
    moveTo(g.x(358f), g.y(176f)); lineTo(g.x(368f), g.y(276f))
    moveTo(g.x(624f), g.y(172f)); lineTo(g.x(624f), g.y(272f))
    moveTo(g.x(844f), g.y(160f)); cubicTo(g.x(846f), g.y(200f), g.x(856f), g.y(226f), g.x(872f), g.y(238f))
  }

  private val paintBrush = Brush.verticalGradient(listOf(k.bodyTop, k.bodyBottom), g.y(72f), g.y(292f))
  /** A diagonal sky reflection across the doors and a second, fainter one on the hatch. */
  private val reflection = Brush.linearGradient(
      listOf(Color.Transparent, Color.White.copy(alpha = REFLECT), Color.Transparent, Color.White.copy(alpha = REFLECT / 2), Color.Transparent),
      g.o(260f, 90f), g.o(860f, 300f))
  private val underShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = UNDER)), g.y(200f), g.y(292f))
  private val glassShine = Brush.linearGradient(listOf(Color.White.copy(alpha = GLASS_SHINE), Color.Transparent), g.o(470f, 84f), g.o(600f, 170f))

  fun draw(d: DrawScope) = with(d) {
    drawPath(body, paintBrush)
    drawPath(body, reflection)
    drawPath(body, underShade)
    drawRect(k.trim, g.o(WHEEL_FRONT + ARCH - 4f, 266f), Size((WHEEL_REAR - WHEEL_FRONT - 2 * ARCH + 8f) * s, 24f * s))
    drawPath(cladding, k.trim, style = Stroke(TRIM * s))
    drawPath(glass, k.glass)
    drawPath(glass, glassShine)
    drawPath(pillars, k.glass)
    drawPath(shoulder, k.edge, style = Stroke(2.5f * s, cap = StrokeCap.Round))
    drawPath(seams, Color.Black.copy(alpha = SEAM), style = Stroke(2f * s))
    drawOval(paintBrush, g.o(372f, 140f), Size(38f * s, 18f * s))
    drawLine(k.head, g.o(46f, 216f), g.o(124f, 198f), 5f * s, StrokeCap.Round)
    drawLine(k.tail.copy(alpha = TAIL_GLOW), g.o(900f, 166f), g.o(962f, 182f), 14f * s, StrokeCap.Round)
    drawLine(k.tail, g.o(900f, 166f), g.o(962f, 182f), 6f * s, StrokeCap.Round)
    wheel(WHEEL_FRONT); wheel(WHEEL_REAR)
  }

  /** 19" Gemini-style: tyre, dark face, five twin spokes, a bright lip with a top-left highlight. */
  private fun DrawScope.wheel(cx: Float) {
    val at = g.o(cx, AXLE)
    drawCircle(k.tyre, TYRE * s, at)
    drawCircle(Color.White.copy(alpha = SIDEWALL), (TYRE - 6f) * s, at, style = Stroke(2f * s))
    drawCircle(k.rimFace, RIM * s, at)
    for (i in 0 until SPOKES) for (side in listOf(-TWIN, TWIN)) {
      val a = Math.toRadians((i * SPOKE_STEP + SPOKE_OFFSET + side).toDouble())
      val dir = androidx.compose.ui.geometry.Offset(cos(a).toFloat(), sin(a).toFloat())
      drawLine(k.rim, at + dir * (HUB * s), at + dir * ((RIM - 4f) * s), 6f * s, StrokeCap.Round)
    }
    drawCircle(k.rim, RIM * s, at, style = Stroke(5f * s))
    drawArc(Color.White.copy(alpha = RIM_SHINE), RIM_SHINE_START, RIM_SHINE_SWEEP, false,
        at - androidx.compose.ui.geometry.Offset(RIM * s, RIM * s), Size(2 * RIM * s, 2 * RIM * s), style = Stroke(4f * s, cap = StrokeCap.Round))
    drawCircle(k.rim, HUB * s, at)
    drawCircle(k.rimFace, 5f * s, at)
  }

  private companion object {
    const val AXLE = 292f
    const val ARCH = 90f
    const val TYRE = 74f
    const val RIM = 54f
    const val HUB = 14f
    const val TRIM = 12f
    const val SPOKES = 5
    const val SPOKE_STEP = 72f
    const val SPOKE_OFFSET = -90f
    const val TWIN = 7f
    const val REFLECT = 0.16f
    const val UNDER = 0.35f
    const val GLASS_SHINE = 0.22f
    const val SEAM = 0.35f
    const val TAIL_GLOW = 0.3f
    const val SIDEWALL = 0.07f
    const val RIM_SHINE = 0.55f
    const val RIM_SHINE_START = 190f
    const val RIM_SHINE_SWEEP = 70f
  }
}
