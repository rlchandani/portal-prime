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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** One pre-blurred image currently visible on the stage, in stage pixel coordinates. */
internal class FrostLayer(
    val bitmap: Bitmap,
    /** Maps backdrop bitmap pixels to stage pixels (the photo's live pose). */
    val matrix: Matrix,
    /** The grade the stage applies to this layer (backdrop dimming, evening warmth). */
    val grade: ColorMatrix?,
    val alpha: Float,
    /** Visible part of this layer in stage pixels; null = everywhere. */
    val clip: RectF?,
)

/** What a frosted card needs from the photo stage. Main thread only. */
internal interface FrostSource {
  /** Bottom-to-top layers currently visible; empty when nothing premium is showing. */
  fun frostLayers(out: MutableList<FrostLayer>)
  fun stageOrigin(out: IntArray)
  fun addFrostListener(listener: () -> Unit)
  fun removeFrostListener(listener: () -> Unit)
}

/**
 * The Hearth "glass" card: the blurred photo region under the card (one extra quad per visible
 * layer, no live blur), a tint, and a hairline edge. Redraws only when the stage moves or
 * dissolves — frames that are being drawn anyway — or when its own content changes.
 */
internal class FrostedCard(context: Context, private val radiusDp: Float) : FrameLayout(context) {
  var source: FrostSource? = null
    set(value) {
      if (field === value) return
      if (isAttachedToWindow) field?.removeFrostListener(listener)
      field = value
      if (isAttachedToWindow) value?.addFrostListener(listener)
      invalidate()
    }
  /** Night tint is warmer and a little denser. */
  var night = false
    set(value) { if (field != value) { field = value; tint.color = tintColor(); invalidate() } }

  private val density = resources.displayMetrics.density
  private val radius = radiusDp * density
  private val layers = ArrayList<FrostLayer>(4)
  private val bounds = RectF()
  private val clip = RectF()
  private val local = Matrix()
  private val cardOrigin = IntArray(2)
  private val stageOrigin = IntArray(2)
  private val frost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor() }
  private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FALLBACK }
  private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = maxOf(1f, density * .75f)
    color = EDGE
  }
  private val listener: () -> Unit = { invalidate() }
  private val shaders = HashMap<Bitmap, BitmapShader>(4)
  private val filters = HashMap<ColorMatrix?, ColorMatrixColorFilter>(4)

  init {
    setWillNotDraw(false)
    clipToOutline = false
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
      }
    }
    elevation = 10f * density
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    source?.addFrostListener(listener)
  }

  override fun onDetachedFromWindow() {
    source?.removeFrostListener(listener)
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return
    bounds.set(0f, 0f, w, h)
    layers.clear()
    source?.frostLayers(layers)
    // Retain derived objects only for what is visible now.
    shaders.keys.retainAll(layers.mapTo(HashSet()) { it.bitmap })
    filters.keys.retainAll(layers.mapTo(HashSet()) { it.grade })
    if (layers.isEmpty()) {
      canvas.drawRoundRect(bounds, radius, radius, solid)
    } else {
      // Card position in stage coordinates, including the overlay's burn-in orbit.
      getLocationInWindow(cardOrigin)
      source?.stageOrigin(stageOrigin)
      val dx = (stageOrigin[0] - cardOrigin[0]).toFloat()
      val dy = (stageOrigin[1] - cardOrigin[1]).toFloat()
      for (layer in layers) {
        if (layer.bitmap.isRecycled || layer.alpha <= 0f) continue
        local.set(layer.matrix)
        local.postTranslate(dx, dy)
        // Recording snapshots the native shader, so one retained shader per bitmap is safe.
        frost.shader = shaders.getOrPut(layer.bitmap) {
          BitmapShader(layer.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }.apply { setLocalMatrix(local) }
        frost.colorFilter = filters.getOrPut(layer.grade) {
          ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(FROST_SATURATION)
            layer.grade?.let(::postConcat)
          })
        }
        frost.alpha = (layer.alpha * 255f).toInt().coerceIn(0, 255)
        val save = canvas.save()
        layer.clip?.let {
          clip.set(it)
          clip.offset(dx, dy)
          canvas.clipRect(clip)
        }
        canvas.drawRoundRect(bounds, radius, radius, frost)
        canvas.restoreToCount(save)
      }
      frost.shader = null
      canvas.drawRoundRect(bounds, radius, radius, tint)
    }
    val inset = edge.strokeWidth / 2f
    canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius - inset, radius - inset, edge)
  }

  private fun tintColor(): Int = if (night) NIGHT_TINT else TINT

  private companion object {
    /** Mock .glass: rgba(22,20,26,.34) over a blurred, 1.3x saturated backdrop. */
    const val TINT = 0x5716141A
    const val NIGHT_TINT = 0x6E1A120C
    const val FALLBACK = 0xB816141A.toInt()
    const val EDGE = 0x1FFFFFFF
    const val FROST_SATURATION = 1.3f
  }
}
