/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Music v3 glyphs drawn on a 24-unit grid (mirrors the mock's SVGs; decorative only). */
internal enum class MusicV3Glyph { PLAY, PAUSE, PREVIOUS, NEXT, SPEAKER, GRIP, MORE, DOWN, RIGHT, CHECK, SHUFFLE, EQ }

@Composable
internal fun MusicV3GlyphIcon(glyph: MusicV3Glyph, color: Color, modifier: Modifier) {
  Canvas(modifier) {
    val u = size.minDimension / 24f
    when (glyph) {
      MusicV3Glyph.PLAY -> fill(color, u, 8f to 5f, 19f to 12f, 8f to 19f)
      MusicV3Glyph.PAUSE -> {
        fill(color, u, 7f to 5f, 10.5f to 5f, 10.5f to 19f, 7f to 19f)
        fill(color, u, 13.5f to 5f, 17f to 5f, 17f to 19f, 13.5f to 19f)
      }
      MusicV3Glyph.PREVIOUS -> {
        fill(color, u, 6f to 5f, 8f to 5f, 8f to 19f, 6f to 19f)
        fill(color, u, 19f to 5f, 19f to 19f, 9f to 12f)
      }
      MusicV3Glyph.NEXT -> {
        fill(color, u, 16f to 5f, 18f to 5f, 18f to 19f, 16f to 19f)
        fill(color, u, 5f to 5f, 15f to 12f, 5f to 19f)
      }
      MusicV3Glyph.SPEAKER -> speaker(color, u)
      MusicV3Glyph.GRIP -> listOf(6f, 12f, 18f).forEach { y ->
        drawCircle(color, 1.7f * u, Offset(9f * u, y * u)); drawCircle(color, 1.7f * u, Offset(15f * u, y * u))
      }
      MusicV3Glyph.MORE -> listOf(5f, 12f, 19f).forEach { y -> drawCircle(color, 1.9f * u, Offset(12f * u, y * u)) }
      MusicV3Glyph.DOWN -> { line(color, u, 6f, 9f, 12f, 15f); line(color, u, 12f, 15f, 18f, 9f) }
      MusicV3Glyph.RIGHT -> { line(color, u, 9f, 6f, 15f, 12f); line(color, u, 15f, 12f, 9f, 18f) }
      MusicV3Glyph.CHECK -> { line(color, u, 5f, 12.5f, 10f, 17.5f); line(color, u, 10f, 17.5f, 19f, 7f) }
      MusicV3Glyph.SHUFFLE -> shuffle(color, u)
      MusicV3Glyph.EQ -> listOf(5f to 9f, 11f to 4f, 17f to 12f).forEach { (x, top) ->
        drawRoundRect(color, Offset(x * u, top * u), Size(3.5f * u, (20f - top) * u),
            androidx.compose.ui.geometry.CornerRadius(1.5f * u))
      }
    }
  }
}

private fun DrawScope.line(c: Color, u: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 2f * u, StrokeCap.Round)

private fun DrawScope.fill(c: Color, u: Float, vararg pts: Pair<Float, Float>) {
  val path = Path().apply {
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
    close()
  }
  drawPath(path, c)
}

private fun DrawScope.speaker(c: Color, u: Float) {
  val body = Path().apply {
    moveTo(3f * u, 9f * u); lineTo(7f * u, 9f * u); lineTo(12f * u, 5f * u)
    lineTo(12f * u, 19f * u); lineTo(7f * u, 15f * u); lineTo(3f * u, 15f * u); close()
  }
  drawPath(body, c)
  drawArc(c, -50f, 100f, false, Offset(10f * u, 7f * u), Size(7f * u, 10f * u),
      style = Stroke(2f * u, cap = StrokeCap.Round))
  drawArc(c, -55f, 110f, false, Offset(10f * u, 3.5f * u), Size(11f * u, 17f * u),
      style = Stroke(2f * u, cap = StrokeCap.Round))
}

private fun DrawScope.shuffle(c: Color, u: Float) {
  line(c, u, 3f, 7f, 7f, 7f); line(c, u, 7f, 7f, 14f, 17f); line(c, u, 14f, 17f, 19f, 17f)
  line(c, u, 3f, 17f, 7f, 17f); line(c, u, 7f, 17f, 9.5f, 13.5f)
  line(c, u, 13f, 7f, 19f, 7f); line(c, u, 16.5f, 4.5f, 19f, 7f); line(c, u, 16.5f, 9.5f, 19f, 7f)
  line(c, u, 16.5f, 14.5f, 19f, 17f); line(c, u, 16.5f, 19.5f, 19f, 17f)
}
