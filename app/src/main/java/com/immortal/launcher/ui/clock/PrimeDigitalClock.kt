/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.ui.clock

import android.content.Context
import android.graphics.BlurMaskFilter
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

    // Paint for the large time text with neon glow via BlurMaskFilter
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FaceStyle.colorWithOpacity(spec.color, spec.opacity)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.SOLID)
    }

    // Separate glow layer drawn behind the main text
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FaceStyle.colorWithOpacity(spec.color, 0.6f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
        alpha = 160
    }

    // Paint for the seconds arc track (translucent background ring)
    private val arcTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ArcTrack.toArgb()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Paint for the seconds arc fill (glowing progress)
    private val arcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AccentGlow.toArgb()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    override val view: View = object : View(context) {

        init {
            // Required so BlurMaskFilter glow is not clipped at view edges
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f

            // Time text size — ~38% of the shorter dimension, scaled by user preference
            val textSize = minOf(w, h) * 0.38f * (spec.sizeScale / 100f)
            timePaint.textSize = textSize
            glowPaint.textSize = textSize

            // Vertical center: baseline offset is roughly 0.35 * textSize above center
            val textY = cy + textSize * 0.35f

            val timeStr = currentHourMin

            // Draw glow layer first (blurred, rendered in software layer)
            canvas.drawText(timeStr, cx, textY, glowPaint)

            // Draw crisp text on top
            canvas.drawText(timeStr, cx, textY, timePaint)

            // Seconds arc — thin ring enclosing the clock face (only when user enables seconds)
            if (spec.showSeconds) {
                val margin = textSize * 0.08f
                val arcRadius = minOf(w, h) * 0.45f - margin
                val arcStroke = maxOf(textSize * 0.035f, 3f)
                arcTrackPaint.strokeWidth = arcStroke
                arcFillPaint.strokeWidth = arcStroke

                val left   = cx - arcRadius
                val top    = cy - arcRadius
                val right  = cx + arcRadius
                val bottom = cy + arcRadius

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
