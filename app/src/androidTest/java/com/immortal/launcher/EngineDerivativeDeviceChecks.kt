/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Looper
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Actual Android codecs/files; owns only generated fixtures, never household photos/settings. */
internal object EngineDerivativeDeviceChecks {
  fun run(probe: Instrumentation): Bundle {
    check(Looper.myLooper() != Looper.getMainLooper())
    val root = File(probe.targetContext.cacheDir, "derivative-probe-${UUID.randomUUID()}")
    val directory = File(root, "copies")
    val source = File(root, "source.jpg")
    val cache = EngineScaledCache(directory)
    val assertions = JSONObject()
    fun demand(label: String, condition: Boolean) {
      assertions.put(label, condition)
      check(condition) { label }
    }
    fun await(edge: Int): File {
      val deadline = SystemClock.elapsedRealtime() + 15_000L
      while (SystemClock.elapsedRealtime() < deadline) {
        cache.resolveAndRequest(source.path, edge)?.takeIf {
          it.name == EngineScaledCache.keyFor(source.path, source.length(), source.lastModified(), edge)
        }?.let { return it }
        SystemClock.sleep(25)
      }
      error("Derivative did not become ready")
    }
    fun size(file: File): Pair<Int, Int> {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, bounds)
      return bounds.outWidth to bounds.outHeight
    }
    var completed = false
    var stage = "fixture"
    try {
      root.mkdirs()
      directory.mkdirs()
      val orphan = File(directory, "interrupted.jpg.part").apply { writeBytes(ByteArray(128)) }
      val bitmap = Bitmap.createBitmap(3300, 1800, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for ((color, xy) in listOf(Color.RED to (0f to 0f), Color.GREEN to (1650f to 0f),
            Color.BLUE to (0f to 900f), Color.YELLOW to (1650f to 900f))) {
          paint.color = color
          canvas.drawRect(xy.first, xy.second, xy.first + 1650f, xy.second + 900f, paint)
        }
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
      } finally { bitmap.recycle() }
      ExifInterface(source.path).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        saveAttributes()
      }
      val bytes = source.length()
      val modified = source.lastModified()
      stage = "small_tier"
      var bridgeMs = 0L
      probe.runOnMainSync {
        val began = SystemClock.elapsedRealtime()
        cache.resolveAndRequest(source.path, 900)
        bridgeMs = SystemClock.elapsedRealtime() - began
      }
      val small = await(1200)
      demand("interrupted_partial_removed", !orphan.exists())
      demand("small_copy_has_requested_tier", maxOf(size(small).first, size(small).second) == 1200)
      demand("orientation_is_baked", size(small).first < size(small).second)
      val upright = BitmapFactory.decodeFile(small.path)
      try {
        val pixel = upright.getPixel(upright.width * 9 / 10, upright.height / 10)
        demand("rotated_corner_keeps_red", Color.red(pixel) > 200 && Color.green(pixel) < 40 && Color.blue(pixel) < 40)
      } finally { upright.recycle() }
      stage = "full_tier"
      val full = await(2400)
      demand("full_copy_keeps_zoom_headroom", maxOf(size(full).first, size(full).second) == 2400)
      demand("tiers_have_distinct_files", full != small)
      demand("never_use_small_for_full_slot", cache.resolveAndRequest(source.path, 2200) == full)
      demand("ready_larger_fallback", cache.resolveAndRequest(source.path, 600) in listOf(small, full))
      val tiny = await(600)
      demand("tiny_copy_600_edge", maxOf(size(tiny).first, size(tiny).second) == 600)
      demand("original_preserved", source.length() == bytes && source.lastModified() == modified)
      demand("complete_files_only", directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
      stage = "eviction_and_edit"
      check(small.delete())
      demand("eviction_invalidates_lookup", cache.resolve(source.path, 900) == null)
      val rebuilt = await(1200)
      demand("evicted_copy_rebuilt", rebuilt == small && rebuilt.length() > 0)
      source.appendBytes(byteArrayOf(0))
      demand("edited_original_invalidates_old_copies", cache.resolve(source.path, 900) == null)
      val edited = await(1200)
      demand("edited_original_gets_new_identity", edited != small)
      stage = "background_warm"
      if (Build.VERSION.SDK_INT >= 28) {
        val reads = AtomicInteger()
        val paths = List(300) { source.path }
        probe.runOnMainSync {
          val previous = StrictMode.getThreadPolicy()
          try {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous).detectDiskReads()
                .penaltyListener({ it.run() }) { violation ->
                  if (violation.stackTrace.any { it.className.contains("EngineScaledCache") }) reads.incrementAndGet()
                }.build())
            cache.warm(paths)
          } finally { StrictMode.setThreadPolicy(previous) }
        }
        probe.runOnMainSync { } // Allow deferred StrictMode reports to drain.
        demand("warm_does_not_read_disk_on_ui", reads.get() == 0)
        await(2400)
      }
      stage = "cancellation"
      cache.cancel()
      demand("cancel_keeps_complete_cache", cache.resolve(source.path, 900) == edited)
      completed = true
      return Bundle().apply {
        putBoolean("derivativesCompleted", true)
        putLong("bridgeLookupMs", bridgeMs) // Report; no brittle emulator timing threshold.
        putString("derivativeAssertions", assertions.toString())
      }
    } catch (_: Throwable) {
      return Bundle().apply {
        putBoolean("derivativesCompleted", completed)
        putString("failureStage", stage)
        putString("derivativeAssertions", assertions.toString())
      }
    } finally {
      cache.cancel()
      root.deleteRecursively()
    }
  }
}
