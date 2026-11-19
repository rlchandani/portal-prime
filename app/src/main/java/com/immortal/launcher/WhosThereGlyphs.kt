/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The mock's line icons that the shared icon set lacks, drawn on a 24-unit grid (1.9 stroke). */
internal enum class WtGlyph { EYE, EYE_OFF, USERS, FACE, SHIELD, CHEVRON_DOWN, TRASH, CAMERA, CAMERA_OFF, MOON, BULB, LOCK, CHECK, PAUSE }

@Composable
internal fun WhosThereGlyph(glyph: WtGlyph, tint: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
  Canvas(modifier.size(size)) {
    val u = this.size.minDimension / 24f
    val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
      moveTo(pts[0] * u, pts[1] * u)
      var i = 2
      while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
      if (close) close()
    }
    fun circle(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, Offset(cx * u, cy * u), style = stroke)
    fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) =
        drawArc(tint, start, sweep, false, Offset(l * u, t * u), Size((r - l) * u, (b - t) * u), style = stroke)
    fun line(vararg pts: Float) = drawPath(p(*pts), tint, style = stroke)
    when (glyph) {
      WtGlyph.EYE -> { eye(u, tint, stroke); circle(12f, 12f, 3f) }
      WtGlyph.EYE_OFF -> { eye(u, tint, stroke); line(3f, 3f, 21f, 21f) }
      WtGlyph.USERS -> {
        circle(9f, 8f, 3.5f); arc(2.5f, 14f, 15.5f, 27f, 180f, 180f)
        arc(14.5f, 4.6f, 20.5f, 11.4f, 270f, 180f); line(17f, 14.5f, 21.5f, 20.5f)
      }
      WtGlyph.FACE -> {
        drawPath(p(4f, 8f, 4f, 4f, 8f, 4f), tint, style = stroke); drawPath(p(16f, 4f, 20f, 4f, 20f, 8f), tint, style = stroke)
        drawPath(p(4f, 16f, 4f, 20f, 8f, 20f), tint, style = stroke); drawPath(p(16f, 20f, 20f, 20f, 20f, 16f), tint, style = stroke)
        line(9f, 9.5f, 9f, 10.5f); line(15f, 9.5f, 15f, 10.5f); arc(9f, 11f, 15f, 16f, 20f, 140f)
      }
      WtGlyph.SHIELD -> { drawPath(p(12f, 3f, 20f, 6f, 19f, 13f, 12f, 21f, 5f, 13f, 4f, 6f, close = true), tint, style = stroke); line(8.5f, 12f, 11f, 14.5f, 15.5f, 9.5f) }
      WtGlyph.CHEVRON_DOWN -> line(6f, 9f, 12f, 15f, 18f, 9f)
      WtGlyph.TRASH -> { line(4f, 7f, 20f, 7f); drawPath(p(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f), tint, style = stroke); line(9f, 7f, 9.5f, 4f, 14.5f, 4f, 15f, 7f) }
      WtGlyph.CAMERA -> { drawRoundRect(tint, Offset(3f * u, 6f * u), Size(13f * u, 12f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke); line(16f, 10f, 21f, 7f, 21f, 17f, 16f, 14f) }
      WtGlyph.CAMERA_OFF -> { drawRoundRect(tint, Offset(3f * u, 6f * u), Size(13f * u, 12f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke); line(2f, 3f, 21f, 21f) }
      WtGlyph.MOON -> drawPath(Path().apply {
        moveTo(20f * u, 14.5f * u); cubicTo(17f * u, 16f * u, 9f * u, 15f * u, 9f * u, 7f * u)
        cubicTo(9f * u, 5.5f * u, 9.5f * u, 4.5f * u, 10f * u, 4f * u); cubicTo(5f * u, 5f * u, 3f * u, 10f * u, 4.5f * u, 14.5f * u)
        cubicTo(6.5f * u, 20f * u, 16f * u, 22f * u, 20f * u, 14.5f * u)
      }, tint, style = stroke)
      WtGlyph.BULB -> { arc(6f, 3f, 18f, 15f, 135f, 270f); line(9.5f, 14f, 9.5f, 17f, 14.5f, 17f, 14.5f, 14f); line(10f, 20.5f, 14f, 20.5f) }
      WtGlyph.LOCK -> { drawRoundRect(tint, Offset(5f * u, 11f * u), Size(14f * u, 10f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke); arc(8f, 4f, 16f, 14f, 180f, 180f); line(8f, 9f, 8f, 11f); line(16f, 9f, 16f, 11f) }
      WtGlyph.CHECK -> line(5f, 12.5f, 10f, 17.5f, 19f, 7f)
      WtGlyph.PAUSE -> { line(9f, 5f, 9f, 19f); line(15f, 5f, 15f, 19f) }
    }
  }
}

private fun DrawScope.eye(u: Float, tint: Color, stroke: Stroke) {
  drawPath(Path().apply {
    moveTo(2f * u, 12f * u)
    cubicTo(5f * u, 6f * u, 19f * u, 6f * u, 22f * u, 12f * u)
    cubicTo(19f * u, 18f * u, 5f * u, 18f * u, 2f * u, 12f * u)
    close()
  }, tint, style = stroke)
}
