/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Night photo" (embers): drawn above the warm-filtered photos while the night clock is on with
 * the Photo colour. A static dark vignette (mock: radial 80% x 80% at 50% 45%, .55 -> .86 black)
 * and a small amber clock with a serif date near the bottom, which drifts a few dp each minute
 * for burn-in protection. It never invalidates between minutes.
 */
internal class NightPhotoOverlay(context: Context) : View(context) {
  private val density = resources.displayMetrics.density
  private val veil = Paint(Paint.DITHER_FLAG)
  private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = HearthFonts.display(context, 200)
    letterSpacing = -.03f
    fontFeatureSettings = "'tnum' 1, 'lnum' 1"
    color = AMBER
  }
  private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = HearthFonts.serif(context)
    color = AMBER_SOFT
  }
  private val ui = Handler(Looper.getMainLooper())
  private var shown = ""
  private var dateText = ""
  private var active = false

  init {
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
  }

  fun setActive(value: Boolean) {
    if (active == value) return
    active = value
    ui.removeCallbacks(tick)
    if (value) tick.run()
  }

  override fun onDetachedFromWindow() {
    setActive(false)
    super.onDetachedFromWindow()
  }

  override fun hasOverlappingRendering(): Boolean = false

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    val r = maxOf(w, h) * .8f
    veil.shader = RadialGradient(w * .5f, h * .45f, r, intArrayOf(VEIL_CENTRE, VEIL_EDGE),
        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
  }

  private val tick = object : Runnable {
    override fun run() {
      if (!active) return
      val now = Date()
      val locale = Locale.getDefault()
      shown = SimpleDateFormat(if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm", locale).format(now)
      dateText = SimpleDateFormat("EEEE '·' " +
          android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale).format(now)
      contentDescription = "$shown, $dateText"
      invalidate()
      ui.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 30L)
    }
  }

  override fun onDraw(canvas: Canvas) {
    if (width <= 0 || height <= 0) return
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
    if (shown.isEmpty()) return
    // Mock units ([HearthMockUnit]): the approved clock is 92 units = 122.7 dp on a Portal+.
    val unit = HearthMockUnit.boost(width, height, density)
    val type = (minOf(width, height) / density / 1219f).coerceIn(.72f, 1f) * unit
    time.textSize = 92f * density * type
    date.textSize = 22f * density * type
    val now = System.currentTimeMillis()
    val dx = HearthOrbit.x(now, 10f * density * unit, DRIFT_X_MS)
    val dy = HearthOrbit.y(now, 6f * density * unit, DRIFT_Y_MS)
    val dateBaseline = height - 70f * density * type - date.descent() + dy
    canvas.drawText(dateText, width / 2f + dx, dateBaseline, date)
    canvas.drawText(shown, width / 2f + dx, dateBaseline + date.ascent() - 8f * density * type - time.descent(), time)
  }

  companion object {
    const val AMBER = 0xFFDEA06C.toInt()
    private const val AMBER_SOFT = 0x99DEA06C.toInt()
    private const val VEIL_CENTRE = 0x8C000000.toInt()
    private const val VEIL_EDGE = 0xDB000000.toInt()
    private const val DRIFT_X_MS = 1_800_000L
    private const val DRIFT_Y_MS = 2_400_000L

    /** Warm multiply (mock #FF9050 at 55%) applied to the photo draw as a colour filter. */
    fun warmFilter(): android.graphics.ColorMatrix = android.graphics.ColorMatrix().apply {
      setScale(1f, 1f - .55f * (1f - 0x90 / 255f), 1f - .55f * (1f - 0x50 / 255f), 1f)
    }

    /** Night photo holds each photo longer than the day interval (mock: max(1.6x, 10 s)). */
    fun intervalMs(dayIntervalMs: Long): Long = maxOf((dayIntervalMs * 1.6).toLong(), 10_000L)
  }
}
