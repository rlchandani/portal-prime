/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.ui.clock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.immortal.launcher.ClockFaceView
import com.immortal.launcher.ClockSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native split-flap clock — replaces the Fliqlo WebView.
 * Each digit has a top half (current) and bottom half that flips down on change.
 * The flip is a ValueAnimator driving a scaleY transform on the flip card.
 */
class SplitFlapClockFaceView(
    context: Context,
    private val spec: ClockSpec,
) : ClockFaceView {

    private val timeFmt = SimpleDateFormat("HHmm", Locale.getDefault())
    private var displayedDigits = charArrayOf('0', '0', '0', '0')
    private var pendingDigits   = charArrayOf('0', '0', '0', '0')
    // flip progress per digit: 0f = idle, 0..1f = flipping
    private val flipProgress    = FloatArray(4) { 0f }
    private val animators       = arrayOfNulls<ValueAnimator>(4)

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A2E.toInt()
    }
    private val topCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22223A.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEEFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8888AA.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        strokeWidth = 3f
    }

    override val view: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val cardW = w * 0.18f
            val cardH = h * 0.55f
            val radius = cardW * 0.12f
            val gap    = cardW * 0.06f
            val colonW = cardW * 0.3f

            // Total width: 4 cards + 1 colon + gaps (4 gaps: after each digit, plus after colon)
            val totalW = 4 * cardW + colonW + 4 * gap
            val startX = (w - totalW) / 2f
            val startY = (h - cardH) / 2f

            textPaint.textSize  = cardH * 0.7f
            colonPaint.textSize = cardH * 0.5f

            var x = startX
            for (i in 0..3) {
                // Insert colon between digit 1 and 2
                if (i == 2) {
                    colonPaint.alpha = 255
                    canvas.drawText(":", x + colonW / 2f, startY + cardH * 0.65f, colonPaint)
                    x += colonW + gap
                }

                val rect = RectF(x, startY, x + cardW, startY + cardH)

                // Draw card background
                canvas.drawRoundRect(rect, radius, radius, cardPaint)

                // Draw top half with current digit
                val topRect = RectF(x, startY, x + cardW, startY + cardH / 2f)
                canvas.drawRoundRect(topRect, radius, radius, topCardPaint)

                // Draw digit text
                canvas.drawText(
                    displayedDigits[i].toString(),
                    x + cardW / 2f,
                    startY + cardH * 0.63f,
                    textPaint,
                )

                // Draw divider line
                canvas.drawLine(x, startY + cardH / 2f, x + cardW, startY + cardH / 2f, dividerPaint)

                // Draw flip card if animating
                val progress = flipProgress[i]
                if (progress > 0f) {
                    val flipRect = RectF(x, startY, x + cardW, startY + cardH / 2f)
                    canvas.save()
                    canvas.scale(1f, progress, x + cardW / 2f, startY + cardH / 2f)
                    canvas.drawRoundRect(flipRect, radius, radius, topCardPaint)
                    // Draw the pending digit on the flipping card
                    textPaint.alpha = (progress * 255).toInt()
                    canvas.drawText(
                        pendingDigits[i].toString(),
                        x + cardW / 2f,
                        startY + cardH * 0.63f,
                        textPaint,
                    )
                    textPaint.alpha = 255
                    canvas.restore()
                }

                x += cardW + gap
            }
        }
    }

    private fun flipDigit(index: Int, newChar: Char) {
        pendingDigits[index] = newChar
        animators[index]?.cancel()
        val anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                flipProgress[index] = va.animatedValue as Float
                if (flipProgress[index] <= 0f) {
                    displayedDigits[index] = newChar
                    flipProgress[index] = 0f
                }
                view.invalidate()
            }
        }
        anim.start()
        animators[index] = anim
    }

    override fun update(now: Date, blinkOn: Boolean) {
        val digits = timeFmt.format(now).toCharArray()
        for (i in 0..3) {
            if (digits[i] != displayedDigits[i] && flipProgress[i] == 0f) {
                flipDigit(i, digits[i])
            }
        }
    }

    override fun dispose() {
        animators.forEach { it?.cancel() }
    }
}
