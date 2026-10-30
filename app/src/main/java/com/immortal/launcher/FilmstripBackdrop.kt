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
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/** Worker-only, fixed small texture: blur is never applied to a moving full-size Android view. */
@Suppress("DEPRECATION")
internal object FilmstripBackdrop {
  internal data class Plan(val width: Int, val height: Int, val passes: Int, val radius: Float)

  internal fun size(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = 512.0 / maxOf(width, height)
    return (width*scale).roundToInt().coerceIn(1,512) to (height*scale).roundToInt().coerceIn(1,512)
  }

  internal fun plan(viewportWidth: Int, viewportHeight: Int, density: Float): Plan {
    require(viewportWidth > 0 && viewportHeight > 0 && density.isFinite() && density > 0f)
    val (width, height) = size(viewportWidth, viewportHeight)
    // Preserve the existing CSS-equivalent blur and Gaussian variance across passes.
    val targetSigma = 20f * density * width / viewportWidth
    val passes = kotlin.math.ceil((targetSigma / 10.6f) * (targetSigma / 10.6f)).toInt().coerceIn(1,8)
    val radius = ((targetSigma / kotlin.math.sqrt(passes.toFloat())-.6f)/.4f).coerceIn(.1f,25f)
    return Plan(width, height, passes, radius)
  }

  fun prepare(context: Context, source: Bitmap, viewportWidth: Int, viewportHeight: Int,
      density: Float): Bitmap = session(context, viewportWidth, viewportHeight, density).use {
    it.prepare(source)
  }

  fun session(context: Context, viewportWidth: Int, viewportHeight: Int, density: Float): Session =
      Session(context.applicationContext, plan(viewportWidth, viewportHeight, density))

  /** One preparation worker owns this scratch set; returned textures have independent ownership. */
  internal class Session(private val context: Context, private val plan: Plan) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false
    private var small: Bitmap? = null
    private var canvas: Canvas? = null
    private var rs: android.renderscript.RenderScript? = null
    private var first: android.renderscript.Allocation? = null
    private var second: android.renderscript.Allocation? = null
    private var blur: android.renderscript.ScriptIntrinsicBlur? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
      val saturation = ColorMatrix().apply { setSaturation(1.2f) }
      val brightness = ColorMatrix(floatArrayOf(
          .6f,0f,0f,0f,0f, 0f,.6f,0f,0f,0f, 0f,0f,.6f,0f,0f, 0f,0f,0f,1f,0f))
      saturation.postConcat(brightness)
      colorFilter = ColorMatrixColorFilter(saturation)
    }

    private fun checkOwner() {
      check(Thread.currentThread() === owner) { "Filmstrip backdrop scratch belongs to its preparation worker" }
    }

    private fun checkInterrupted() {
      if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
    }

    private fun initialize() {
      if (small != null) return
      try {
        small = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(checkNotNull(small))
        val scriptContext = android.renderscript.RenderScript.create(context)
        rs = scriptContext
        first = android.renderscript.Allocation.createFromBitmap(scriptContext, checkNotNull(small))
        second = android.renderscript.Allocation.createTyped(scriptContext, checkNotNull(first).type)
        blur = android.renderscript.ScriptIntrinsicBlur.create(scriptContext,
            android.renderscript.Element.U8_4(scriptContext))
        checkNotNull(blur).setRadius(plan.radius)
      } catch (failure: Throwable) {
        close()
        throw failure
      }
    }

    fun prepare(source: Bitmap): Bitmap {
      checkOwner(); check(!closed) { "Filmstrip backdrop scratch is closed" }; checkInterrupted()
      initialize()
      val scratch = checkNotNull(small)
      val width = plan.width; val height = plan.height
      var result: Bitmap? = null
      try {
        // A fresh bitmap used to provide these zeroes, including transparent source regions.
        scratch.eraseColor(Color.TRANSPARENT)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        checkNotNull(canvas).drawBitmap(source, Rect(0,0,source.width,source.height),
            RectF((width-drawnWidth)/2f, (height-drawnHeight)/2f,
                (width+drawnWidth)/2f, (height+drawnHeight)/2f), paint)
        checkInterrupted()
        // Always reset the ping-pong direction, including after an even-pass photograph.
        var input = checkNotNull(first); var output = checkNotNull(second)
        input.copyFrom(scratch)
        val script = checkNotNull(blur)
        repeat(plan.passes) { pass ->
          checkInterrupted()
          script.setInput(input)
          script.forEach(output)
          if (pass+1 < plan.passes) { val swap = input; input = output; output = swap }
        }
        result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.copyTo(result)
        checkInterrupted()
        return result.also { result = null }
      } finally {
        result?.recycle()
      }
    }

    override fun close() {
      checkOwner()
      if (closed) return
      closed = true
      runCatching { blur?.destroy() }; runCatching { second?.destroy() }
      runCatching { first?.destroy() }; runCatching { rs?.destroy() }
      canvas?.setBitmap(null)
      small?.recycle()
      blur = null; second = null; first = null; rs = null; canvas = null; small = null
    }
  }
}
