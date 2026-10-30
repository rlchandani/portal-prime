/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One small native drawing surface, refreshed at minute boundaries; no second ticker or effects. */
internal class QuietNightClockView(context: Context, tone: String) : View(context) {
  private val ink = if (tone == "red") Color.rgb(166, 85, 72) else Color.rgb(185, 159, 135)
  private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ink
    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    textAlign = Paint.Align.CENTER
  }
  private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ink
    alpha = 180
    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    textAlign = Paint.Align.CENTER
  }
  private var active = false
  private var timeChanges: BroadcastReceiver? = null
  private var time = ""
  private var date = ""
  private val tick = object : Runnable {
    override fun run() {
      if (!active || !isAttachedToWindow) return
      val locale = resources.configuration.locales[0] ?: Locale.getDefault()
      val now = Date()
      val use24 = ImmortalSettings.use24HourClock(context)
      time = SimpleDateFormat(if (use24) "H:mm" else "h:mm", locale).format(now)
      val datePattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
      date = SimpleDateFormat(datePattern, locale).format(now)
      contentDescription = "$time, $date"
      invalidate()
      removeCallbacks(this)
      postDelayed(this, DarkRoomPolicy.nextMinuteDelay(System.currentTimeMillis()))
    }
  }

  init {
    setBackgroundColor(Color.BLACK)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
  }

  fun setActive(value: Boolean) {
    active = value
    removeCallbacks(tick)
    if (value && isAttachedToWindow) {
      observeTimeChanges()
      tick.run()
    } else stopObservingTimeChanges()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (active) {
      observeTimeChanges()
      tick.run()
    }
  }

  override fun onDetachedFromWindow() {
    stopObservingTimeChanges()
    removeCallbacks(tick)
    super.onDetachedFromWindow()
  }

  private fun observeTimeChanges() {
    if (timeChanges != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (timeChanges !== this || !active || !isAttachedToWindow) return
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) tick.run()
      }
    }
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_TIME_CHANGED)
      addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    context.registerReceiver(receiver, filter)
    timeChanges = receiver
  }

  private fun stopObservingTimeChanges() {
    val receiver = timeChanges ?: return
    timeChanges = null
    context.unregisterReceiver(receiver)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (width <= 0 || height <= 0) return
    clockPaint.textSize = height * 0.28f
    val measured = clockPaint.measureText(time).coerceAtLeast(1f)
    clockPaint.textSize *= minOf(1f, width * 0.82f / measured)
    datePaint.textSize = minOf(height * 0.041f, width * 0.038f)
    val dateWidth = datePaint.measureText(date).coerceAtLeast(1f)
    datePaint.textSize *= minOf(1f, width * 0.84f / dateWidth)
    val clockHeight = clockPaint.descent() - clockPaint.ascent()
    val dateHeight = datePaint.descent() - datePaint.ascent()
    val gap = height * 0.043f
    val top = (height - clockHeight - dateHeight - gap) / 2f
    canvas.drawText(time, width / 2f, top - clockPaint.ascent(), clockPaint)
    canvas.drawText(date, width / 2f, top + clockHeight + gap - datePaint.ascent(), datePaint)
  }
}
