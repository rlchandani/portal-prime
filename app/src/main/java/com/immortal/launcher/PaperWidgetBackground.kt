/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/** Paint-only segmentation preserves the bucket's measured geometry and padding. */
internal class PaperWidgetBackground(private val radius: Float) : Drawable() {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rect = RectF()
  private var widgets = emptyList<Pair<View, Int>>()
  private var snapshot = emptyList<PaperWidgetContrast.Band>()
  private var opacity = 255

  fun update(next: List<Pair<View, Int>>) {
    val nextSnapshot = PaperWidgetContrast.bands(bounds.height(), next.map { (view, color) ->
      PaperWidgetContrast.Widget(view.top, view.bottom, color)
    })
    if (next == widgets && nextSnapshot == snapshot) return
    widgets = next
    snapshot = nextSnapshot
    // Layout can move children while the palette stays unchanged. Invalidating a drawable
    // redraws without requesting another layout, so the geometry observer can settle.
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    val bands = PaperWidgetContrast.bands(bounds.height(), widgets.map { (view, color) ->
      PaperWidgetContrast.Widget(view.top, view.bottom, color)
    })
    bands.forEach { band ->
      paint.color = band.color
      paint.alpha = (band.color ushr 24) * opacity / 255
      rect.set(bounds.left.toFloat(), (bounds.top + band.top).toFloat(),
          bounds.right.toFloat(), (bounds.top + band.bottom).toFloat())
      canvas.drawRoundRect(rect, radius, radius, paint)
    }
  }

  override fun setAlpha(alpha: Int) {
    opacity = alpha.coerceIn(0, 255)
    invalidateSelf()
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    paint.colorFilter = colorFilter
    invalidateSelf()
  }

  @Deprecated("Deprecated in Android")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
