/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.ui.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.immortal.launcher.AssetResolver
import com.immortal.launcher.ClockFaceView
import com.immortal.launcher.ClockSpec
import com.immortal.launcher.FaceStyle
import com.immortal.launcher.ui.theme.AccentGlow
import com.immortal.launcher.ui.theme.ArcTrack
import com.immortal.launcher.ui.theme.ClockPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Premium digital clock face drawn entirely on Canvas.
 * - Large hours + minutes centered with optional neon-halo shadow via BlurMaskFilter
 * - Seconds arc drawn as a thin circle progress indicator around the text
 * - Replaces the flat TextView-based DigitalClockFaceView for DIGITAL mode
 */
class PrimeDigitalClockFaceView(
    context: Context,
    private val spec: ClockSpec,
    @Suppress("UNUSED_PARAMETER") private val assets: AssetResolver,
) : ClockFaceView {

    private val hourMinFmt = SimpleDateFormat(if (spec.is24h) "HH:mm" else "hh:mm", Locale.getDefault())
    private val secondsFmt = SimpleDateFormat("ss", Locale.getDefault())

    private var currentHourMin = "--:--"
    private var currentSeconds = 0
    private var blinkColon = true

    // Paint for the large time text — left-aligned so left edge matches the date row below
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FaceStyle.colorWithOpacity(spec.color, spec.opacity)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }

    // Paint for the seconds arc track (translucent background ring)
    private val arcTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ArcTrack.toArgb()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Paint for the seconds arc fill (progress)
    private val arcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AccentGlow.toArgb()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override val view: View = object : View(context) {

        init { setBackgroundColor(android.graphics.Color.TRANSPARENT) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Fixed 500dp wide x 100dp tall — wide enough for a large readable time,
            // constrained so it stays in the bottom-left corner rather than spanning the screen.
            val density = context.resources.displayMetrics.density
            val w = (500 * density).toInt()
            val h = (100 * density).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // Auto-fit: scale text so it fills the full view width
            val sample = currentHourMin.ifEmpty { "00:00" }
            var textSize = h * 0.85f * (spec.sizeScale / 100f)
            timePaint.textSize = textSize
            val measured = timePaint.measureText(sample)
            if (measured > 0f) textSize *= (w / measured)
            timePaint.textSize = textSize

            // Draw left-aligned from x=0, baseline at 85% height
            val textY = h * 0.85f
            canvas.drawText(currentHourMin, 0f, textY, timePaint)

            // Seconds arc — thin ring enclosing the clock face (only when user enables seconds)
            if (spec.showSeconds) {
                val margin = textSize * 0.08f
                val arcRadius = minOf(w, h) * 0.45f - margin
                val arcStroke = maxOf(textSize * 0.035f, 3f)
                arcTrackPaint.strokeWidth = arcStroke
                arcFillPaint.strokeWidth = arcStroke

                val left   = w / 2f - arcRadius
                val top    = h / 2f - arcRadius
                val right  = w / 2f + arcRadius
                val bottom = h / 2f + arcRadius

                // Full background ring
                canvas.drawArc(left, top, right, bottom, -90f, 360f, false, arcTrackPaint)

                // Progress arc — sweep proportional to elapsed seconds (0–59 → 0°–354°)
                if (currentSeconds > 0) {
                    val sweep = (currentSeconds / 60f) * 360f
                    canvas.drawArc(left, top, right, bottom, -90f, sweep, false, arcFillPaint)
                }
            }
        }
    }

    override fun update(now: Date, blinkOn: Boolean) {
        // Honour blink separator: replace colon with space on blink-off ticks
        val raw = hourMinFmt.format(now)
        currentHourMin = if (!blinkOn && spec.separator.name == "BLINK") raw.replace(':', ' ') else raw
        currentSeconds = secondsFmt.format(now).toIntOrNull() ?: 0
        blinkColon = blinkOn
        view.invalidate()
    }
}
