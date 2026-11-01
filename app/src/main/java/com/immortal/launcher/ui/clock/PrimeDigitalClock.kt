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
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.immortal.launcher.AssetResolver
import com.immortal.launcher.ClockFaceView
import com.immortal.launcher.ClockSpec
import com.immortal.launcher.FaceStyle
import com.immortal.launcher.ui.theme.AccentGlow
import com.immortal.launcher.ui.theme.ArcTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Premium digital clock face drawn entirely on Canvas.
 * - Large hours + minutes centered with optional neon-halo shadow via BlurMaskFilter
 * - Seconds arc drawn in a compact trailing slot beside the time
 * - Replaces the flat TextView-based DigitalClockFaceView for DIGITAL mode
 */
class PrimeDigitalClockFaceView(
    context: Context,
    private val spec: ClockSpec,
    private val assets: AssetResolver,
) : ClockFaceView {

    private var formatterLocale = Locale.getDefault()
    private var hourMinFmt = SimpleDateFormat(DigitalClockTimeFormat.pattern(spec), formatterLocale)
    private var secondsFmt = SimpleDateFormat("ss", formatterLocale)

    private var currentHourMin = "--:--"
    private var currentSeconds = 0
    private var accessibleTime: String? = null
    private var measuredTextSize = 0f
    private var measuredTimeWidthPerPixel = 0f
    private val fontMetrics = Paint.FontMetrics()

    // Paint for the large time text — left-aligned so left edge matches the date row below
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FaceStyle.colorWithOpacity(spec.color, spec.opacity)
        textAlign = Paint.Align.LEFT
        typeface = FaceStyle.typeface(assets, spec, light = false)
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

        init {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val density = context.resources.displayMetrics.density
            timePaint.textSize = 100f
            timePaint.getFontMetrics(fontMetrics)
            // Stable digit reservation prevents the layout changing every minute.
            val widthUnit = maxOf(
                DigitalClockTimeFormat.reservedLabels(spec, formatterLocale)
                    .maxOf { timePaint.measureText(it) },
                timePaint.measureText(currentHourMin),
            ) / 100f
            measuredTimeWidthPerPixel = widthUnit
            fun limit(measureSpec: Int): Int =
                if (MeasureSpec.getMode(measureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
                else MeasureSpec.getSize(measureSpec)
            val geometry = DigitalClockGeometry.measure(
                density, spec.sizeScale, widthUnit,
                (fontMetrics.descent - fontMetrics.ascent) / 100f,
                limit(widthMeasureSpec), limit(heightMeasureSpec), 2f * density,
                showSeconds = spec.showSeconds,
            )
            measuredTextSize = geometry.textSize
            setMeasuredDimension(
                resolveSize(geometry.width, widthMeasureSpec),
                resolveSize(geometry.height, heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val padding = minOf(2f * context.resources.displayMetrics.density, w / 2f, h / 2f)
            timePaint.textSize = measuredTextSize
            timePaint.getFontMetrics(fontMetrics)
            val textWidth = timePaint.measureText(currentHourMin)
            val textHeight = fontMetrics.descent - fontMetrics.ascent
            val occupiedWidth = if (spec.showSeconds) {
                maxOf(textWidth, measuredTimeWidthPerPixel * measuredTextSize) +
                    measuredTextSize * DigitalClockGeometry.SECONDS_SLOT_RATIO
            } else textWidth
            val occupiedHeight = if (spec.showSeconds) {
                maxOf(textHeight, measuredTextSize * DigitalClockGeometry.SECONDS_DIAMETER_RATIO)
            } else textHeight
            // Only shrink to respect a tighter exact parent or a changed locale; never
            // enlarge back to the full width and cancel the selected scale.
            val fit = minOf(
                1f,
                if (occupiedWidth > 0f) (w - padding * 2f) / occupiedWidth else 1f,
                if (occupiedHeight > 0f) (h - padding * 2f) / occupiedHeight else 1f,
            ).coerceAtLeast(0f)
            val textSize = measuredTextSize * fit
            if (textSize <= 0f) return
            timePaint.textSize = textSize
            timePaint.getFontMetrics(fontMetrics)
            val textY = (h - (fontMetrics.descent - fontMetrics.ascent)) / 2f - fontMetrics.ascent
            canvas.drawText(currentHourMin, padding, textY, timePaint)

            // The entire ring stroke fits inside its own slot, after the stable time reservation.
            if (spec.showSeconds) {
                val diameter = textSize * DigitalClockGeometry.SECONDS_DIAMETER_RATIO
                val arcStroke = maxOf(textSize * 0.025f, 1f).coerceAtMost(diameter)
                val arcRadius = (diameter - arcStroke) / 2f
                arcTrackPaint.strokeWidth = arcStroke
                arcFillPaint.strokeWidth = arcStroke
                val timeWidth = maxOf(measuredTimeWidthPerPixel * textSize, textWidth * fit)
                val centerX = padding + timeWidth +
                    textSize * DigitalClockGeometry.SECONDS_GAP_RATIO + diameter / 2f
                val left   = centerX - arcRadius
                val top    = h / 2f - arcRadius
                val right  = centerX + arcRadius
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
        // Retained clock views must follow system language/timezone changes.
        val locale = Locale.getDefault()
        if (locale != formatterLocale) {
            formatterLocale = locale
            hourMinFmt = SimpleDateFormat(DigitalClockTimeFormat.pattern(spec), locale)
            secondsFmt = SimpleDateFormat("ss", locale)
            view.requestLayout()
        }
        val zone = TimeZone.getDefault()
        if (hourMinFmt.timeZone != zone) hourMinFmt.timeZone = zone
        if (spec.showSeconds && secondsFmt.timeZone != zone) secondsFmt.timeZone = zone
        // Honour blink separator: replace colon with space on blink-off ticks
        val raw = hourMinFmt.format(now)
        if (accessibleTime != raw) {
            accessibleTime = raw
            view.contentDescription = "Clock $raw"
        }
        val hourMin = if (!blinkOn && spec.separator.name == "BLINK") raw.replace(':', ' ') else raw
        val seconds = if (spec.showSeconds) secondsFmt.format(now).toIntOrNull() ?: 0 else 0
        if (currentHourMin == hourMin && currentSeconds == seconds) return
        currentHourMin = hourMin
        currentSeconds = seconds
        view.invalidate()
    }
}
