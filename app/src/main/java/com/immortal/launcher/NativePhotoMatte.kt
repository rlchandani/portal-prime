/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import kotlin.math.ceil

/** Static vector decoration below photos; each composition keeps its opaque matte. */
internal class NativePhotoMatte(matte: Int, density: Float) : Drawable() {
  private val scale = if (density.isFinite()) density.coerceIn(.5f, 4f) else 1f
  private val base = Paint().apply { color = matte }
  private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.rgb(222, 216, 203)
    alpha = 33
    style = Paint.Style.STROKE
    strokeWidth = 1.35f
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  override fun draw(canvas: Canvas) {
    if (bounds.isEmpty) return
    canvas.drawRect(bounds, base)
    val save = canvas.save()
    try {
      canvas.clipRect(bounds)
      canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
      canvas.scale(scale, scale)
      val columns = ceil(bounds.width() / (480f * scale)).toInt()
      val rows = ceil(bounds.height() / (360f * scale)).toInt()
      for (row in 0 until rows) for (column in 0 until columns) {
        val tile = canvas.save()
        canvas.translate(column * 480f, row * 360f)
        paths.forEach { canvas.drawPath(it, ink) }
        canvas.restoreToCount(tile)
      }
    } finally { canvas.restoreToCount(save) }
  }

  override fun setAlpha(alpha: Int) {
    base.alpha = alpha.coerceIn(0, 255)
    ink.alpha = (base.alpha * 33 + 127) / 255
    invalidateSelf()
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    base.colorFilter = colorFilter
    ink.colorFilter = colorFilter
    invalidateSelf()
  }

  @Deprecated("Deprecated in Java")
  override fun getOpacity(): Int =
      if (base.alpha == 255 && base.colorFilter == null) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

  companion object {
    // Same paths as screensaver/photo-doodles-dark.svg; circles expressed as paired arcs.
    private val paths = listOf(
        "M36 60c9-15 19-23 29-21-2 14-11 25-25 25m-3-2 18-14m-15 11-1-8m8 3 10 1",
        "m178 37 4 10 11 1-8 7 2 11-9-6-9 6 2-11-8-7 11-1z",
        "m310 66 14-17 12 13 9-9 17 24h-55m14-22 5 4 4-5",
        "M424 24c-3 13 4 23 16 24-14 9-31-3-26-18 2-5 6-8 10-9",
        "M84 176c-8-8-17-2-13 6l13 13 13-13c5-9-6-15-13-6z",
        "M220 154c11-9 19-2 14 8-4 8-15 15-8 23m-3 4 1 1",
        "m360 167 25-13-9 29-6-12-10-4 15-6m-5 11-6 5",
        "M433 233c5-9 13-9 16 0 7-1 12 5 8 10h-29c-7-4-4-12 5-10z",
        "M27 279c11 1 19 7 22 19-14 0-23-6-24-16m5 3 16 12m-9-8-1 7",
        "M142 275a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
        "m151 258 1-5m-1 40 1 5m-18-24-5-1m40 1 5-1m-35-11-4-4m29 30 4 3m-30 0-3 3m30-30 3-4",
        "M274 286h31v22h-31zm7 0 3-5h12l3 5",
        "M283 297a6 6 0 1 0 12 0a6 6 0 1 0 -12 0",
        "M390 320c4-5 8-4 11 1 3-5 8-5 11-1m-30-201c3-4 6-4 9 0 3-4 6-4 9 0",
        "m112 106 2 3m161-3 2-3m-36 221 2 2m-28-95 2-1m-66-87 2 1m207 155 2-2",
    ).map { PathParser.createPathFromPathData(it) }
  }
}
