/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

/** Same public decoder probe for old and new APKs; owns only a fresh cache directory. */
internal object FilmstripFileAdmissionDeviceChecks {
  fun run(context: Context): JSONObject {
    val directory = File(context.cacheDir, "filmstrip-file-admission-${UUID.randomUUID()}")
    check(directory.mkdir()) { "Could not create an owned Filmstrip probe directory" }
    val largeGif = File(directory, "large-cached.jpg")
    val tinyGif = File(directory, "warmup.gif")
    val report = JSONObject()
    try {
      val signature = "GIF89a".toByteArray(Charsets.US_ASCII)
      RandomAccessFile(largeGif, "rw").use {
        it.write(signature)
        it.setLength(16L * 1024 * 1024)
      }
      // Complete single-frame 1x1 GIF; fixture creation and warmups are not measured.
      tinyGif.writeBytes(signature + byteArrayOf(
        1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b
      ))
      val warmupPath = tinyGif.path
      val measuredPath = largeGif.path
      val encodedBytes = largeGif.length()
      repeat(4) {
        callNativeFile(warmupPath)
        processAllocatedJavaBytes()
        SystemClock.elapsedRealtimeNanos()
      }

      val allocatedBefore = processAllocatedJavaBytes()
      val started = SystemClock.elapsedRealtimeNanos()
      val fallbackSignal = callNativeFile(measuredPath)
      val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
      val allocatedAfter = processAllocatedJavaBytes()

      // This cumulative ART counter includes concurrent application work and sampling
      // overhead. Its delta is process-wide Java allocation, never Java heap peak.
      val allocated = if (allocatedBefore != null && allocatedAfter != null &&
          allocatedAfter >= allocatedBefore) allocatedAfter - allocatedBefore else null
      report.put("encodedBytes", encodedBytes)
          .put("nativeFallbackSignal", fallbackSignal)
          .put("processAllocatedJavaBytes", allocated ?: JSONObject.NULL)
          .put("allocationCounterSupported", allocated != null)
          .put("elapsedNanos", elapsedNanos)
    } finally {
      // Delete exactly the two owned fixtures and their directory, never other cache files.
      // Attempt every deletion even if one fails; no recursive cache cleanup.
      val tinyDeleted = deleteOwned(tinyGif)
      val largeDeleted = deleteOwned(largeGif)
      val directoryDeleted = deleteOwned(directory)
      report.put("cleanupExact", tinyDeleted && largeDeleted && directoryDeleted)
    }
    return report
  }

  /** Only the explicit unsupported-format exception proves native fallback. */
  private fun callNativeFile(path: String): Boolean = try {
    SlideshowDecoder.nativeFilmstripFile(path)?.recycle()
    false // A bitmap or null is not the native fallback signal.
  } catch (_: NativeFilmstripUnsupportedFormat) {
    true
  }

  private fun processAllocatedJavaBytes(): Long? = try {
    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.takeIf { it >= 0L }
  } catch (_: RuntimeException) {
    null
  } catch (_: LinkageError) {
    null
  }

  private fun deleteOwned(file: File): Boolean = try {
    !file.exists() || file.delete()
  } catch (_: SecurityException) {
    false
  }
}
