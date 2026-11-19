/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Drawn weather conditions: the Hearth overlay never uses emoji glyphs. */
enum class HearthWeather {
  CLEAR, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM, UNKNOWN;

  companion object {
    /** Split [Weather.fetch] output ("☀️ 72°") into a condition and its temperature text. */
    fun parse(text: String): Pair<HearthWeather, String>? {
      val trimmed = text.trim()
      if (trimmed.isEmpty()) return null
      val space = trimmed.indexOf(' ')
      if (space <= 0) return UNKNOWN to trimmed
      val condition = when (trimmed.substring(0, space)) {
        "☀️" -> CLEAR
        "🌤️" -> PARTLY
        "☁️" -> CLOUD
        "🌫️" -> FOG
        "🌦️", "🌧️" -> RAIN
        "🌨️" -> SNOW
        "⛈️" -> STORM
        else -> UNKNOWN
      }
      return condition to trimmed.substring(space + 1).trim()
    }
  }
}

/** A small vector weather glyph drawn in the current text colour. */
internal class HearthWeatherGlyph(context: Context) : View(context) {
  var condition: HearthWeather = HearthWeather.UNKNOWN
    set(value) { if (field != value) { field = value; invalidate() } }
  var night = false
    set(value) { if (field != value) { field = value; invalidate() } }
  var color: Int
    get() = fill.color
    set(value) {
      if (fill.color == value) return
      fill.color = value; stroke.color = value; invalidate()
    }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }
  private val path = Path()

  override fun onDraw(canvas: Canvas) {
    val s = minOf(width, height).toFloat()
    if (s <= 0f) return
    val cx = width / 2f
    val cy = height / 2f
    stroke.strokeWidth = s * .075f
    when (condition) {
      HearthWeather.CLEAR -> if (night) moon(canvas, cx, cy, s) else sun(canvas, cx, cy, s * .2f, s)
      HearthWeather.PARTLY -> {
        if (night) moon(canvas, cx - s * .12f, cy - s * .12f, s * .7f)
        else sun(canvas, cx - s * .14f, cy - s * .14f, s * .15f, s * .72f)
        cloud(canvas, cx + s * .08f, cy + s * .1f, s * .8f)
      }
      HearthWeather.CLOUD -> cloud(canvas, cx, cy, s)
      HearthWeather.FOG -> for (i in -1..1) {
        canvas.drawLine(cx - s * .36f, cy + i * s * .2f, cx + s * .36f, cy + i * s * .2f, stroke)
      }
      HearthWeather.RAIN, HearthWeather.SNOW, HearthWeather.STORM -> {
        cloud(canvas, cx, cy - s * .12f, s * .9f)
        for (i in -1..1) {
          val x = cx + i * s * .2f
          when (condition) {
            HearthWeather.SNOW -> canvas.drawCircle(x, cy + s * .32f, s * .045f, fill)
            HearthWeather.STORM -> if (i == 0) bolt(canvas, x, cy + s * .14f, s)
            else -> canvas.drawLine(x, cy + s * .2f, x - s * .06f, cy + s * .4f, stroke)
          }
        }
      }
      HearthWeather.UNKNOWN -> canvas.drawCircle(cx, cy, s * .12f, fill)
    }
  }

  private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float, s: Float) {
    canvas.drawCircle(cx, cy, r, fill)
    for (i in 0 until 8) {
      val a = Math.toRadians(i * 45.0)
      val c = Math.cos(a).toFloat()
      val n = Math.sin(a).toFloat()
      canvas.drawLine(cx + c * s * .3f, cy + n * s * .3f, cx + c * s * .42f, cy + n * s * .42f, stroke)
    }
  }

  private fun moon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
    path.reset()
    val r = s * .34f
    path.addCircle(cx, cy, r, Path.Direction.CW)
    val bite = Path().apply { addCircle(cx + r * .55f, cy - r * .35f, r * .85f, Path.Direction.CW) }
    path.op(bite, Path.Op.DIFFERENCE)
    canvas.drawPath(path, fill)
  }

  private fun cloud(canvas: Canvas, cx: Float, cy: Float, s: Float) {
    path.reset()
    path.addCircle(cx - s * .16f, cy + s * .04f, s * .17f, Path.Direction.CW)
    path.addCircle(cx + s * .06f, cy - s * .06f, s * .22f, Path.Direction.CW)
    path.addCircle(cx + s * .24f, cy + s * .07f, s * .14f, Path.Direction.CW)
    path.addRect(cx - s * .16f, cy + s * .04f, cx + s * .24f, cy + s * .21f, Path.Direction.CW)
    canvas.drawPath(path, fill)
  }

  private fun bolt(canvas: Canvas, x: Float, y: Float, s: Float) {
    path.reset()
    path.moveTo(x + s * .04f, y)
    path.lineTo(x - s * .08f, y + s * .18f)
    path.lineTo(x, y + s * .18f)
    path.lineTo(x - s * .05f, y + s * .34f)
    path.lineTo(x + s * .1f, y + s * .13f)
    path.lineTo(x + s * .02f, y + s * .13f)
    path.close()
    canvas.drawPath(path, fill)
  }
}

/** The Hearthpane ember mark (a flame-shaped drop with the brand gradient). */
internal class HearthEmberGlyph(context: Context) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val oval = RectF()

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), EMBER_TOP, EMBER_BOTTOM, Shader.TileMode.CLAMP)
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return
    // CSS border-radius 27% 27% 50% 50% / 23% 23% 50% 50%: a soft-topped drop.
    oval.set(0f, 0f, w, h)
    canvas.drawRoundRect(oval, w * .5f, h * .5f, paint)
    oval.set(0f, 0f, w, h * .6f)
    canvas.drawRoundRect(oval, w * .27f, h * .23f, paint)
  }

  private companion object {
    const val EMBER_TOP = 0xFFF0A65A.toInt()
    const val EMBER_BOTTOM = 0xFFD9773A.toInt()
  }
}

/** A small filled circle in the photo accent. */
internal class HearthDot(context: Context) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HearthReadability.EMBER }
  var color: Int
    get() = paint.color
    set(value) { if (paint.color != value) { paint.color = value; invalidate() } }

  override fun onDraw(canvas: Canvas) {
    canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
  }
}
