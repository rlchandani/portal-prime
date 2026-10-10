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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/** Worker-only, fixed small texture: blur is never applied to a moving full-size Android view. */
@Suppress("DEPRECATION")
internal object FilmstripBackdrop {
  internal fun size(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = 512.0 / maxOf(width, height)
    return (width*scale).roundToInt().coerceIn(1,512) to (height*scale).roundToInt().coerceIn(1,512)
  }

  fun prepare(context: Context, source: Bitmap, viewportWidth: Int, viewportHeight: Int,
      density: Float): Bitmap {
    require(viewportWidth > 0 && viewportHeight > 0 && density > 0f)
    val (width, height) = size(viewportWidth, viewportHeight)
    val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var result: Bitmap? = null
    var rs: android.renderscript.RenderScript? = null
    var input: android.renderscript.Allocation? = null
    var output: android.renderscript.Allocation? = null
    var blur: android.renderscript.ScriptIntrinsicBlur? = null
    try {
      val saturation = ColorMatrix().apply { setSaturation(1.2f) }
      val brightness = ColorMatrix(floatArrayOf(
          .6f,0f,0f,0f,0f, 0f,.6f,0f,0f,0f, 0f,0f,.6f,0f,0f, 0f,0f,0f,1f,0f))
      saturation.postConcat(brightness)
      val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(saturation) }
      val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
      val drawnWidth = source.width * scale
      val drawnHeight = source.height * scale
      Canvas(small).drawBitmap(source, Rect(0,0,source.width,source.height),
          RectF((width-drawnWidth)/2f, (height-drawnHeight)/2f,
              (width+drawnWidth)/2f, (height+drawnHeight)/2f), paint)
      if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
      rs = android.renderscript.RenderScript.create(context.applicationContext)
      input = android.renderscript.Allocation.createFromBitmap(rs, small)
      output = android.renderscript.Allocation.createTyped(rs, input.type)
      blur = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
      // CSS: 24px blur, -10% inset, then scale(1.15). Radius here is in the small texture.
      val targetSigma = 20f * density * width / viewportWidth
      val passes = kotlin.math.ceil((targetSigma / 10.6f) * (targetSigma / 10.6f)).toInt().coerceIn(1,8)
      // AOSP intrinsic uses sigma = .4 * radius + .6; Gaussian variances add over passes.
      // https://android.googlesource.com/platform/frameworks/rs/+/refs/tags/android-13.0.0_r52/cpu_ref/rsCpuIntrinsicBlur.cpp
      blur.setRadius(((targetSigma / kotlin.math.sqrt(passes.toFloat())-.6f)/.4f).coerceIn(.1f,25f))
      repeat(passes) { pass ->
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        blur.setInput(input)
        blur.forEach(output)
        if (pass+1 < passes) { val swap = input; input = output; output = swap }
      }
      result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      checkNotNull(output).copyTo(result)
      if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
      return result.also { result = null }
    } finally {
      runCatching { blur?.destroy() }; runCatching { output?.destroy() }
      runCatching { input?.destroy() }; runCatching { rs?.destroy() }
      small.recycle(); result?.recycle()
    }
  }
}
