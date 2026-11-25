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
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import kotlin.math.pow

/**
 * Composites app artwork inside a continuous superellipse silhouette.
 * Only small mask paths, never mutated after publication, are shared;
 * every render owns its output.
 */
object IconRenderer {
  // Four fixed-complexity paths, regardless of output dimensions; no mask bitmaps.
  // Lazy so the object can initialize without touching Android graphics — the pure pixel-coverage
  // helpers below are then reachable from plain JVM unit tests.
  private val masks by lazy { LruCache<Int, Path>(4) }
  private val clearPaint by lazy {
    Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
  }

  /**
   * Keeps adaptive background/foreground composition and legacy square scaling.
   * Antialiased inverse-path clearing removes all pixels outside the silhouette.
   *
   * @param context Android context
   * @param drawable The app's icon drawable (may be AdaptiveIconDrawable or legacy)
   * @param sizePx Output bitmap size in pixels
   * @return Independent bitmap with transparent corners outside the silhouette
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

    // ALPHA_8 drawBitmap can use coverage-mask semantics on software Canvas:
    // zero coverage skips destination pixels even with DST_IN. An inverse path
    // explicitly covers every exterior pixel, so CLEAR erases the corners and
    // antialiased boundary coverage preserves the corresponding source alpha.
    canvas.drawPath(outsideMask(sizePx), clearPaint)

    return output
  }

  /** Below this opaque fill a normalized icon is a bare glyph (no full-bleed tile of its own). */
  const val MIN_OPAQUE_FILL = 0.5f
  private const val OPAQUE_ALPHA = 128

  /**
   * Fraction of [pixels] that are substantially opaque (alpha ≥ [OPAQUE_ALPHA]). A full-bleed
   * squircle icon fills ~0.9 of its bitmap; a transparent/monochrome glyph (e.g. the Home
   * Assistant companion mark) fills only a fraction. Pure so it can be unit tested without a Bitmap.
   */
  fun opaqueFraction(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    var opaque = 0
    for (p in pixels) if ((p ushr 24) and 0xFF >= OPAQUE_ALPHA) opaque++
    return opaque.toFloat() / pixels.size
  }

  /**
   * True when a normalized app bitmap carries its own filled background. When false the artwork is
   * a bare glyph and the grid should place it on a tile instead of letting it float.
   */
  fun hasOwnBackground(bitmap: Bitmap): Boolean {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return opaqueFraction(pixels) >= MIN_OPAQUE_FILL
  }

  private fun outsideMask(sizePx: Int): Path = synchronized(masks) {
    masks.get(sizePx) ?: createSuperellipsePath(sizePx).apply {
      fillType = Path.FillType.INVERSE_WINDING
    }.also { mask ->
      // Once published, this path is only read by drawPath, never edited.
      masks.put(sizePx, mask)
    }
  }

  /**
   * Fourth-power superellipse, |x|^4 + |y|^4 = 1, approximated with small
   * tangent-matched cubic segments. Unlike a round rect, curvature changes along
   * the shoulders without a straight-edge/circular-arc join.
   * Geometry is prepared only on a bounded-cache miss.
   */
  private fun createSuperellipsePath(sizePx: Int): Path {
    val half = sizePx / 2.0
    val diagonal = 0.5.pow(0.25)
    val segments = 8
    val coordinates = FloatArray(6)
    val path = Path().apply { moveTo(half.toFloat(), 0f) }

    fun point(offset: Int, x: Double, y: Double, swapped: Boolean, quadrant: Int) {
      val u = if (swapped) y else x
      val v = if (swapped) -x else -y
      val rotatedX = when (quadrant) { 0 -> u; 1 -> -v; 2 -> -u; else -> v }
      val rotatedY = when (quadrant) { 0 -> v; 1 -> u; 2 -> -v; else -> -u }
      coordinates[offset] = (half + half * rotatedX).toFloat()
      coordinates[offset + 1] = (half + half * rotatedY).toFloat()
    }

    fun curve(x0: Double, x1: Double, swapped: Boolean, quadrant: Int) {
      val y0 = (1.0 - x0 * x0 * x0 * x0).pow(0.25)
      val y1 = (1.0 - x1 * x1 * x1 * x1).pow(0.25)
      val slope0 = x0 * x0 * x0 / (y0 * y0 * y0)
      val slope1 = x1 * x1 * x1 / (y1 * y1 * y1)
      val step = (x1 - x0) / 3.0
      point(0, x0 + step, y0 - slope0 * step, swapped, quadrant)
      point(2, x1 - step, y1 + slope1 * step, swapped, quadrant)
      point(4, x1, y1, swapped, quadrant)
      path.cubicTo(coordinates[0], coordinates[1], coordinates[2],
          coordinates[3], coordinates[4], coordinates[5])
    }

    for (quadrant in 0..3) {
      for (index in 0 until segments) {
        curve(diagonal * index / segments, diagonal * (index + 1) / segments, false, quadrant)
      }
      for (index in segments downTo 1) {
        curve(diagonal * index / segments, diagonal * (index - 1) / segments, true, quadrant)
      }
    }
    path.close()
    return path
  }
}
