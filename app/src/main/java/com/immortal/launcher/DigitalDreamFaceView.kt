/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.PathParser
import com.immortal.launcher.DigitalClockFaceSpec.Face
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The redesigned digital clock screensaver: six faces drawn on one canvas in the bundled Hearth
 * type. Classic and Bold use the light/heavy display numerals with a serif date; Neon is a warm
 * ember tube glow; Retro LED draws real 7-segment paths with ghost segments; Flip is the premium
 * split-flap with a 3D card turn; Analog is a warm dial with serif numerals and a sweeping
 * second hand. Between minute changes nothing redraws unless seconds are shown.
 */
internal class DigitalDreamFaceView(context: Context, private val settings: DigitalClockConfig.Settings) : View(context) {
  private val face = DigitalClockFaceSpec.face(settings.style)
  private val density = resources.displayMetrics.density
  private val time = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
    textAlign = Paint.Align.CENTER
    fontFeatureSettings = "'tnum' 1, 'lnum' 1"
    typeface = HearthFonts.display(context, when (face) { Face.BOLD -> 700; Face.NEON -> 300; Face.FLIP -> 500; else -> 200 })
    letterSpacing = when (face) { Face.BOLD -> -.05f; Face.FLIP -> -.02f; else -> -.04f }
    color = if (face == Face.NEON) NEON_INK else INK
  }
  private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = HearthFonts.serif(context)
    color = DATE_INK
  }
  private val glow = Paint(time)
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
  private val backdrop = Paint(Paint.DITHER_FLAG)
  private val segments = DigitalClockFaceSpec.SEGMENT_PATHS.mapValues { PathParser.createPathFromPathData(it.value) }
  private val segmentMatrix = Matrix()
  private val scratch = Path()
  private val rect = RectF()
  private val camera = Camera()
  private val cameraMatrix = Matrix()
  private var shown = ""
  private var previous = ""
  private var dateText = ""
  private var changedAt = 0L
  private var flipAnimator: ValueAnimator? = null
  private var sweepOn = false
  private var dial: Shader? = null
  private var dialKey = -1L

  /** Refresh the displayed time; called by the dream's minute (or second) tick. */
  fun update(now: Date = Date()) {
    val use24 = ImmortalSettings.use24HourClock(context)
    val locale = Locale.getDefault()
    val text = SimpleDateFormat(timePattern(use24), locale).format(now)
    val dateLine = if (settings.showDate && settings.layout != DigitalClockConfig.LAYOUT_MINIMAL) {
      SimpleDateFormat("EEEE '·' " + android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"),
          locale).format(now)
    } else ""
    contentDescription = "$text $dateLine".trim()
    if (face == Face.ANALOG) {
      dateText = dateLine
      invalidate()
      return
    }
    if (text == shown && dateLine == dateText) return
    if (face == Face.FLIP && shown.isNotEmpty() && text != shown) startFlip()
    previous = if (shown.isEmpty()) text else shown
    shown = text
    dateText = dateLine
    invalidate()
  }

  fun stop() {
    flipAnimator?.cancel()
    sweepOn = false
  }

  private fun timePattern(use24: Boolean): String = when {
    face == Face.FLIP || face == Face.LED -> if (use24) "HH:mm" else "hh:mm"
    settings.showSeconds && face != Face.ANALOG -> if (use24) "H:mm:ss" else "h:mm:ss"
    else -> if (use24) "H:mm" else "h:mm"
  }

  private fun startFlip() {
    flipAnimator?.cancel()
    changedAt = System.currentTimeMillis()
    if (!ValueAnimator.areAnimatorsEnabled()) return
    flipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = DigitalClockFaceSpec.FLIP_MS + 3 * DigitalClockFaceSpec.FLIP_STAGGER_MS
      addUpdateListener { invalidate() }
      start()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    sweepOn = face == Face.ANALOG && settings.showSeconds
    update()
  }

  override fun onDetachedFromWindow() {
    stop()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    val b = DigitalClockFaceSpec.backdrop(face, settings.background)
    backdrop.shader = if (b.radial) RadialGradient(w * .5f, h * .42f, maxOf(w, h) * .62f, b.inner, b.outer, Shader.TileMode.CLAMP)
    else LinearGradient(0f, 0f, w * .35f, h.toFloat(), b.inner, b.outer, Shader.TileMode.CLAMP)
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return
    canvas.drawRect(0f, 0f, w, h, backdrop)
    val cy = when (settings.layout) {
      DigitalClockConfig.LAYOUT_TOP -> h * .36f
      DigitalClockConfig.LAYOUT_BOTTOM -> h * .64f
      else -> h * .5f
    }
    when (face) {
      Face.ANALOG -> drawAnalog(canvas, w, h, cy)
      Face.FLIP -> drawFlip(canvas, w, h, cy)
      Face.LED -> drawLed(canvas, w, cy)
      else -> drawNumerals(canvas, w, cy)
    }
    if (sweepOn) postInvalidateDelayed(DigitalClockFaceSpec.SWEEP_FRAME_MS)
  }

  private fun sizePx(): Float = DigitalClockFaceSpec.timeSizeDp(settings.size) * density *
      (if (settings.layout == DigitalClockConfig.LAYOUT_MINIMAL) 1.2f else 1f)

  private fun drawNumerals(canvas: Canvas, w: Float, cy: Float) {
    time.textSize = sizePx()
    time.textSize = DigitalClockFaceSpec.fit(time.textSize, time.measureText(shown), w * .86f)
    // Mock: 38 dp serif date under 250 dp numerals; keep that ratio, then fit the width.
    date.textSize = (38f * density * time.textSize / (250f * density)).coerceAtLeast(16f * density)
    date.textSize = DigitalClockFaceSpec.fit(date.textSize, date.measureText(dateText), w * .86f)
    val timeH = time.descent() - time.ascent()
    val dateH = if (dateText.isEmpty()) 0f else date.descent() - date.ascent() + 18f * density
    val top = cy - (timeH + dateH) / 2f
    val baseline = top - time.ascent()
    if (face == Face.NEON) {
      // Warm ember tube: three glow layers (mock 60/18/4 px shadows) beneath the bright core.
      glow.set(time)
      glow.color = 0
      for ((radius, colour) in NEON_GLOW) {
        glow.setShadowLayer(radius * density, 0f, 0f, colour)
        canvas.drawText(shown, w / 2f, baseline, glow)
      }
    } else if (face == Face.BOLD) {
      time.setShadowLayer(10f * density, 0f, 4f * density, 0x99000000.toInt())
    }
    canvas.drawText(shown, w / 2f, baseline, time)
    if (dateText.isNotEmpty()) canvas.drawText(dateText, w / 2f, baseline + time.descent() + 18f * density - date.ascent(), date)
  }

  private fun drawLed(canvas: Canvas, w: Float, cy: Float) {
    // Each digit cell is 60x100 units; the colon slot is 40 units and digits are 14 units apart.
    var unit = sizePx() / 100f
    val units = 4 * 60f + 3 * 14f + 40f
    unit = DigitalClockFaceSpec.fit(unit, units * unit, w * .86f)
    val totalH = 100f * unit
    val dateH = if (dateText.isEmpty()) 0f else 50f * density
    var x = (w - units * unit) / 2f
    val y = cy - (totalH + dateH) / 2f
    fill.setShadowLayer(10f * density, 0f, 0f, LED_GLOW)
    for (c in shown) {
      if (c == ':') {
        fill.color = LED_ON
        val r = 5f * unit
        canvas.drawCircle(x + 20f * unit, y + 34f * unit, r, fill)
        canvas.drawCircle(x + 20f * unit, y + 66f * unit, r, fill)
        x += 40f * unit
        continue
      }
      val lit = DigitalClockFaceSpec.segments(c)
      segmentMatrix.setScale(unit, unit)
      segmentMatrix.postTranslate(x, y)
      for ((key, path) in segments) {
        scratch.reset()
        path.transform(segmentMatrix, scratch)
        val on = lit.indexOf(key) >= 0
        fill.color = if (on) LED_ON else LED_GHOST
        if (!on) fill.clearShadowLayer()
        canvas.drawPath(scratch, fill)
        if (!on) fill.setShadowLayer(10f * density, 0f, 0f, LED_GLOW)
      }
      x += (60f + 14f) * unit
    }
    fill.clearShadowLayer()
    if (dateText.isNotEmpty()) {
      date.textSize = DigitalClockFaceSpec.fit(30f * density, date.measureText(dateText), w * .86f)
      canvas.drawText(dateText, w / 2f, y + totalH + 40f * density, date)
    }
  }

  private fun drawFlip(canvas: Canvas, w: Float, h: Float, cy: Float) {
    val f = DigitalClockFaceSpec.flip(h * DigitalClockFaceSpec.flipHeightFraction(settings.size), w * .9f)
    val elapsed = if (flipAnimator?.isRunning == true) System.currentTimeMillis() - changedAt else Long.MAX_VALUE / 4
    val now = shown.replace(":", "")
    val old = previous.replace(":", "")
    val top = cy - f.cardH / 2f
    var x = (w - f.total) / 2f
    time.textSize = f.cardH * .7f
    for (i in 0 until 4) {
      if (i == 2) {
        fill.color = EMBER
        val r = f.cardH * .028f
        canvas.drawCircle(x - f.gap + f.colon / 2f, cy - f.cardH * .1f, r, fill)
        canvas.drawCircle(x - f.gap + f.colon / 2f, cy + f.cardH * .1f, r, fill)
        x += f.colon - f.gap
      }
      val nv = now.getOrElse(i) { ' ' }.toString()
      val ov = old.getOrElse(i) { ' ' }.toString()
      val p = if (nv != ov) DigitalClockFaceSpec.flipProgress(i, elapsed) else 1f
      drawCard(canvas, x, top, f, nv, ov, p)
      x += f.cardW + f.gap
    }
  }

  /** Card art cached per size: drawing it per frame (gradients, blurred shadow) janked the flip. */
  private class CardArt(val w: Int, val h: Int, val shadow: android.graphics.Bitmap, val pad: Int,
      val top: android.graphics.Bitmap, val bottom: android.graphics.Bitmap, val seam: Shader)
  private var art: CardArt? = null

  private fun cardArt(f: DigitalClockFaceSpec.Flip): CardArt {
    val w = f.cardW.toInt().coerceAtLeast(1)
    val h = f.cardH.toInt().coerceAtLeast(2)
    art?.let { if (it.w == w && it.h == h) return it }
    val pad = (40f * density).toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val shadow = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
    Canvas(shadow).apply {
      paint.color = 0
      paint.setShadowLayer(26f * density, 0f, 14f * density, 0xB3000000.toInt())
      drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), f.radius, f.radius, paint)
      paint.clearShadowLayer()
    }
    fun halfBitmap(top: Boolean): android.graphics.Bitmap {
      val b = android.graphics.Bitmap.createBitmap(w, h / 2, android.graphics.Bitmap.Config.ARGB_8888)
      val c = Canvas(b)
      paint.shader = LinearGradient(0f, 0f, 0f, h / 2f, if (top) CARD_TOP_A else CARD_BOTTOM_A,
          if (top) CARD_TOP_B else CARD_BOTTOM_B, Shader.TileMode.CLAMP)
      // Round only the outer corners: draw the full card offset so the cut edge is square.
      c.drawRoundRect(RectF(0f, if (top) 0f else -h / 2f, w.toFloat(), if (top) h.toFloat() else h / 2f),
          f.radius, f.radius, paint)
      paint.shader = null
      return b
    }
    val seam = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(SEAM_EDGE, SEAM_GLOW, SEAM_EDGE),
        floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    return CardArt(w, h, shadow, pad, halfBitmap(true), halfBitmap(false), seam).also { art = it }
  }

  /** One split-flap card: static halves, then the top flap falls and the bottom flap lands. */
  private fun drawCard(canvas: Canvas, x: Float, y: Float, f: DigitalClockFaceSpec.Flip, nv: String, ov: String, p: Float) {
    val a = cardArt(f)
    val mid = y + f.cardH / 2f
    canvas.drawBitmap(a.shadow, x - a.pad, y - a.pad, null)
    half(canvas, a, x, y, f, true, nv)                       // new digit's top is revealed beneath
    half(canvas, a, x, y, f, false, if (p >= 1f) nv else ov) // old bottom until the flap lands
    if (p < 1f) {
      val t1 = (p / .5f).coerceIn(0f, 1f)
      val t2 = ((p - .5f) / .5f).coerceIn(0f, 1f)
      if (t1 < 1f) flap(canvas, a, x, y, f, true, ov, -90f * t1 * t1, t1 * t1 * .9f)
      if (p > .5f) flap(canvas, a, x, y, f, false, nv, 90f - 90f * BOUNCE.getInterpolation(t2), (1f - t2) * .9f)
    }
    val save = canvas.save()
    canvas.translate(x, 0f)
    stroke.shader = a.seam
    stroke.strokeWidth = maxOf(2f, 2f * density)
    stroke.strokeCap = Paint.Cap.BUTT
    canvas.drawLine(0f, mid, f.cardW, mid, stroke)
    stroke.shader = null
    canvas.restoreToCount(save)
  }

  private fun half(canvas: Canvas, a: CardArt, x: Float, y: Float, f: DigitalClockFaceSpec.Flip, top: Boolean, digit: String) {
    val mid = y + f.cardH / 2f
    canvas.drawBitmap(if (top) a.top else a.bottom, x, if (top) y else mid, null)
    val save = canvas.save()
    canvas.clipRect(x, if (top) y else mid, x + f.cardW, if (top) mid else y + f.cardH)
    time.color = INK_FLIP
    canvas.drawText(digit, x + f.cardW / 2f, mid - (time.ascent() + time.descent()) / 2f, time)
    canvas.restoreToCount(save)
  }

  private fun flap(canvas: Canvas, a: CardArt, x: Float, y: Float, f: DigitalClockFaceSpec.Flip, top: Boolean,
      digit: String, degrees: Float, shade: Float) {
    val mid = y + f.cardH / 2f
    val save = canvas.save()
    camera.save()
    camera.setLocation(0f, 0f, -f.cardH * 5f / 72f)
    camera.rotateX(-degrees)
    camera.getMatrix(cameraMatrix)
    camera.restore()
    cameraMatrix.preTranslate(-(x + f.cardW / 2f), -mid)
    cameraMatrix.postTranslate(x + f.cardW / 2f, mid)
    canvas.concat(cameraMatrix)
    half(canvas, a, x, y, f, top, digit)
    fill.color = if (top) ((shade * 140).toInt() shl 24) else ((shade * 100).toInt() shl 24)
    canvas.drawRect(x, if (top) y else mid, x + f.cardW, if (top) mid else y + f.cardH, fill)
    canvas.restoreToCount(save)
  }

  private fun drawAnalog(canvas: Canvas, w: Float, h: Float, cy: Float) {
    val dateRoom = if (dateText.isEmpty()) 0f else 70f * density
    val r = minOf(w * .42f, (h - dateRoom) * .42f) * (DigitalClockFaceSpec.timeSizeDp(settings.size) / 250f).coerceIn(.7f, 1.1f)
    val cx = w / 2f
    val dy = cy - dateRoom / 2f
    val key = (cx.toInt().toLong() shl 40) or (dy.toInt().toLong() shl 20) or r.toInt().toLong()
    if (dialKey != key) {
      dial = RadialGradient(cx, dy - r * .2f, r, DIAL_CENTRE, DIAL_EDGE, Shader.TileMode.CLAMP)
      dialKey = key
    }
    fill.shader = dial
    canvas.drawCircle(cx, dy, r, fill)
    fill.shader = null
    stroke.strokeCap = Paint.Cap.ROUND
    stroke.color = DIAL_RIM
    stroke.strokeWidth = r / 96f
    canvas.drawCircle(cx, dy, r, stroke)
    for (i in 0 until 60) {
      val major = i % 5 == 0
      val a = Math.toRadians(i * 6.0)
      val outer = r * (if (major) 92f else 91f) / 96f
      val inner = r * (if (major) 83f else 88f) / 96f
      stroke.color = if (major) TICK_MAJOR else TICK_MINOR
      stroke.strokeWidth = r * (if (major) 1.6f else .6f) / 96f
      val s = Math.sin(a).toFloat()
      val c = Math.cos(a).toFloat()
      canvas.drawLine(cx + s * inner, dy - c * inner, cx + s * outer, dy - c * outer, stroke)
    }
    date.color = NUMERAL
    date.textSize = r * 16f / 96f
    for ((n, deg) in listOf(12 to 0.0, 3 to 90.0, 6 to 180.0, 9 to 270.0)) {
      val a = Math.toRadians(deg)
      val rr = r * .68f
      canvas.drawText(n.toString(), cx + Math.sin(a).toFloat() * rr,
          dy - Math.cos(a).toFloat() * rr - (date.ascent() + date.descent()) / 2f, date)
    }
    val cal = Calendar.getInstance()
    val angles = DigitalClockFaceSpec.handAngles(cal.get(Calendar.HOUR), cal.get(Calendar.MINUTE),
        cal.get(Calendar.SECOND), if (settings.showSeconds) cal.get(Calendar.MILLISECOND) else 0)
    hand(canvas, cx, dy, angles[0], r * .52f, r * 10f / 280f * 2f, HAND)
    hand(canvas, cx, dy, angles[1], r * .76f, r * 6f / 280f * 2f, HAND)
    if (settings.showSeconds) hand(canvas, cx, dy, angles[2], r * .84f, maxOf(1.5f, r * 2f / 280f * 2f), EMBER_DEEP)
    fill.color = EMBER_DEEP
    fill.setShadowLayer(12f * density, 0f, 0f, EMBER)
    canvas.drawCircle(cx, dy, r * 8f / 280f * 2f, fill)
    fill.clearShadowLayer()
    if (dateText.isNotEmpty()) {
      date.color = DATE_INK
      date.textSize = DigitalClockFaceSpec.fit(34f * density, date.measureText(dateText), w * .86f)
      canvas.drawText(dateText, cx, dy + r + 56f * density, date)
    }
  }

  private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, width: Float, colour: Int) {
    val a = Math.toRadians(degrees.toDouble())
    stroke.color = colour
    stroke.strokeWidth = width
    stroke.strokeCap = Paint.Cap.ROUND
    canvas.drawLine(cx, cy, cx + Math.sin(a).toFloat() * length, cy - Math.cos(a).toFloat() * length, stroke)
  }

  private companion object {
    const val INK = 0xFFF6EEE4.toInt()
    const val INK_FLIP = 0xFFF4EEE6.toInt()
    const val NEON_INK = 0xFFFFE2C2.toInt()
    const val DATE_INK = 0xB3F0C8A0.toInt()
    // Shadow colours must be translucent: an opaque one inherits the (transparent) paint alpha.
    val NEON_GLOW = listOf(30f to 0xCCD9773A.toInt(), 9f to 0xE6F0A65A.toInt(), 2f to 0xF2FFB27A.toInt())
    const val LED_ON = 0xFFFF5A3C.toInt()
    const val LED_GHOST = 0x12FF5A3C
    const val LED_GLOW = 0x99FF5A3C.toInt()
    const val EMBER = 0xFFF0A65A.toInt()
    const val EMBER_DEEP = 0xFFD9773A.toInt()
    const val CARD_TOP_A = 0xFF34302C.toInt()
    const val CARD_TOP_B = 0xFF2A2724.toInt()
    const val CARD_BOTTOM_A = 0xFF262320.toInt()
    const val CARD_BOTTOM_B = 0xFF1F1C1A.toInt()
    const val SEAM_EDGE = 0xFF0A0807.toInt()
    const val SEAM_GLOW = 0x8CD9773A.toInt()
    const val DIAL_CENTRE = 0xFF2A231D.toInt()
    const val DIAL_EDGE = 0xFF14100D.toInt()
    const val DIAL_RIM = 0xFF3A3029.toInt()
    const val TICK_MAJOR = 0xFFD8C7B4.toInt()
    const val TICK_MINOR = 0xFF6B5A4C.toInt()
    const val NUMERAL = 0xFFE9DCCD.toInt()
    const val HAND = 0xFFEFE4D6.toInt()
    val BOUNCE = CubicEase(.3f, 1.45f, .6f, 1f)
  }
}
