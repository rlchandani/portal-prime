/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only worker probe. Reads no settings, photos, providers, or network sources. */
@Suppress("DEPRECATION")
class FilmstripBackdropPreviewActivity : ComponentActivity() {
  private var worker: Thread? = null
  private lateinit var status: TextView
  private lateinit var preview: ImageView
  private var displayed: Bitmap? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(24, 24, 24, 24)
      setBackgroundColor(Color.rgb(28, 27, 25))
    }
    status = TextView(this).apply {
      text = "Filmstrip backdrop: comparing frozen oracle and reusable session"
      textSize = 20f
      setTextColor(Color.WHITE)
    }
    preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    column.addView(status)
    column.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
    setContentView(column)
    worker = Thread({ runChecks() }, "FilmstripBackdropNativeCheck").also { it.start() }
  }

  override fun onDestroy() {
    worker?.interrupt()
    preview.setImageDrawable(null)
    displayed = null
    super.onDestroy()
  }

  private data class Scenario(val label: String, val width: Int, val height: Int, val density: Float)

  private fun runChecks() {
    val directory = File(cacheDir, "filmstrip-backdrop-native").apply { mkdirs() }
    val checks = JSONArray()
    val scenarios = JSONArray()
    val report = JSONObject()
        .put("complete", false).put("passed", 0).put("checks", checks)
        .put("scenarios", scenarios).put("api", Build.VERSION.SDK_INT)
        .put("oracle", "Frozen pre-session FilmstripBackdrop.prepare algorithm")
        .put("originalInMemorySources", 12)
        .put("physicalPortalPerformanceMeasured", false)
        .put("renderScriptContextCreationSavingsMeasured", false)
        .put("inFlightInterruptionDeterministicallyMeasured", false)
    fun publish() {
      File(directory, "checks.json").writeText(report.toString(2) + "\n")
    }
    fun demand(name: String, condition: Boolean) {
      check(condition) { name }
      checks.put(name)
      report.put("passed", checks.length())
      publish()
    }
    val sources = mutableListOf<Bitmap>()
    var display: Bitmap? = null
    try {
      publish()
      repeat(12) { sources.add(source(it)) }
      sources.forEachIndexed { index, bitmap -> writePng(directory, "source-%02d".format(index), bitmap) }
      val cases = listOf(
          Scenario("portal-1pass", 2160, 1440, 1.5f),
          Scenario("landscape-2pass", 1080, 720, 1.5f),
          Scenario("portrait-3pass", 720, 1280, 2f),
          Scenario("landscape-6pass", 800, 600, 2f),
          Scenario("capped-8pass", 480, 320, 3f))
      for ((caseIndex, case) in cases.withIndex()) {
        val (width, height) = frozenSize(case.width, case.height)
        val sigma = 20f * case.density * width / case.width
        val passes = ceil((sigma / 10.6f) * (sigma / 10.6f)).toInt().coerceIn(1, 8)
        val radius = ((sigma / sqrt(passes.toFloat()) - .6f) / .4f).coerceIn(.1f, 25f)
        val plan = FilmstripBackdrop.plan(case.width, case.height, case.density)
        demand("${case.label} preserves frozen dimensions, pass count and radius",
            plan.width == width && plan.height == height && plan.passes == passes && plan.radius == radius)
        val row = JSONObject().put("label", case.label)
            .put("viewportWidth", case.width).put("viewportHeight", case.height)
            .put("density", case.density).put("textureWidth", width).put("textureHeight", height)
            .put("passes", passes).put("radius", radius).put("pixelIdenticalSources", 0)
        scenarios.put(row)
        val owned = mutableListOf<Bitmap>()
        val snapshots = mutableListOf<IntArray>()
        val session = FilmstripBackdrop.session(applicationContext, case.width, case.height, case.density)
        var handles = emptyList<Any>()
        try {
          if (caseIndex == 0) {
            var prepareRejected = false
            var closeRejected = false
            val foreignWorker = Thread({
              try { session.prepare(sources[0]).also { it.recycle() } }
              catch (_: IllegalStateException) { prepareRejected = true }
              try { session.close() }
              catch (_: IllegalStateException) { closeRejected = true }
            }, "FilmstripBackdropWrongOwner")
            foreignWorker.start()
            foreignWorker.join(5_000)
            demand("Session rejects prepare and close on another worker",
                !foreignWorker.isAlive && prepareRejected && closeRejected)
          }
          sources.forEachIndexed { index, input ->
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val expected = frozenPrepare(applicationContext, input, case.width, case.height, case.density)
            try {
              val actual = session.prepare(input)
              owned.add(actual)
              val expectedPixels = pixels(expected)
              val actualPixels = pixels(actual)
              snapshots.add(actualPixels)
              demand("${case.label}/source-$index pixel-identical to frozen oracle",
                  actual.width == width && actual.height == height && expectedPixels.contentEquals(actualPixels))
              demand("${case.label}/source-$index output has independent ownership",
                  actual !== input && owned.dropLast(1).none { it === actual })
              owned.dropLast(1).forEachIndexed { previous, bitmap ->
                check(!bitmap.isRecycled && pixels(bitmap).contentEquals(snapshots[previous])) {
                  "${case.label}: preparing source $index mutated output $previous"
                }
              }
              if (index == 0) {
                handles = resourceHandles(session)
                demand("${case.label} initializes exactly two allocations and one blur handle",
                    handles.count { it is android.renderscript.Allocation } == 2 &&
                        handles.count { it is android.renderscript.ScriptIntrinsicBlur } == 1)
                row.put("allocationHandlesObserved", handles.count { it is android.renderscript.Allocation })
                    .put("blurHandlesObserved", handles.count { it is android.renderscript.ScriptIntrinsicBlur })
              } else if (handles.isNotEmpty()) {
                val current = resourceHandles(session)
                demand("${case.label}/source-$index reuses the same resource identities",
                    handles.size == current.size && handles.all { old -> current.any { it === old } })
              }
              if (caseIndex == 0) {
                writePng(directory, "oracle-%02d".format(index), expected)
                writePng(directory, "session-%02d".format(index), actual)
                if (index == 0) display = actual.copy(Bitmap.Config.ARGB_8888, false)
              }
              if (index == 0 || index == sources.lastIndex) {
                val isolated = FilmstripBackdrop.prepare(applicationContext, input, case.width, case.height, case.density)
                try {
                  demand("${case.label}/source-$index convenience prepare matches frozen oracle",
                      expectedPixels.contentEquals(pixels(isolated)))
                } finally { isolated.recycle() }
              }
              row.put("pixelIdenticalSources", index + 1)
            } finally { expected.recycle() }
          }
          demand("${case.label} retained outputs remain unchanged after all 12 preparations",
              owned.indices.all { !owned[it].isRecycled && pixels(owned[it]).contentEquals(snapshots[it]) })
          session.close()
          session.close()
          demand("${case.label} close clears all scratch and native handle references",
              resourceHandles(session).isEmpty())
          demand("${case.label} close is idempotent and leaves caller-owned outputs intact",
              owned.indices.all { !owned[it].isRecycled && pixels(owned[it]).contentEquals(snapshots[it]) })
          var rejected = false
          try {
            session.prepare(sources[0]).also { it.recycle() }
          } catch (_: IllegalStateException) { rejected = true }
          demand("${case.label} prepare rejects a closed session", rejected)
          val allocations = handles.filterIsInstance<android.renderscript.Allocation>()
          val scripts = handles.filterIsInstance<android.renderscript.ScriptIntrinsicBlur>()
          if (allocations.size == 2 && scripts.size == 1) {
            val probe = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
              val allocationFailures = allocations.map { allocation ->
                runCatching { allocation.copyTo(probe) }.exceptionOrNull()
              }
              row.put("closedAllocationErrors", JSONArray(allocationFailures.map { it?.toString() ?: "call succeeded" }))
              demand("${case.label} close destroys both allocation handles",
                  allocationFailures.all { it is RuntimeException })
              val blurFailure = runCatching { scripts.single().setRadius(.1f) }.exceptionOrNull()
              row.put("closedBlurError", blurFailure?.toString() ?: "call succeeded")
              demand("${case.label} close destroys the blur handle",
                  blurFailure is RuntimeException)
              row.put("resourceReleaseVerified", true)
            } finally { probe.recycle() }
          } else {
            row.put("resourceReleaseVerified", false)
          }
        } finally {
          session.close()
          owned.forEach { if (!it.isRecycled) it.recycle() }
        }
      }
      val interruption = FilmstripBackdrop.session(applicationContext, 1080, 720, 1.5f)
      demand("New session allocates no bitmap or native scratch before preparation",
          resourceHandles(interruption).isEmpty())
      var cancellationObserved = false
      var interruptedOutput: Bitmap? = null
      try {
        Thread.currentThread().interrupt()
        try { interruptedOutput = interruption.prepare(sources[0]) }
        catch (_: CancellationException) { cancellationObserved = true }
        finally { Thread.interrupted() }
        demand("Pre-interrupted preparation throws CancellationException and publishes no output",
            cancellationObserved && interruptedOutput == null)
        demand("Pre-interrupted first preparation allocates no scratch or native handles",
            resourceHandles(interruption).isEmpty())
      } finally {
        Thread.interrupted()
        interruptedOutput?.recycle()
        interruption.close()
        interruption.close()
      }
      val after = FilmstripBackdrop.session(applicationContext, 1080, 720, 1.5f)
      var retained: Bitmap? = null
      try {
        retained = after.prepare(sources[0])
        val before = pixels(checkNotNull(retained))
        val handles = resourceHandles(after)
        cancellationObserved = false
        Thread.currentThread().interrupt()
        try { after.prepare(sources[1]).also { it.recycle() } }
        catch (_: CancellationException) { cancellationObserved = true }
        finally { Thread.interrupted() }
        demand("Interrupted initialized session publishes no output and retains its handles",
            cancellationObserved && handles.all { old -> resourceHandles(after).any { it === old } })
        val actual = after.prepare(sources[3])
        val expected = frozenPrepare(applicationContext, sources[3], 1080, 720, 1.5f)
        try {
          demand("Session recovers after interruption and clears transparent input without residual pixels",
              pixels(actual).contentEquals(pixels(expected)) && pixels(actual).all { it == Color.TRANSPARENT })
          demand("Interruption and recovery preserve previously returned output",
              !checkNotNull(retained).isRecycled && pixels(checkNotNull(retained)).contentEquals(before))
        } finally { actual.recycle(); expected.recycle() }
      } finally {
        Thread.interrupted()
        after.close()
        after.close()
        retained?.recycle()
      }
      demand("All original source bitmaps remain caller-owned", sources.none { it.isRecycled })
      report.put("complete", true).put("success", true)
      publish()
      val image = display
      display = null
      runOnUiThread {
        if (isFinishing || isDestroyed) {
          image?.recycle()
        } else {
          displayed = image
          status.text = "PASS: ${checks.length()} checks; 60 frozen-oracle pixel comparisons"
          preview.setImageBitmap(image)
        }
      }
    } catch (failure: Throwable) {
      Thread.interrupted()
      report.put("complete", true).put("success", false)
          .put("failure", failure.toString()).put("stack", failure.stackTraceToString())
      publish()
      runOnUiThread { status.text = "FAIL: $failure" }
    } finally {
      display?.recycle()
      sources.forEach { if (!it.isRecycled) it.recycle() }
    }
  }

  private fun resourceHandles(session: Any): List<Any> =
      session.javaClass.declaredFields.mapNotNull { field ->
        field.isAccessible = true
        field.get(session)?.takeIf { it is android.renderscript.Allocation ||
            it is android.renderscript.ScriptIntrinsicBlur || it is Bitmap }
      }

  private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
  }

  private fun writePng(directory: File, name: String, bitmap: Bitmap) {
    File(directory, "$name.png").outputStream().use {
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }

  private fun source(index: Int): Bitmap {
    val sizes = listOf(640 to 426, 180 to 780, 1800 to 300, 240 to 900,
        511 to 511, 900 to 150, 150 to 950, 1200 to 240,
        301 to 777, 777 to 301, 72 to 96, 1001 to 667)
    val (width, height) = sizes[index]
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (index == 3) return bitmap // Completely transparent after three nonempty sources.
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (index % 4 != 1 && index != 7) {
      paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
          Color.rgb(35 + index * 12, 70 + index * 8, 160 - index * 8),
          Color.rgb(230 - index * 7, 195 - index * 8, 90 + index * 10), Shader.TileMode.CLAMP)
      canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
      paint.shader = null
    }
    paint.color = Color.argb(if (index % 4 == 1 || index == 7) 100 else 255, 240, 80, 55)
    canvas.drawCircle(width * .23f, height * .37f, minOf(width, height) * .18f, paint)
    paint.color = Color.argb(if (index % 4 == 1 || index == 7) 165 else 255, 45, 210, 180)
    canvas.drawRect(width * .57f, height * .13f, width * .92f, height * .78f, paint)
    paint.color = Color.WHITE
    paint.strokeWidth = maxOf(1f, minOf(width, height) * .02f)
    canvas.drawLine(0f, height * .82f, width.toFloat(), height * .18f, paint)
    return bitmap
  }

  private fun frozenSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = 512.0 / maxOf(width, height)
    return (width*scale).roundToInt().coerceIn(1,512) to (height*scale).roundToInt().coerceIn(1,512)
  }

  // Frozen verbatim from pre-session git HEAD. This oracle deliberately does not call Session.
  private fun frozenPrepare(context: Context, source: Bitmap, viewportWidth: Int, viewportHeight: Int,
      density: Float): Bitmap {
    require(viewportWidth > 0 && viewportHeight > 0 && density > 0f)
    val (width, height) = frozenSize(viewportWidth, viewportHeight)
    val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var result: Bitmap? = null
    var rs: android.renderscript.RenderScript? = null
    var input: android.renderscript.Allocation? = null
    var output: android.renderscript.Allocation? = null
    var blur: android.renderscript.ScriptIntrinsicBlur? = null
    try {
      val saturation = ColorMatrix().apply { setSaturation(1.2f) }
      val brightness = ColorMatrix(floatArrayOf(
          .6f,0f,0f,0f,0f, 0f,.6f,0f,0f,0f, 0f,0f,.6f,0f,0f, 0f,0f,0f,1f,0f))
      saturation.postConcat(brightness)
      val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(saturation) }
      val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
      val drawnWidth = source.width * scale
      val drawnHeight = source.height * scale
      Canvas(small).drawBitmap(source, Rect(0,0,source.width,source.height),
          RectF((width-drawnWidth)/2f, (height-drawnHeight)/2f,
              (width+drawnWidth)/2f, (height+drawnHeight)/2f), paint)
      if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
      rs = android.renderscript.RenderScript.create(context.applicationContext)
      input = android.renderscript.Allocation.createFromBitmap(rs, small)
      output = android.renderscript.Allocation.createTyped(rs, input.type)
      blur = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
      // CSS: 24px blur, -10% inset, then scale(1.15). Radius here is in the small texture.
      val targetSigma = 20f * density * width / viewportWidth
      val passes = kotlin.math.ceil((targetSigma / 10.6f) * (targetSigma / 10.6f)).toInt().coerceIn(1,8)
      // AOSP intrinsic uses sigma = .4 * radius + .6; Gaussian variances add over passes.
      // https://android.googlesource.com/platform/frameworks/rs/+/refs/tags/android-13.0.0_r52/cpu_ref/rsCpuIntrinsicBlur.cpp
      blur.setRadius(((targetSigma / kotlin.math.sqrt(passes.toFloat())-.6f)/.4f).coerceIn(.1f,25f))
      repeat(passes) { pass ->
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        blur.setInput(input)
        blur.forEach(output)
        if (pass+1 < passes) { val swap = input; input = output; output = swap }
      }
      result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      checkNotNull(output).copyTo(result)
      if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
      return result.also { result = null }
    } finally {
      runCatching { blur?.destroy() }; runCatching { output?.destroy() }
      runCatching { input?.destroy() }; runCatching { rs?.destroy() }
      small.recycle(); result?.recycle()
    }
  }
}
