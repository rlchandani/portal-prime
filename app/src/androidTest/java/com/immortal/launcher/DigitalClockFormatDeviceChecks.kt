/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.immortal.launcher.ui.clock.PrimeDigitalClockFaceView
import java.io.File
import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Detached, real Canvas clock fixtures. Only generated text and owned PNG/JSON files leave RAM. */
internal object DigitalClockFormatDeviceChecks {
  private data class Case(
      val name: String,
      val spec: ClockSpec,
      val expected: String,
      val hour: Int = 1,
      val second: Int = 20,
      val blinkOn: Boolean = true,
      val accessible: String = expected,
  )

  private data class Drawing(val bitmap: Bitmap, val geometry: JSONObject)

  fun run(instrumentation: Instrumentation): Bundle {
    val checks = JSONArray()
    val artifacts = JSONArray()
    val renders = JSONArray()
    var failed = 0
    var isolatedFailures = 0
    var casesRun = 0
    val expectedRenderCount = 7
    val prefix = "digital-clock-format-owned-${UUID.randomUUID()}"
    val directory = instrumentation.targetContext.cacheDir
    val reportFile = File(directory, "$prefix.json")

    fun expect(name: String, condition: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", condition))
      if (!condition) failed++
    }
    fun isolated(name: String, block: () -> Unit) {
      try { block() } catch (_: Throwable) {
        // Fixed fixture labels only: never include exception messages or diagnostics.
        isolatedFailures++
        expect("$name.isolated_failure", false)
      }
    }
    fun <T> main(block: () -> T): T {
      var value: T? = null
      var error: Throwable? = null
      instrumentation.runOnMainSync {
        try { value = block() } catch (failure: Throwable) { error = failure }
      }
      error?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return value as T
    }

    val locale = Locale.getDefault()
    val english = locale.language == "en" &&
        DecimalFormatSymbols.getInstance(locale).zeroDigit == '0' &&
        DateFormatSymbols.getInstance(locale).amPmStrings.contentEquals(arrayOf("AM", "PM"))
    expect("preflight.english_locale_and_literal_meridiem", english)
    expect("preflight.worker_off_main", Looper.myLooper() != Looper.getMainLooper())

    // Literal expectations, independent of the production formatter and ClockMath.
    fun base(name: String, format: String, zero: Boolean, separator: Separator, text: String) =
        Case(name, ClockSpec(format = format, leadingZero = zero, separator = separator,
            shadow = Shadow.NONE), text)
    val cases = listOf(
        base("12_colon_plain", "12h", false, Separator.COLON, "1:34"),
        base("12_dot_plain", "12h", false, Separator.DOT, "1.34"),
        base("12_none_plain", "12h", false, Separator.NONE, "134"),
        base("12_blink_plain", "12h", false, Separator.BLINK, "1:34"),
        base("12_colon_zero", "12h", true, Separator.COLON, "01:34"),
        base("12_dot_zero", "12h", true, Separator.DOT, "01.34"),
        base("12_none_zero", "12h", true, Separator.NONE, "0134"),
        base("12_blink_zero", "12h", true, Separator.BLINK, "01:34"),
        base("24_colon_plain", "24h", false, Separator.COLON, "1:34"),
        base("24_dot_plain", "24h", false, Separator.DOT, "1.34"),
        base("24_none_plain", "24h", false, Separator.NONE, "134"),
        base("24_blink_plain", "24h", false, Separator.BLINK, "1:34"),
        base("24_colon_zero", "24h", true, Separator.COLON, "01:34"),
        base("24_dot_zero", "24h", true, Separator.DOT, "01.34"),
        base("24_none_zero", "24h", true, Separator.NONE, "0134"),
        base("24_blink_zero", "24h", true, Separator.BLINK, "01:34"),
        Case("am_with_suffix", ClockSpec(leadingZero = true, showAmPm = true,
            shadow = Shadow.NONE), "01:34 AM"),
        Case("am_without_suffix", ClockSpec(leadingZero = true, showAmPm = false,
            shadow = Shadow.NONE), "01:34"),
        Case("pm_with_suffix", ClockSpec(leadingZero = true, showAmPm = true,
            shadow = Shadow.NONE), "01:34 PM", hour = 13),
        Case("pm_without_suffix", ClockSpec(showAmPm = false, shadow = Shadow.NONE),
            "1:34", hour = 13),
        Case("24_pm_without_suffix", ClockSpec(format = "24h", shadow = Shadow.NONE),
            "13:34", hour = 13),
        Case("24_pm_with_suffix", ClockSpec(format = "24h", showAmPm = true,
            shadow = Shadow.NONE), "13:34 PM", hour = 13),
        Case("blink_off_pm", ClockSpec(leadingZero = true, showAmPm = true,
            separator = Separator.BLINK, shadow = Shadow.NONE), "01 34 PM",
            hour = 13, blinkOn = false, accessible = "01:34 PM"),
        Case("seconds_arc_20", ClockSpec(leadingZero = true, showSeconds = true,
            shadow = Shadow.NONE), "01:34"),
    )

    fun clock(case: Case): PrimeDigitalClockFaceView = PrimeDigitalClockFaceView(
        instrumentation.targetContext, case.spec, AssetResolver(instrumentation.targetContext))

    if (english && Looper.myLooper() != Looper.getMainLooper()) {
      for (case in cases) isolated(case.name) {
        main {
          expect("${case.name}.on_main", Looper.myLooper() == Looper.getMainLooper())
          val face = clock(case)
          face.update(localTime(case.hour, case.second), case.blinkOn)
          expect("${case.name}.displayed_literal", displayed(face) == case.expected)
          expect("${case.name}.raw_accessibility_literal",
              face.view.contentDescription?.toString() == "Clock ${case.accessible}")
          expect("${case.name}.seconds_remain_arc",
              field(face, "currentSeconds") == if (case.spec.showSeconds) case.second else 0)
          casesRun++
        }
      }

      val renderCases = listOf(
          Triple(cases.first { it.name == "12_colon_zero" }, 960, 480),
          Triple(cases.first { it.name == "12_dot_plain" }, 960, 480),
          Triple(cases.first { it.name == "12_none_zero" }, 960, 480),
          Triple(cases.first { it.name == "blink_off_pm" }, 960, 480),
          Triple(cases.first { it.name == "pm_with_suffix" }.let {
            it.copy(name = "narrow_portrait_pm", spec = it.spec.copy(sizeScale = 500))
          }, 240, 600),
          Triple(cases.first { it.name == "seconds_arc_20" }, 960, 480),
          Triple(cases.first { it.name == "pm_with_suffix" }.let {
            it.copy(name = "seconds_arc_narrow_pm_20",
                spec = it.spec.copy(showSeconds = true, sizeScale = 500))
          }, 240, 600),
      )
      for ((case, parentWidth, parentHeight) in renderCases) isolated("render.${case.name}") {
        val drawing = main {
          val face = clock(case)
          face.update(localTime(case.hour, case.second), case.blinkOn)
          val view = face.view
          measure(view, parentWidth, parentHeight)
          val bounded = view.width in 1..parentWidth && view.height in 1..parentHeight
          expect("render.${case.name}.at_most_parent", bounded)
          check(bounded)
          val bitmap = draw(view)
          try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val all = bounds(pixels, bitmap.width) { Color.alpha(it) > 0 }
            val glyph = bounds(pixels, bitmap.width, ::isGlyph)
            val glyphCount = pixels.count(::isGlyph)
            val geometry = JSONObject().put("name", case.name)
                .put("parentWidth", parentWidth).put("parentHeight", parentHeight)
                .put("viewWidth", bitmap.width).put("viewHeight", bitmap.height)
                .put("glyphPixels", glyphCount).put("glyphBounds", rectJson(glyph))
                .put("allPixelBounds", rectJson(all))
            expect("render.${case.name}.glyph_pixels_present", glyphCount > 20 && glyph != null)
            expect("render.${case.name}.full_canvas_margin_2px", all != null &&
                all.left >= 2 && all.top >= 2 &&
                bitmap.width - all.right >= 2 && bitmap.height - all.bottom >= 2)
            if (!case.spec.showSeconds) {
              expect("render.${case.name}.literal_canvas_glyphs",
                  matchesLiteral(face, bitmap, case.expected))
            } else {
              // Hide paints only: retain the actual view's layout, font and draw geometry.
              // This isolates the full ring, including its translucent track and fringe.
              val textPixels = pixelsWithout(face, "arcTrackPaint", "arcFillPaint")
              val arcPixels = pixelsWithout(face, "timePaint")
              val textBounds = bounds(textPixels, bitmap.width) { Color.alpha(it) > 0 }
              val arcBounds = bounds(arcPixels, bitmap.width) { Color.alpha(it) > 0 }
              val blueBounds = bounds(pixels, bitmap.width, ::isBlueFill)
              val bluePixels = pixels.count(::isBlueFill)
              expect("render.${case.name}.seconds_arc_pixels_present", bluePixels > 0)
              expect("render.${case.name}.full_indicator_right_of_text_gap_2px",
                  separated(textBounds, arcBounds))
              expect("render.${case.name}.blue_fill_right_of_text_gap_2px",
                  separated(textBounds, blueBounds))
              val firstWidth = view.measuredWidth
              val firstHeight = view.measuredHeight
              val firstDescription = view.contentDescription?.toString()
              face.update(localTime(case.hour, case.second + 1), case.blinkOn)
              expect("render.${case.name}.seconds_do_not_enter_text",
                  displayed(face) == case.expected)
              expect("render.${case.name}.seconds_accessibility_unchanged",
                  firstDescription == "Clock ${case.accessible}" &&
                      view.contentDescription?.toString() == firstDescription)
              view.forceLayout()
              measure(view, parentWidth, parentHeight)
              val stable = view.measuredWidth == firstWidth && view.measuredHeight == firstHeight
              expect("render.${case.name}.seconds_measured_dimensions_stable", stable)
              check(stable)
              val next = draw(view)
              try {
                val nextPixels = IntArray(pixels.size)
                next.getPixels(nextPixels, 0, next.width, 0, 0, next.width, next.height)
                val nextTextPixels = pixelsWithout(face, "arcTrackPaint", "arcFillPaint")
                val nextArcPixels = pixelsWithout(face, "timePaint")
                val nextTextBounds = bounds(nextTextPixels, next.width) { Color.alpha(it) > 0 }
                val nextArcBounds = bounds(nextArcPixels, next.width) { Color.alpha(it) > 0 }
                val nextBlueBounds = bounds(nextPixels, next.width, ::isBlueFill)
                expect("render.${case.name}.next_full_indicator_right_of_text_gap_2px",
                    separated(nextTextBounds, nextArcBounds))
                expect("render.${case.name}.next_blue_fill_right_of_text_gap_2px",
                    separated(nextTextBounds, nextBlueBounds))
                expect("render.${case.name}.time_glyph_pixels_unchanged",
                    textPixels.contentEquals(nextTextPixels))
                expect("render.${case.name}.arc_changes_with_second",
                    !arcPixels.contentEquals(nextArcPixels))
                expect("render.${case.name}.only_indicator_pixels_change",
                    !pixels.contentEquals(nextPixels) && pixels.indices.all { index ->
                      pixels[index] == nextPixels[index] ||
                          Color.alpha(arcPixels[index]) > 0 || Color.alpha(nextArcPixels[index]) > 0
                    })
                geometry.put("timeOnlyBounds", rectJson(textBounds))
                    .put("indicatorOnlyBounds", rectJson(arcBounds))
                    .put("blueFillBounds", rectJson(blueBounds))
                    .put("indicatorGapPixels", gap(textBounds, arcBounds))
                    .put("nextSecond", case.second + 1)
                    .put("nextTimeOnlyBounds", rectJson(nextTextBounds))
                    .put("nextIndicatorOnlyBounds", rectJson(nextArcBounds))
                    .put("nextBlueFillBounds", rectJson(nextBlueBounds))
                    .put("nextIndicatorGapPixels", gap(nextTextBounds, nextArcBounds))
              } finally { next.recycle() }
            }
            Drawing(bitmap, geometry)
          } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
          }
        }
        try {
          val file = File(directory, "$prefix-${case.name}.png")
          file.outputStream().use { output ->
            check(drawing.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
          }
          artifacts.put(file.absolutePath)
          drawing.geometry.put("path", file.absolutePath)
          renders.put(drawing.geometry)
          expect("render.${case.name}.owned_png_written", file.length() > 0)
        } finally { drawing.bitmap.recycle() }
      }

      isolated("retained_blink") {
        main {
          val case = cases.first { it.name == "blink_off_pm" }
          val face = clock(case)
          val instant = localTime(13, 20)
          face.update(instant, true)
          measure(face.view, 240, 600)
          val firstWidth = face.view.measuredWidth
          val firstHeight = face.view.measuredHeight
          val description = face.view.contentDescription?.toString()
          face.update(instant, false)
          expect("retained_blink.off_literal", displayed(face) == "01 34 PM")
          expect("retained_blink.raw_accessibility_stable",
              description == "Clock 01:34 PM" && face.view.contentDescription?.toString() == description)
          face.view.forceLayout()
          measure(face.view, 240, 600)
          expect("retained_blink.stable_measured_dimensions",
              face.view.measuredWidth == firstWidth && face.view.measuredHeight == firstHeight)
        }
      }
    }

    val report = JSONObject().put("checks", checks).put("renders", renders)
        .put("caseCount", casesRun).put("checkCount", checks.length())
        .put("failureCount", failed).put("isolatedFailureCount", isolatedFailures)
        .put("artifacts", artifacts)
        .put("passed", failed == 0 && casesRun == cases.size && renders.length() == expectedRenderCount)
        .put("relativeReportPath", "cache/${reportFile.name}")
    isolated("owned_json") {
      reportFile.writeText(report.toString(2))
      check(reportFile.length() > 0)
    }
    return Bundle().apply {
      putBoolean("digitalClockFormatCompleted", true)
      putBoolean("digitalClockFormatPassed",
          failed == 0 && casesRun == cases.size && renders.length() == expectedRenderCount)
      putString("digitalClockFormatChecksJson", checks.toString())
      putInt("digitalClockFormatCheckCount", checks.length())
      putInt("digitalClockFormatCaseCount", casesRun)
      putInt("digitalClockFormatFailureCount", failed)
      putInt("digitalClockFormatIsolatedFailureCount", isolatedFailures)
      putString("digitalClockFormatReportPath", reportFile.absolutePath)
      putString("digitalClockFormatReportRelativePath", "cache/${reportFile.name}")
      putString("digitalClockFormatArtifactPaths", artifacts.toString())
    }
  }

  /** Device-default locale/timezone are read, never replaced. */
  private fun localTime(hour: Int, second: Int): Date =
      Calendar.getInstance().apply {
        clear()
        set(2030, Calendar.JANUARY, 1, hour, 34, second)
      }.time

  private fun field(face: PrimeDigitalClockFaceView, name: String): Any? =
      PrimeDigitalClockFaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(face)

  private fun displayed(face: PrimeDigitalClockFaceView): String = field(face, "currentHourMin") as String

  private fun measure(view: View, width: Int, height: Int) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
  }

  private fun draw(view: View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
      view.draw(Canvas(bitmap))
      return bitmap
    } catch (failure: Throwable) {
      bitmap.recycle()
      throw failure
    }
  }

  private fun isGlyph(color: Int): Boolean = Color.alpha(color) > 128 &&
      Color.red(color) > 220 && Color.green(color) > 220 && Color.blue(color) > 220

  private fun isBlueFill(color: Int): Boolean = Color.alpha(color) > 128 &&
      Color.blue(color) > Color.red(color) + 40

  /** Render actual paint layers separately, without copying any production layout calculations. */
  private fun pixelsWithout(face: PrimeDigitalClockFaceView, vararg paintNames: String): IntArray {
    val paints = paintNames.map { field(face, it) as Paint }
    val alphas = paints.map { it.alpha }
    try {
      paints.forEach { it.alpha = 0 }
      val bitmap = draw(face.view)
      try {
        return IntArray(bitmap.width * bitmap.height).also {
          bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
      } finally { bitmap.recycle() }
    } finally {
      paints.forEachIndexed { index, paint -> paint.alpha = alphas[index] }
    }
  }

  private fun separated(text: Rect?, indicator: Rect?): Boolean =
      text != null && indicator != null && indicator.left - text.right >= 2

  private fun gap(text: Rect?, indicator: Rect?): Any =
      if (text == null || indicator == null) JSONObject.NULL else indicator.left - text.right

  private fun bounds(pixels: IntArray, width: Int, matches: (Int) -> Boolean): Rect? {
    var left = width
    var top = pixels.size / width
    var right = -1
    var bottom = -1
    pixels.forEachIndexed { index, color ->
      if (matches(color)) {
        val x = index % width
        val y = index / width
        left = minOf(left, x); top = minOf(top, y)
        right = maxOf(right, x); bottom = maxOf(bottom, y)
      }
    }
    return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
  }

  private fun rectJson(rect: Rect?): Any = rect?.let {
    JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom)
  } ?: JSONObject.NULL

  /** Compare actual text pixels to an independently supplied literal, using its actual Android paint. */
  private fun matchesLiteral(face: PrimeDigitalClockFaceView, actual: Bitmap, literal: String): Boolean {
    val paint = Paint(field(face, "timePaint") as Paint)
    val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
    try {
      val metrics = paint.fontMetrics
      val padding = minOf(2f * face.view.resources.displayMetrics.density,
          actual.width / 2f, actual.height / 2f)
      val baseline = (actual.height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
      Canvas(expected).drawText(literal, padding, baseline, paint)
      return actual.sameAs(expected)
    } finally { expected.recycle() }
  }
}
