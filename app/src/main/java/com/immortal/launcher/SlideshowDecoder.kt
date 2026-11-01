/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Blocking worker-only decode. The caller owns successful bitmaps and input streams.
 * File handles and intermediate bitmaps are owned here. All sources share the same upright
 * EXIF 1..8 path and hard limits: 2400px, 12 MiB of prepared pixels, 32 MiB encoded input.
 *
 * Rotation/reflection can temporarily hold two bounded pixel allocations (24 MiB total).
 * Encoded buffering can hold two copies; codec scratch and GPU residency need separate
 * stage admission. Interruption is checked around every read/decode/transform; the worker
 * must close its transport to unblock a stuck read, since BitmapFactory is not interruptible.
 */
object SlideshowDecoder {
  fun file(path: String, maxEdge: Int = 2400): Bitmap? = file(path, maxEdge, false)

  /** Native cards cannot animate encoded images; reject the whole deck before Bitmap decode. */
  fun nativeFilmstripFile(path: String, maxEdge: Int = 1400): Bitmap? = file(path, maxEdge, true)

  fun nativeFilmstripBytes(encoded: ByteArray, maxEdge: Int = 1400): Bitmap? {
    if (cancelled() || maxEdge <= 0 || encoded.isEmpty() ||
        encoded.size > SlideshowDecodeBudget.MAX_ENCODED_BYTES) return null
    if (!NativeFilmstripFormat.isConfirmedStatic(encoded)) throw NativeFilmstripUnsupportedFormat()
    return bytes(encoded, maxEdge)
  }

  private fun file(path: String, maxEdge: Int, nativeFilmstrip: Boolean): Bitmap? {
    if (cancelled() || maxEdge <= 0) return null
    return try {
      val file = File(path)
      if (!file.isFile || file.length() !in 1..SlideshowDecodeBudget.MAX_ENCODED_BYTES.toLong()) null
      else {
        if (nativeFilmstrip && !NativeFilmstripFormat.isConfirmedStatic(file))
          throw NativeFilmstripUnsupportedFormat()
        file.inputStream().use {
          // Recheck the exact decoded bytes: an external file may change after preflight.
          if (nativeFilmstrip) readEncoded(it)?.let { encoded -> nativeFilmstripBytes(encoded, maxEdge) }
          else stream(it, maxEdge)
        }
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  fun bytes(bytes: ByteArray, maxEdge: Int = 2400): Bitmap? {
    if (cancelled() || maxEdge <= 0 || bytes.isEmpty() ||
        bytes.size > SlideshowDecodeBudget.MAX_ENCODED_BYTES) return null
    var sampled: Bitmap? = null
    var upright: Bitmap? = null
    try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (cancelled()) return null
      val plan = SlideshowDecodeBudget.plan(bounds.outWidth, bounds.outHeight, maxEdge) ?: return null
      val orientation = orientation(bytes)
      if (cancelled()) return null
      val options = BitmapFactory.Options().apply {
        inSampleSize = plan.sampleSize
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        // Prevent wide-gamut originals from implicitly doubling bytes/pixel with RGBA_F16.
        if (Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
      }
      sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
      val decoded = sampled
      if (cancelled() || !admitted(decoded, maxEdge) ||
          decoded.width > plan.width || decoded.height > plan.height) return null
      upright = if (orientation in 2..8) {
        val exif = SlideshowDecodeBudget.exifTransform(orientation)
        val matrix = Matrix().apply {
          // createBitmap translates the transformed bounds to the origin itself.
          setValues(floatArrayOf(exif.xx.toFloat(), exif.xy.toFloat(), 0f,
              exif.yx.toFloat(), exif.yy.toFloat(), 0f, 0f, 0f, 1f))
        }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, false)
      } else decoded
      val ready = upright
      if (cancelled() || !admitted(ready, maxEdge)) return null
      if (ready !== decoded) decoded.recycle()
      // Transfer only the accepted upright allocation to the caller.
      sampled = null
      upright = null
      return ready
    } catch (_: OutOfMemoryError) {
      return null
    } catch (_: RuntimeException) {
      return null
    } finally {
      upright?.let { if (!it.isRecycled) it.recycle() }
      sampled?.let { if (!it.isRecycled) it.recycle() }
    }
  }

  /** Reads but does not close the caller's stream. Oversize inputs are rejected, never truncated. */
  fun stream(stream: InputStream, maxEdge: Int = 2400): Bitmap? {
    if (cancelled() || maxEdge <= 0) return null
    val encoded = readEncoded(stream) ?: return null
    return bytes(encoded, maxEdge)
  }

  internal fun readEncoded(
      stream: InputStream, limit: Int = SlideshowDecodeBudget.MAX_ENCODED_BYTES
  ): ByteArray? {
    if (cancelled() || limit <= 0) return null
    val cap = minOf(limit, SlideshowDecodeBudget.MAX_ENCODED_BYTES)
    return try {
      val output = ByteArrayOutputStream(minOf(cap, 16384))
      val buffer = ByteArray(minOf(cap, 16384))
      while (true) {
        if (cancelled()) return null
        // Probe only one extra byte, including for infinite or oversized network streams.
        val count = stream.read(buffer, 0, minOf(buffer.size, cap - output.size() + 1))
        if (cancelled()) return null
        if (count < 0) break
        if (count == 0) {
          val byte = stream.read()
          if (cancelled()) return null
          if (byte < 0) break
          if (output.size() == cap) return null
          output.write(byte)
        } else {
          if (count > cap - output.size()) return null
          output.write(buffer, 0, count)
        }
      }
      if (cancelled()) null else output.toByteArray()
    } catch (_: SocketTimeoutException) {
      null
    } catch (_: InterruptedIOException) {
      Thread.currentThread().interrupt()
      null
    } catch (_: IOException) {
      null
    } catch (_: OutOfMemoryError) {
      null
    }
  }

  private fun cancelled(): Boolean = Thread.currentThread().isInterrupted

  private fun admitted(bitmap: Bitmap, maxEdge: Int): Boolean =
      SlideshowDecodeBudget.fits(bitmap.width, bitmap.height, maxEdge) &&
          bitmap.allocationByteCount.toLong() <= SlideshowDecodeBudget.MAX_PIXEL_BYTES

  private fun orientation(bytes: ByteArray): Int = try {
    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  } catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
  }
}
