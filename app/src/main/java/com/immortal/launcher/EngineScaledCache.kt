/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decode-once, screen-sized copies of local photos for the HTML screensaver engine.
 *
 * The engine's WebView loads folder photos straight from disk, so a 12MP camera original is decoded
 * to a ~46MB texture every time it appears — that decode + upload is the per-transition hitch on the
 * Portal's weak GPU, and nine of them blow past Chromium's decoded-image cache so nothing stays warm.
 * Here each oversized photo is scaled ONCE to [targetEdge] (EXIF rotation baked in) on a low-priority
 * background thread and written to [dir]; the engine asks [resolve] per load and gets the small copy
 * when it exists, the original otherwise. Nothing is ever decoded on the load path — the earlier
 * on-the-fly downsampler made images visibly "buffer" in.
 *
 * Entries are keyed by the original's path + size + mtime, so an edited photo is simply a new key;
 * the directory is trimmed oldest-first to [budgetBytes] after every write.
 */
class EngineScaledCache(
    private val dir: File,
    private val targetEdge: Int = TARGET_EDGE,
    private val budgetBytes: Long = BUDGET_BYTES,
) {
  /** A cached file as seen by the eviction planner: name, byte size, and write time. */
  data class Entry(val name: String, val bytes: Long, val writtenAt: Long)

  private val cancelled = AtomicBoolean(false)
  private val running = AtomicBoolean(false)

  /** The ready scaled copy for [originalPath], or null when none (missing, in progress, or not needed). */
  fun resolve(originalPath: String): File? {
    val orig = File(originalPath)
    if (!orig.isFile) return null
    val f = File(dir, keyFor(originalPath, orig.length(), orig.lastModified()))
    return if (f.isFile && f.length() > 0L) f else null
  }

  /**
   * Scale every not-yet-cached photo in [paths], in order, on one low-priority thread. Idempotent:
   * a second call while a warm is running is ignored. Stops early after [cancel].
   */
  fun warm(paths: List<String>) {
    if (!running.compareAndSet(false, true)) return
    cancelled.set(false)
    Thread(
            {
              try {
                dir.mkdirs()
                for (p in paths) {
                  if (cancelled.get()) break
                  if (resolve(p) != null) continue
                  runCatching { scaleOne(p) }.onFailure { Log.w(TAG, "scale failed for $p: ${it.message}") }
                }
                trimToBudget()
              } finally {
                running.set(false)
              }
            },
            "ss-scaled-cache")
        .apply { priority = Thread.MIN_PRIORITY }
        .start()
  }

  /** Stop the warm loop after the photo in flight (called when the screensaver exits). */
  fun cancel() = cancelled.set(true)

  private fun scaleOne(path: String) {
    val orig = File(path)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0 || !needsScaling(w, h, targetEdge)) return
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(maxOf(w, h), targetEdge) }
    val src = BitmapFactory.decodeFile(path, opts) ?: return
    val (tw, th) = targetSize(src.width, src.height, targetEdge)
    val m = Matrix().apply { postScale(tw.toFloat() / src.width, th.toFloat() / src.height) }
    applyExifRotation(m, path, tw, th)
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (out !== src) src.recycle()
    val dst = File(dir, keyFor(path, orig.length(), orig.lastModified()))
    val tmp = File(dir, dst.name + ".part")
    try {
      FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
      if (!tmp.renameTo(dst)) throw IllegalStateException("rename failed")
    } finally {
      out.recycle()
      tmp.delete()
    }
  }

  /** Bake the EXIF orientation into the copy (a rotated copy has no tag, so it renders upright). */
  private fun applyExifRotation(m: Matrix, path: String, tw: Int, th: Int) {
    val o =
        runCatching {
              ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val cx = tw / 2f
    val cy = th / 2f
    when (o) {
      ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f, cx, cy)
      ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f, cx, cy)
      ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f, cx, cy)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f, cx, cy)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f, cx, cy)
      ExifInterface.ORIENTATION_TRANSPOSE -> { m.postScale(-1f, 1f, cx, cy); m.postRotate(270f, cx, cy) }
      ExifInterface.ORIENTATION_TRANSVERSE -> { m.postScale(-1f, 1f, cx, cy); m.postRotate(90f, cx, cy) }
    }
  }

  private fun trimToBudget() {
    val entries = (dir.listFiles() ?: return).filter { it.isFile }.map { Entry(it.name, it.length(), it.lastModified()) }
    for (name in evictionPlan(entries, budgetBytes)) File(dir, name).delete()
  }

  companion object {
    private const val TAG = "EngineScaledCache"
    // Long edge of a cached copy: the largest Portal panel is 2160px; 2400 leaves Ken-Burns zoom
    // headroom (2400×1800×4 ≈ 17MB texture vs 46MB for a 12MP original).
    const val TARGET_EDGE = 2400
    // Disk budget for copies (~600KB each at QUALITY → ~250 photos). Trimmed oldest-first.
    const val BUDGET_BYTES = 160L * 1024 * 1024
    const val QUALITY = 88
    // Originals within this factor of the target aren't worth a copy (little texture saving).
    private const val MIN_RATIO = 1.25

    /** Stable, path-safe file name for the copy of an original with this identity. */
    fun keyFor(path: String, bytes: Long, mtime: Long): String {
      val d = MessageDigest.getInstance("SHA-1").digest("$path|$bytes|$mtime".toByteArray())
      return d.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /** Largest power-of-two subsample that still leaves the long edge ≥ [target] (decoder-efficient). */
    fun sampleSizeFor(longest: Int, target: Int): Int =
        if (longest > target) Integer.highestOneBit(longest / target).coerceAtLeast(1) else 1

    fun needsScaling(w: Int, h: Int, target: Int): Boolean = maxOf(w, h) > target * MIN_RATIO

    /** Exact output size: long edge = [target], aspect preserved. */
    fun targetSize(w: Int, h: Int, target: Int): Pair<Int, Int> {
      val s = target.toDouble() / maxOf(w, h)
      return Math.round(w * s).toInt().coerceAtLeast(1) to Math.round(h * s).toInt().coerceAtLeast(1)
    }

    /** Names to delete, oldest first, until the remaining total fits [budgetBytes]. */
    fun evictionPlan(entries: List<Entry>, budgetBytes: Long): List<String> {
      var total = entries.sumOf { it.bytes }
      val victims = ArrayList<String>()
      for (e in entries.sortedBy { it.writtenAt }) {
        if (total <= budgetBytes) break
        victims.add(e.name)
        total -= e.bytes
      }
      return victims
    }
  }
}
