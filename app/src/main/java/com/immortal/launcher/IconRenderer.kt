/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Renders app icons with consistent squircle masking. Composites adaptive icon
 * layers (API 26+) and applies a uniform rounded-corner mask, eliminating the
 * transparent-corner inconsistency when circular app icons are shown in rounded tiles.
 */
object IconRenderer {

  /**
   * Renders an app icon into a bitmap with a consistent squircle mask.
   *
   * For adaptive icons (API 26+), composites the background and foreground layers.
   * For legacy drawables, scales them to fill the canvas. Then applies a squircle
   * mask (rounded-corner path) via DST_IN blending.
   *
   * @param context Android context
   * @param drawable The app's icon drawable (may be AdaptiveIconDrawable or legacy)
   * @param sizePx Output bitmap size in pixels
   * @return Masked bitmap with transparent corners outside the squircle
   */
  fun renderAppIcon(context: Context, drawable: Drawable, sizePx: Int = 144): Bitmap {
    if (sizePx <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    // Create output bitmap
    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    // Draw icon content (adaptive or legacy)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
      // Composite adaptive icon layers
      drawable.setBounds(0, 0, sizePx, sizePx)
      drawable.background?.draw(canvas)
      drawable.foreground?.draw(canvas)
    } else {
      // Scale legacy drawable to fill canvas
      drawable.setBounds(0, 0, sizePx, sizePx)
      drawable.draw(canvas)
    }

    // Apply squircle mask via DST_IN (destination-in: keeps only content inside mask)
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    val squirclePath = createSquirclePath(sizePx)
    canvas.drawPath(squirclePath, maskPaint)

    return output
  }

  /**
   * Creates a squircle (super-ellipse / rounded-corner) path for masking.
   * Corner radius is ~22% of the size to match the UI's RoundedCornerShape.
   */
  private fun createSquirclePath(sizePx: Int): Path {
    val cornerRadius = sizePx * 0.22f
    val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
    return Path().apply {
      addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
    }
  }
}
