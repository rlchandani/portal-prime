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
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.immortal.launcher.ui.clock.PrimeDigitalClockFaceView
import java.io.File
import java.security.MessageDigest
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Real detached Canvas/font fixtures: no window, live clock, provider, audio or FPS proof. */
internal object DigitalClockFontDeviceChecks {
  private const val RUN_MS = 15_000L
  private const val LITERAL = "08:34"
  private data class Drawing(val images: List<Pair<String, Bitmap>>, val geometry: JSONObject) {
    fun dispose() = images.forEach { if (!it.second.isRecycled) it.second.recycle() }
  }

  fun run(probe: Instrumentation): Bundle {
    val began = SystemClock.elapsedRealtime()
    val deadline = began + RUN_MS
    val prefix = "digital-clock-font-owned-${UUID.randomUUID()}"
    val reportFile = File(probe.targetContext.cacheDir, "$prefix.json")
    val handler = Handler(Looper.getMainLooper())
    val checks = JSONArray()
    val artifacts = JSONArray()
    val renders = JSONArray()
    var failures = 0
    var isolatedFailures = 0
    var casesRun = 0
    fun expect(name: String, condition: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", condition))
      if (!condition) failures++
    }
    fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun <T> main(dispose: (T) -> Unit = {}, block: () -> T): T {
      val done = CountDownLatch(1)
      val lock = Any()
      var abandoned = false
      var value: T? = null
      var failure: Throwable? = null
      check(remaining() > 0)
      handler.post {
        if (synchronized(lock) { abandoned } || SystemClock.elapsedRealtime() >= deadline) {
          done.countDown()
        } else {
          try {
            val result = block()
            synchronized(lock) {
              if (abandoned) dispose(result) else value = result
            }
          } catch (thrown: Throwable) {
            synchronized(lock) { if (!abandoned) failure = thrown }
          } finally { done.countDown() }
        }
      }
      if (!done.await(minOf(2_000L, remaining()), TimeUnit.MILLISECONDS)) {
        synchronized(lock) {
          abandoned = true
          value?.let(dispose)
          value = null
        }
        error("detached_main_timeout")
      }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return value as T
    }
    fun isolated(name: String, block: () -> Unit) {
      try { check(remaining() > 0); block() } catch (_: Throwable) {
        isolatedFailures++
        expect("$name.isolated_failure", false)
      }
    }
    val context = probe.targetContext
    val nativeWeights = Build.VERSION.SDK_INT >= 28
    val englishDigits = DecimalFormatSymbols.getInstance(Locale.getDefault()).zeroDigit == '0'
    expect("preflight.worker_off_main", Looper.myLooper() != Looper.getMainLooper())
    expect("preflight.literal_ascii_digits", englishDigits)
    expect("preflight.native_weight_api_28", nativeWeights)
    val families = listOf(Face.FONT_SANS_LIGHT, "Courier New", "Georgia", "Bebas Neue")
    fun reference(name: String, weight: Int): Typeface {
      val base = when (name) {
        Face.FONT_SANS_LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        "Courier New" -> Typeface.MONOSPACE
        "Georgia" -> Typeface.SERIF
        "Bebas Neue" -> Typeface.createFromAsset(context.assets, "fonts/BebasNeue.ttf")
        else -> Typeface.SANS_SERIF
      }
      return if (nativeWeights) Typeface.create(base, weight, false)
      else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
    val hashes = mutableMapOf<String, String>()
    val referenceHashes = mutableMapOf<String, String>()
    if (Looper.myLooper() != Looper.getMainLooper() && nativeWeights && englishDigits) {
      // Fresh resolver for each order; the clock subsequently uses a retained shared resolver.
      for (name in families + "Generated Missing Clock Font" + ClockSpec().font) {
        for (order in listOf(listOf(400, 700), listOf(700, 400))) isolated("cache.$name.${order[0]}") {
          main {
            val resolver = AssetResolver(context)
            val first = resolver.font(name, order[0])
            val second = resolver.font(name, order[1])
            for ((weight, actual) in listOf(order[0] to first, order[1] to second)) {
              val expected = reference(name, weight)
              expect("cache.$name.${order[0]}.$weight.native_weight", actual.weight == expected.weight)
              expect("cache.$name.${order[0]}.$weight.independent_typeface", actual == expected)
              expect("cache.$name.${order[0]}.$weight.exact_pair_reused",
                  resolver.font(name, weight) === actual)
            }
            expect("cache.$name.${order[0]}.weights_are_distinct", first !== second)
          }
        }
      }
      isolated("default_font_200") {
        main {
          val actual = AssetResolver(context).font(ClockSpec().font, ClockSpec().fontWeight)
          val expected = reference(ClockSpec().font, 200)
          expect("default_font_200.resolver_native_weight", actual.weight == expected.weight)
          expect("default_font_200.resolver_independent_typeface", actual == expected)
        }
      }
      isolated("imported_weight_bounds") {
        main {
          val resolver = AssetResolver(context)
          for (font in families) for ((raw, normalized) in listOf(-50 to 1, 5_000 to 1_000)) {
            val actual = resolver.font(font, raw)
            val expected = reference(font, normalized)
            expect("imported_weight.$font.$raw.native_boundary_weight", actual.weight == expected.weight)
            expect("imported_weight.$font.$raw.independent_typeface", actual == expected)
            expect("imported_weight.$font.$raw.normalized_pair_reused",
                resolver.font(font, normalized) === actual)
          }
        }
      }
      val resolver = AssetResolver(context)
      val fixtureFonts = families.flatMap { name -> listOf(name to 400, name to 700) } +
          (ClockSpec().font to 200) + (Face.FONT_SANS_LIGHT to 200)
      for ((font, weight) in fixtureFonts) {
        for ((shape, width, height) in listOf(Triple("wide", 960, 480), Triple("narrow", 240, 600))) {
          val name = "${font.replace(Regex("[^a-zA-Z0-9]"), "_")}-$weight-$shape"
          isolated("render.$name") {
            val drawing = main(dispose = { it: Drawing -> it.dispose() }) {
              val spec = ClockSpec(font = font, fontWeight = weight, format = "24h",
                  leadingZero = true, showSeconds = true, color = "#ffffff",
                  opacity = 1f, shadow = Shadow.NONE, sizeScale = 500)
              val face = PrimeDigitalClockFaceView(context, spec, resolver)
              val bitmaps = mutableListOf<Pair<String, Bitmap>>()
              try {
                face.update(Calendar.getInstance().apply {
                  clear(); set(2030, Calendar.JANUARY, 1, 8, 34, 20)
                }.time, true)
                val view = face.view
                expect("render.$name.detached", !view.isAttachedToWindow && view.parent == null)
                expect("render.$name.literal", field(face, "currentHourMin") == LITERAL)
                val expected = reference(font, weight)
                val paint = field(face, "timePaint") as Paint
                val actualTypeface = checkNotNull(paint.typeface)
                expect("render.$name.independent_typeface", actualTypeface == expected)
                expect("render.$name.native_weight", actualTypeface.weight == expected.weight)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                expect("render.$name.bounded_dimensions",
                    view.width in 1..width && view.height in 1..height)
                check(view.width in 1..width && view.height in 1..height)
                val actual = draw(view).also { bitmaps.add("actual" to it) }
                val text = without(face, "arcTrackPaint", "arcFillPaint")
                    .also { bitmaps.add("glyph" to it) }
                val arc = without(face, "timePaint")
                val arcBounds = try { bounds(arc) } finally { arc.recycle() }
                val textBounds = bounds(text)
                val allBounds = bounds(actual)
                expect("render.$name.glyph_present", textBounds != null)
                expect("render.$name.arc_present", arcBounds != null)
                expect("render.$name.glyph_arc_gap_2px",
                    textBounds != null && arcBounds != null && arcBounds.left - textBounds.right >= 2)
                expect("render.$name.all_pixels_margin_2px", allBounds != null &&
                    allBounds.left >= 2 && allBounds.top >= 2 &&
                    actual.width - allBounds.right >= 2 && actual.height - allBounds.bottom >= 2)
                // Independent native typeface at the actual Canvas text size and baseline.
                val referenceBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    .also { bitmaps.add("reference" to it) }
                val metrics = paint.fontMetrics
                val baseline = (view.height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
                val padding = minOf(2f * view.resources.displayMetrics.density,
                    view.width / 2f, view.height / 2f)
                val referencePaint = Paint(paint).apply {
                  typeface = expected; color = Color.WHITE; clearShadowLayer()
                }
                Canvas(referenceBitmap).drawText(LITERAL, padding, baseline, referencePaint)
                expect("render.$name.literal_independent_font_pixels", text.sameAs(referenceBitmap))
                // Fixed-size glyph masks compare families/weights without responsive size changes.
                val mask = fixedMask(actualTypeface)
                val refMask = fixedMask(expected)
                try {
                  hashes["$font:$weight"] = digest(mask)
                  referenceHashes["$font:$weight"] = digest(refMask)
                  expect("render.$name.fixed_glyph_reference_pixels", mask.sameAs(refMask))
                } finally { mask.recycle(); refMask.recycle() }
                val geometry = JSONObject().put("name", name).put("font", font)
                    .put("fontWeight", weight).put("nativeWeight", actualTypeface.weight)
                    .put("referenceWeight", expected.weight).put("shape", shape)
                    .put("showSeconds", true).put("literal", LITERAL)
                    .put("parentWidth", width).put("parentHeight", height)
                    .put("viewWidth", view.width).put("viewHeight", view.height)
                    .put("glyphBounds", rectJson(textBounds)).put("indicatorOnlyBounds", rectJson(arcBounds))
                    .put("allPixelBounds", rectJson(allBounds)).put("glyphMaskSha256", hashes["$font:$weight"])
                    .put("referenceMaskSha256", referenceHashes["$font:$weight"])
                Drawing(bitmaps.toList(), geometry)
              } catch (failure: Throwable) {
                bitmaps.forEach { if (!it.second.isRecycled) it.second.recycle() }
                throw failure
              } finally { face.dispose() }
            }
            try {
              for ((layer, bitmap) in drawing.images) {
                check(remaining() > 0)
                val file = File(context.cacheDir, "$prefix-$name-$layer.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(file.length() > 0)
                val path = "cache/${file.name}"
                artifacts.put(path)
                drawing.geometry.put(if (layer == "actual") "path" else "${layer}Path", path)
              }
              renders.put(drawing.geometry)
              casesRun++
            } finally { drawing.dispose() }
          }
        }
      }
      for (weight in listOf(400, 700)) {
        for (a in families.indices) for (b in a + 1 until families.size) {
          val left = hashes["${families[a]}:$weight"]
          val right = hashes["${families[b]}:$weight"]
          expect("glyph_families.$weight.$a.$b.distinct", left != null && right != null && left != right)
        }
      }
      for (font in families) {
        val regular = hashes["$font:400"]
        val bold = hashes["$font:700"]
        val referenceDiffers = referenceHashes["$font:400"] != referenceHashes["$font:700"]
        expect("glyph_weights.$font.match_native_reference_difference",
            regular != null && bold != null && (regular != bold) == referenceDiffers)
      }
      expect("glyph_weights.built_in_reference_has_visible_weight_difference",
          families.take(3).any { referenceHashes["$it:400"] != referenceHashes["$it:700"] })
    }
    expect("budget.within_15_seconds", SystemClock.elapsedRealtime() - began <= RUN_MS)
    val passed = failures == 0 && isolatedFailures == 0 && casesRun == 20
    val report = JSONObject().put("checks", checks).put("renders", renders).put("artifacts", artifacts)
        .put("caseCount", casesRun).put("renderCount", renders.length())
        .put("checkCount", checks.length()).put("failureCount", failures)
        .put("isolatedFailureCount", isolatedFailures).put("passed", passed)
        .put("scope", "detached_canvas").put("windowProof", false).put("fpsProof", false)
        .put("audioProof", false).put("generatedOnly", true).put("workBudgetMs", RUN_MS)
        .put("elapsedMs", SystemClock.elapsedRealtime() - began)
        .put("relativeReportPath", "cache/${reportFile.name}")
    var written = false
    try { reportFile.writeText(report.toString(2)); written = reportFile.length() > 0 }
    catch (_: Throwable) { /* No exception messages or private diagnostics in reports. */ }
    return Bundle().apply {
      putBoolean("digitalClockFontCompleted", true)
      putBoolean("digitalClockFontPassed", passed && written)
      putString("digitalClockFontChecksJson", checks.toString())
      putInt("digitalClockFontCaseCount", casesRun)
      putInt("digitalClockFontRenderCount", renders.length())
      putInt("digitalClockFontCheckCount", checks.length())
      putInt("digitalClockFontFailureCount", failures)
      putInt("digitalClockFontIsolatedFailureCount", isolatedFailures)
      putString("digitalClockFontReportRelativePath", "cache/${reportFile.name}")
      putString("digitalClockFontArtifactPaths", artifacts.toString())
    }
  }

  private fun field(face: PrimeDigitalClockFaceView, name: String): Any? =
      PrimeDigitalClockFaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(face)

  private fun draw(view: View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try { view.draw(Canvas(bitmap)); return bitmap }
    catch (failure: Throwable) { bitmap.recycle(); throw failure }
  }

  private fun without(face: PrimeDigitalClockFaceView, vararg names: String): Bitmap {
    val paints = names.map { field(face, it) as Paint }
    val alphas = paints.map { it.alpha }
    try { paints.forEach { it.alpha = 0 }; return draw(face.view) }
    finally { paints.forEachIndexed { i, paint -> paint.alpha = alphas[i] } }
  }

  private fun fixedMask(typeface: Typeface): Bitmap {
    val bitmap = Bitmap.createBitmap(512, 160, Bitmap.Config.ARGB_8888)
    try {
      Canvas(bitmap).drawText(LITERAL, 12f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface; textSize = 90f; color = Color.WHITE; clearShadowLayer()
      })
      return bitmap
    } catch (failure: Throwable) { bitmap.recycle(); throw failure }
  }

  private fun digest(bitmap: Bitmap): String {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val bytes = ByteArray(pixels.size) { Color.alpha(pixels[it]).toByte() }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it.toInt() and 255)
    }
  }

  private fun bounds(bitmap: Bitmap): Rect? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var left = bitmap.width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    pixels.forEachIndexed { i, color ->
      if (Color.alpha(color) > 0) {
        left = minOf(left, i % bitmap.width); top = minOf(top, i / bitmap.width)
        right = maxOf(right, i % bitmap.width); bottom = maxOf(bottom, i / bitmap.width)
      }
    }
    return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
  }

  private fun rectJson(rect: Rect?): Any = rect?.let {
    JSONArray(listOf(it.left, it.top, it.right, it.bottom))
  } ?: JSONObject.NULL
}
