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
internal enum class MusicV3Glyph {
  PLAY, PAUSE, PREVIOUS, NEXT, SPEAKER, GRIP, MORE, DOWN, RIGHT, CHECK, SHUFFLE, EQ,
  REPEAT, UP, LIST, GRID, MOVE, FOLLOW, SPEAKER_LOW, HUSH,
}

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
      MusicV3Glyph.REPEAT -> repeat(color, u)
      MusicV3Glyph.UP -> { line(color, u, 6f, 14.5f, 12f, 8.5f); line(color, u, 12f, 8.5f, 18f, 14.5f) }
      MusicV3Glyph.LIST -> listOf(6.5f, 12f, 17.5f).forEach { y ->
        drawCircle(color, 1.1f * u, Offset(4.5f * u, y * u)); line(color, u, 9f, y, 20f, y)
      }
      MusicV3Glyph.GRID -> listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f).forEach { (x, y) ->
        drawRoundRect(color, Offset(x * u, y * u), Size(7f * u, 7f * u), androidx.compose.ui.geometry.CornerRadius(1.6f * u),
            style = Stroke(1.9f * u))
      }
      MusicV3Glyph.MOVE -> {
        drawRoundRect(color, Offset(3f * u, 5f * u), Size(13f * u, 9.5f * u), androidx.compose.ui.geometry.CornerRadius(2f * u),
            style = Stroke(1.9f * u))
        line(color, u, 7f, 19f, 12f, 19f); line(color, u, 18.5f, 9.5f, 21f, 9.5f)
        line(color, u, 21f, 9.5f, 21f, 18.5f); line(color, u, 21f, 18.5f, 14.5f, 18.5f)
      }
      MusicV3Glyph.FOLLOW -> {
        drawArc(color, 180f, 90f, false, Offset(4f * u, 7f * u), Size(16f * u, 16f * u), style = Stroke(1.9f * u, cap = StrokeCap.Round))
        drawArc(color, 180f, 90f, false, Offset(4f * u, 7f * u), Size(24f * u, 24f * u), style = Stroke(1.9f * u, cap = StrokeCap.Round))
        drawCircle(color, 2.5f * u, Offset(18f * u, 17f * u))
      }
      MusicV3Glyph.SPEAKER_LOW -> {
        speakerBody(color, u)
        drawArc(color, -50f, 100f, false, Offset(10f * u, 7f * u), Size(7f * u, 10f * u), style = Stroke(2f * u, cap = StrokeCap.Round))
      }
      MusicV3Glyph.HUSH -> { speakerBody(color, u); line(color, u, 15.5f, 9.5f, 20.5f, 14.5f); line(color, u, 20.5f, 9.5f, 15.5f, 14.5f) }
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

private fun DrawScope.speakerBody(c: Color, u: Float) {
  val body = Path().apply {
    moveTo(3f * u, 9f * u); lineTo(7f * u, 9f * u); lineTo(12f * u, 5f * u)
    lineTo(12f * u, 19f * u); lineTo(7f * u, 15f * u); lineTo(3f * u, 15f * u); close()
  }
  drawPath(body, c)
}

private fun DrawScope.repeat(c: Color, u: Float) {
  line(c, u, 4f, 11.5f, 4f, 10f); line(c, u, 7.5f, 6.5f, 19f, 6.5f)
  drawArc(c, 180f, 90f, false, Offset(4f * u, 6.5f * u), Size(7f * u, 7f * u), style = Stroke(1.9f * u, cap = StrokeCap.Round))
  line(c, u, 16.5f, 4f, 19f, 6.5f); line(c, u, 19f, 6.5f, 16.5f, 9f)
  line(c, u, 20f, 12.5f, 20f, 14f); line(c, u, 16.5f, 17.5f, 5f, 17.5f)
  drawArc(c, 0f, 90f, false, Offset(13f * u, 10.5f * u), Size(7f * u, 7f * u), style = Stroke(1.9f * u, cap = StrokeCap.Round))
  line(c, u, 7.5f, 20f, 5f, 17.5f); line(c, u, 5f, 17.5f, 7.5f, 15f)
}

private fun DrawScope.speaker(c: Color, u: Float) {
  speakerBody(c, u)
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
