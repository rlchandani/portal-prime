/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.ImageView

/** A thin edge follows the existing whole-photo view without consuming matte space. */
internal class NativePhotoView(context: Context) : ImageView(context) {
  var showBorder = false
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }
  private val density = resources.displayMetrics.density.let {
    if (it.isFinite() && it > 0f) it.coerceAtMost(4f) else 1f
  }
  private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(96, 222, 216, 203)
    style = Paint.Style.STROKE
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val scale = scaleX
    if (!showBorder || !scale.isFinite() || scale <= 0f ||
        width * scale < 24 * density || height * scale < 24 * density) return
    // The view is in source-bitmap pixels; inverse scaling keeps the edge one dp wide.
    edge.strokeWidth = (density / scale).coerceAtMost(minOf(width, height) / 8f)
    val inset = edge.strokeWidth / 2f
    canvas.drawRect(inset, inset, width - inset, height - inset, edge)
  }

  override fun setScaleX(scaleX: Float) {
    super.setScaleX(scaleX)
    // Measured Gallery reservations update retained views, so redraw their edge at the new scale.
    if (showBorder) invalidate()
  }
}
