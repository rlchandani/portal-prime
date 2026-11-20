/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Split glance (StandBy-style) information panel: the Hearthpane wordmark in the photo's accent,
 * large light numerals, a serif date, then weather and the next event. It sits beside the photo
 * card (below it in portrait) and redraws only at the minute, when the weather or agenda
 * changes, or when the accent cross-fades with a new photo.
 */
internal class SplitGlancePanel(private val context: Context) {
  private val density = context.resources.displayMetrics.density
  /**
   * Type scale from the panel's short side (0.72 on a Portal Mini), times the mock unit on a
   * Portal+ ([HearthMockUnit]: 1.33, so the clock is the approved 234.7 dp, not 176 dp).
   */
  private val scale = run {
    val m = context.resources.displayMetrics
    (minOf(m.widthPixels, m.heightPixels) / density / 1219f).coerceIn(.72f, 1f) *
        HearthMockUnit.boost(m.widthPixels, m.heightPixels, density)
  }
  val view = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    visibility = View.GONE
  }
  private val ui = Handler(Looper.getMainLooper())
  private val wordmark = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }
  private val ember = EmberMark(context)
  private val brand = text(13f, 600, tracking = .18f)
  private val clock = text(176f, 200, tracking = -.045f)
  private val date = serif(34f)
  private val rule = View(context).apply { setBackgroundColor(0x24FFFFFF) }
  private val facts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  private val weatherColumn = column()
  private val weatherRow = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }
  private val glyph = HearthWeatherGlyph(context)
  private val temperature = text(30f, 300)
  private val nextColumn = column()
  private val nextTitle = text(24f, 500).apply { ellipsize = TextUtils.TruncateAt.END }
  private val nextTime = text(17f, 400)
  private var accent = HearthReadability.EMBER
  private var accentFade: ValueAnimator? = null
  private var running = false
  private var shownTime = ""
  private var orbitOn = true

  init {
    brand.text = Brand.NAME.uppercase(Locale.getDefault())
    wordmark.addView(ember, LinearLayout.LayoutParams(dp(10f), dp(12f)).apply { marginEnd = dp(10f) })
    wordmark.addView(brand)
    view.addView(wordmark)
    view.addView(clock, params(top = 14f))
    view.addView(date, params(top = 8f))
    view.addView(rule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, density.toInt()))
        .apply { topMargin = dp(30f); bottomMargin = dp(22f) })
    weatherColumn.addView(label("WEATHER"))
    weatherRow.addView(glyph, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(8f) })
    weatherRow.addView(temperature)
    weatherColumn.addView(weatherRow, params(top = 6f))
    nextColumn.addView(label("NEXT"))
    nextColumn.addView(nextTitle, params(top = 6f))
    nextColumn.addView(nextTime)
    facts.addView(weatherColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(36f) })
    facts.addView(nextColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    view.addView(facts)
    listOf(clock, temperature, nextTitle).forEach { it.setTextColor(INK) }
    date.setTextColor(SUB)
    nextTime.setTextColor(FAINT)
    glyph.color = EMBER_GLYPH
    brand.setTextColor(accent)
    setWeather("")
    setNext(null, null)
  }

  fun start() {
    if (running) return
    running = true
    view.visibility = View.VISIBLE
    tick.run()
  }

  fun stop() {
    running = false
    ui.removeCallbacks(tick)
    accentFade?.cancel()
  }

  fun setOrbitEnabled(enabled: Boolean) { orbitOn = enabled }

  /** Cross-fade the wordmark to the incoming photo's accent with its dissolve. */
  fun setAccent(target: Int, durationMs: Long) {
    accentFade?.cancel()
    val from = accent
    if (from == target) return
    if (durationMs <= 0 || !ValueAnimator.areAnimatorsEnabled()) { accent = target; brand.setTextColor(target); return }
    accentFade = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = durationMs
      interpolator = HearthEasing.SOFT
      addUpdateListener {
        accent = HearthReadability.lerpArgb(from, target, it.animatedValue as Float)
        brand.setTextColor(accent)
      }
      start()
    }
  }

  fun setWeather(text: String) {
    val parsed = HearthWeather.parse(text)
    weatherColumn.visibility = if (parsed == null) View.GONE else View.VISIBLE
    if (parsed != null) {
      glyph.condition = parsed.first
      if (temperature.text.toString() != parsed.second) temperature.text = parsed.second
    }
  }

  /** The next (or current) event; null hides the column. */
  fun setNext(title: String?, timeLabel: String?) {
    nextColumn.visibility = if (title == null) View.GONE else View.VISIBLE
    if (title != null && nextTitle.text.toString() != title) nextTitle.text = title
    val t = timeLabel.orEmpty()
    if (nextTime.text.toString() != t) nextTime.text = t
  }

  private val tick = object : Runnable {
    override fun run() {
      if (!running) return
      val now = Date()
      val use24 = ImmortalSettings.use24HourClock(context)
      val locale = Locale.getDefault()
      val time = SimpleDateFormat(if (use24) "H:mm" else "h:mm", locale).format(now)
      if (time != shownTime) {
        shownTime = time
        clock.text = time
        date.text = SimpleDateFormat("EEEE '·' " +
            android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale).format(now)
        if (orbitOn) {
          // Burn-in orbit stepped with the minute redraw: no extra frames.
          val r = HearthOrbit.FACE_RADIUS_DP * density
          view.translationX = HearthOrbit.x(now.time, r, HearthOrbit.FACE_PERIOD_X_MS)
          view.translationY = HearthOrbit.y(now.time, r, HearthOrbit.FACE_PERIOD_Y_MS)
        }
      }
      ui.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 30L)
    }
  }

  private fun text(sizeDp: Float, weight: Int, tracking: Float = 0f) = TextView(context).apply {
    typeface = HearthFonts.display(context, weight)
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * scale)
    includeFontPadding = false
    fontFeatureSettings = "'tnum' 1, 'lnum' 1"
    letterSpacing = tracking
    maxLines = 1
  }

  private fun serif(sizeDp: Float) = TextView(context).apply {
    typeface = HearthFonts.serif(context)
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * scale)
    includeFontPadding = false
    maxLines = 1
  }

  private fun label(value: String) = text(12f, 600, tracking = .16f).apply {
    text = value
    setTextColor(FAINT)
  }

  private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

  private fun params(top: Float) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

  private fun dp(v: Float): Int = (v * density * scale).toInt()

  /** The Hearthpane ember drop with the brand gradient. */
  private class EmberMark(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
      paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFF0A65A.toInt(),
          0xFFD9773A.toInt(), android.graphics.Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
      canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), width * .5f, height * .5f, paint)
      canvas.drawRoundRect(0f, 0f, width.toFloat(), height * .6f, width * .27f, height * .23f, paint)
    }
  }

  /** A pixel rectangle (pure, so the layout is JVM-testable). */
  data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
  }

  companion object {
    private const val INK = 0xFFFBF7F1.toInt()
    private const val SUB = 0xDBFBF7F1.toInt()
    private const val FAINT = 0x8CFBF7F1.toInt()
    private const val EMBER_GLYPH = 0xFFF0A65A.toInt()

    /** The panel's next item: an event in progress, else the first upcoming one. */
    fun nextEvent(events: List<CalendarFeed.Event>, now: Long): CalendarFeed.Event? =
        events.filter { !it.allDay && it.endMillis > now }.minByOrNull { it.startMillis }

    /** Card and panel rectangles for a viewport, in px: landscape side by side, portrait stacked. */
    fun layout(width: Int, height: Int, density: Float): Pair<Box, Box> {
      // Margins are mock units too (44 / 64 / 48 / 24), so the card keeps the mock's proportions.
      val unit = density * HearthMockUnit.boost(width, height, density)
      val m = (44 * unit).toInt()
      return if (width >= height) {
        val pw = (width * .56f).toInt()
        val card = Box(width - pw - m, m, width - m, height - m)
        card to Box((64 * unit).toInt(), m, card.left - (48 * unit).toInt(), height - m)
      } else {
        val ch = (height * .56f).toInt()
        val card = Box(m, m, width - m, m + ch)
        card to Box(m, card.bottom + (24 * unit).toInt(), width - m, height - m)
      }
    }
  }
}
