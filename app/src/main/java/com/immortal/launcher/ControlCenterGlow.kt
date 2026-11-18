/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Pre-rendered paint for the plan, built once per screen: ONE white radial-gradient bitmap that
 * every light glow (and the scene-walk light) draws tinted with its colour — no live blur on
 * the Portal GPU — plus the icon painters (vector caches) and per-colour tint filters.
 */
@Stable
internal class CcPlanPaint(val glow: ImageBitmap, private val icons: Map<String, VectorPainter>) {
  private val tints = HashMap<Long, ColorFilter>()

  fun icon(name: String): VectorPainter? = icons[name]

  /** A cached SrcIn tint (white glow → [color]); allocates only the first time a colour is seen. */
  fun tint(color: Color): ColorFilter = tints.getOrPut(color.value.toLong()) { ColorFilter.tint(color, BlendMode.SrcIn) }

  fun tint(rgb: Int): ColorFilter = tint(Color(OPAQUE or rgb))

  companion object {
    /** Glow bitmap edge in px: a soft gradient scales up smoothly, so 256 is plenty. */
    const val GLOW_PX = 256
    private const val OPAQUE = 0xFF000000.toInt()
    private const val CORE_ALPHA = 0.95f
    private const val MID_ALPHA = 0.45f
    private const val MID_STOP = 0.45f
    /** Every glyph the canvas draws (painters must be created in a stable order). */
    val ICONS = listOf("lock", "unlock", "cam", "heat", "leak", "washer", "dryer", "dish", "oven", "sun", "battery", "grid", "home")

    fun buildGlow(px: Int = GLOW_PX): ImageBitmap {
      val bmp = ImageBitmap(px, px)
      val c = px / 2f
      val paint = Paint().apply {
        shader = RadialGradientShader(Offset(c, c), c,
            listOf(Color.White.copy(alpha = CORE_ALPHA), Color.White.copy(alpha = MID_ALPHA), Color.White.copy(alpha = 0f)),
            listOf(0f, MID_STOP, 1f))
      }
      Canvas(bmp).drawCircle(Offset(c, c), c, paint)
      return bmp
    }
  }
}

@Composable
internal fun rememberCcPlanPaint(): CcPlanPaint {
  val glow = remember { CcPlanPaint.buildGlow() }
  val painters = CcPlanPaint.ICONS.map { rememberVectorPainter(CcIcons[it]) }
  return remember(glow) { CcPlanPaint(glow, CcPlanPaint.ICONS.zip(painters).toMap()) }
}

/** Draws the shared glow centred at [center] with [radius] px, tinted by [filter]. */
internal fun DrawScope.ccGlow(paint: CcPlanPaint, center: Offset, radius: Float, alpha: Float, filter: ColorFilter) {
  val d = (radius * 2f).toInt().coerceAtLeast(1)
  drawImage(paint.glow, srcOffset = IntOffset.Zero, srcSize = IntSize(paint.glow.width, paint.glow.height),
      dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()), dstSize = IntSize(d, d),
      alpha = alpha.coerceIn(0f, 1f), colorFilter = filter)
}

/** Draws icon [name] centred at [center], [sizePx] square, tinted by [filter]. */
internal fun DrawScope.ccIcon(paint: CcPlanPaint, name: String, center: Offset, sizePx: Float, filter: ColorFilter, alpha: Float = 1f) {
  val p = paint.icon(name) ?: return
  translate(center.x - sizePx / 2f, center.y - sizePx / 2f) {
    with(p) { draw(Size(sizePx, sizePx), alpha = alpha, colorFilter = filter) }
  }
}
