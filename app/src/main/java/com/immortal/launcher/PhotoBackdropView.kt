/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.view.View

/**
 * The "ambient fill" behind a contained photo: its own pre-blurred copy, covered, graded and
 * vignetted in ONE opaque draw (bitmap shader x radial gradient), and clipped out under the
 * photo so a dissolve never blends the backdrop through it. No live blur, no extra layers.
 */
internal class PhotoBackdropView(context: Context) : View(context) {
  private var bitmap: Bitmap? = null
  private val hole = RectF()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private val matrix = Matrix()
  private var night = 0f

  /** [photoRect] is the photo's smallest on-screen rectangle; [inset] keeps its rounded corners covered. */
  fun show(backdrop: Bitmap, photoRect: FloatArray, inset: Float, nightLevel: Float) {
    bitmap = backdrop
    hole.set(photoRect[0] + inset, photoRect[1] + inset, photoRect[2] - inset, photoRect[3] - inset)
    night = nightLevel
    rebuild()
    invalidate()
  }

  fun clear() {
    bitmap = null
    paint.shader = null
    invalidate()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    rebuild()
  }

  override fun hasOverlappingRendering(): Boolean = false

  private fun rebuild() {
    val source = bitmap ?: return
    if (width <= 0 || height <= 0) return
    coverMatrix(source.width, source.height, width, height, matrix)
    val image = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
      setLocalMatrix(matrix)
    }
    // Mock veil: radial-gradient(120% 90% at 50% 40%, clear -> 45% black), as a multiply.
    val radius = maxOf(width, height).toFloat()
    val veil = RadialGradient(width * .5f, height * .4f, radius,
        intArrayOf(Color.WHITE, Color.WHITE, Color.rgb(140, 140, 140)),
        floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP).apply {
      setLocalMatrix(Matrix().apply {
        setScale(1.2f * width / radius, .9f * height / radius, width * .5f, height * .4f)
      })
    }
    paint.shader = ComposeShader(image, veil, PorterDuff.Mode.MULTIPLY)
    paint.colorFilter = ColorMatrixColorFilter(grade(night))
  }

  @Suppress("DEPRECATION")
  override fun onDraw(canvas: Canvas) {
    if (bitmap == null || paint.shader == null) return
    val save = canvas.save()
    if (!hole.isEmpty) {
      if (android.os.Build.VERSION.SDK_INT >= 26) canvas.clipOutRect(hole)
      else canvas.clipRect(hole, Region.Op.DIFFERENCE)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    canvas.restoreToCount(save)
  }

  companion object {
    /** The backdrop is the photo covered and enlarged 15%, so its blurred edge never shows. */
    const val OVERSCAN = 1.15f
    private const val SATURATION = 1.15f

    /** Map a [bw]x[bh] backdrop onto a [vw]x[vh] viewport: cover, centred, with overscan. */
    fun coverMatrix(bw: Int, bh: Int, vw: Int, vh: Int, out: Matrix) {
      val s = maxOf(vw.toFloat() / bw, vh.toFloat() / bh) * OVERSCAN
      out.setScale(s, s)
      out.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f)
    }

    /** Brightness .62, saturation 1.15 (mock Ambient), times the evening warm dim. */
    fun grade(nightLevel: Float): ColorMatrix {
      val m = ColorMatrix().apply { setSaturation(SATURATION) }
      m.postConcat(warmth(nightLevel, HearthReadability.AMBIENT_BRIGHTNESS))
      return m
    }

    /** The evening multiply as a matrix, optionally with an extra [brightness] factor. */
    fun warmth(nightLevel: Float, brightness: Float = 1f): ColorMatrix {
      val warm = HearthNight.multiplyColor(nightLevel)
      return ColorMatrix().apply {
        setScale(brightness * Color.red(warm) / 255f, brightness * Color.green(warm) / 255f,
            brightness * Color.blue(warm) / 255f, 1f)
      }
    }
  }
}
