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
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepared local-photo derivatives for the HTML engine. Slot sizes round up to bounded tiers;
 * full-screen requests retain zoom headroom. The bridge only stats ready files and enqueues work.
 * One process-wide low-priority decoder serves all frame owners, with a bounded pending queue.
 * File identity includes size/mtime and tier. Completed atomic writes alone are exposed to WebView.
 */
class EngineScaledCache(
    private val dir: File,
    private val targetEdge: Int = TARGET_EDGE,
    private val budgetBytes: Long = BUDGET_BYTES,
) {
  data class Entry(val name: String, val bytes: Long, val writtenAt: Long)
  init { require(targetEdge in 1..TARGET_EDGE); require(budgetBytes >= 0) }

  private val owner = work.owner()
  private val warming = AtomicBoolean(false)
  private var maintained = false // Accessed only by the shared serial decoder.
  // Remember small originals without repeatedly opening their headers. Identity changes on edits.
  private val unneeded = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 1200
  }

  fun resolve(originalPath: String, requestedEdge: Int = targetEdge): File? {
    val original = File(originalPath)
    if (!original.isFile) return null
    return ready(original, derivativeEdge(requestedEdge, targetEdge))
  }

  /** Return a ready copy now; asynchronously prepare the requested tier if it is absent. */
  fun resolveAndRequest(originalPath: String, requestedEdge: Int): File? {
    val original = File(originalPath)
    if (!original.isFile) return null
    val edge = derivativeEdge(requestedEdge, targetEdge)
    ready(original, edge)?.let { return it }
    request(original, edge)
    // A ready larger copy is safe while the smaller one is prepared. Never upscale a smaller copy.
    for (larger in TIERS) {
      if (larger > edge && larger <= targetEdge) ready(original, larger)?.let { return it }
    }
    if (targetEdge > edge) return ready(original, targetEdge)
    return null
  }

  private fun ready(original: File, edge: Int): File? =
      File(dir, keyFor(original.path, original.length(), original.lastModified(), edge))
          .takeIf { it.isFile && it.length() > 0L }

  /** One queued warm step at a time; all enumeration/stats happen off UI, behind visible requests. */
  fun warm(paths: List<String>) {
    if (!warming.compareAndSet(false, true)) return
    val pending = paths.take(300)
    fun enqueue(index: Int) {
      if (index >= pending.size) { warming.set(false); return }
      val accepted = owner.submit("warm:$index") { current ->
        val original = File(pending[index])
        if (current() && original.isFile && ready(original, targetEdge) == null) {
          val bytes = original.length()
          val modified = original.lastModified()
          val key = keyFor(original.path, bytes, modified, targetEdge)
          if (!synchronized(unneeded) { unneeded.containsKey(key) }) {
            runCatching { scaleOne(original, bytes, modified, targetEdge, key, current) }
                .onFailure { Log.w(TAG, "Local photo derivative failed: ${it.javaClass.simpleName}") }
          }
        }
        // Tail-enqueue after each photo: already queued visible requests run before the next warm.
        if (current()) enqueue(index + 1) else warming.set(false)
      }
      if (!accepted) warming.set(false) // Warming is expendable when visible work fills the queue.
    }
    enqueue(0)
  }

  fun cancel() = owner.close()

  private fun request(original: File, edge: Int) {
    val bytes = original.length()
    val modified = original.lastModified()
    val key = keyFor(original.path, bytes, modified, edge)
    if (synchronized(unneeded) { unneeded.containsKey(key) }) return
    owner.submit(key) { current ->
      runCatching { scaleOne(original, bytes, modified, edge, key, current) }
          .onFailure { Log.w(TAG, "Local photo derivative failed: ${it.javaClass.simpleName}") }
    }
  }

  private fun scaleOne(original: File, bytes: Long, modified: Long, edge: Int, key: String,
      current: () -> Boolean) {
    fun valid(): Boolean = current() && original.isFile && original.length() == bytes &&
        original.lastModified() == modified
    if (!valid() || ready(original, edge) != null) return
    if (!maintained) {
      // With one decoder globally, no other job can own a partial in this cache directory.
      dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { it.delete() }
      trimToBudget()
      maintained = true
    }
    // Pin one opened revision. An atomic path replacement must not swap the header and pixels.
    FileInputStream(original).use { input ->
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeStream(input, null, bounds)
      val w = bounds.outWidth
      val h = bounds.outHeight
      if (w <= 0 || h <= 0 || !valid()) return
      if (!needsScaling(w, h, edge)) {
        synchronized(unneeded) { unneeded[key] = true }
        return
      }
      val opts = BitmapFactory.Options().apply {
        inSampleSize = boundedSampleSize(w, h, edge)
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        if (Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
      }
      input.channel.position(0)
      val src = BitmapFactory.decodeStream(input, null, opts) ?: return
      var out: Bitmap? = null
      val dst = File(dir, key)
      val tmp = File(dir, key + ".part")
      try {
        if (!valid() || src.width.toLong() * src.height > MAX_SOURCE_PIXELS) return
        val outputEdge = minOf(edge, maxOf(src.width, src.height))
        val (tw, th) = targetSize(src.width, src.height, outputEdge)
        val matrix = Matrix().apply { postScale(tw.toFloat() / src.width, th.toFloat() / src.height) }
        input.channel.position(0)
        applyExifRotation(matrix, input, tw, th)
        out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        if (!valid()) return
        dir.mkdirs()
        FileOutputStream(tmp).use {
          check(out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) { "JPEG compression failed" }
        }
        if (!valid()) return
        check(tmp.renameTo(dst)) { "Derivative publication failed" }
        trimToBudget()
      } finally {
        out?.takeIf { !it.isRecycled }?.recycle()
        if (!src.isRecycled) src.recycle()
        tmp.delete()
      }
    }
  }

  /** Bake the EXIF orientation into the copy (a rotated copy has no tag, so it renders upright). */
  private fun applyExifRotation(m: Matrix, input: InputStream, tw: Int, th: Int) {
    val o =
        runCatching {
              ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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
    // At most ~48 MiB of ARGB source pixels plus one <=2400-edge output; one decoder globally.
    const val MAX_SOURCE_PIXELS = 12L * 1024 * 1024
    private val TIERS = intArrayOf(600, 1200, 1800, TARGET_EDGE)
    private val work = EngineDerivativeWork()

    fun derivativeEdge(requested: Int, maximum: Int = TARGET_EDGE): Int {
      val cap = maximum.coerceIn(1, TARGET_EDGE)
      val wanted = if (requested <= 0) cap else requested.coerceAtMost(cap)
      return (TIERS.firstOrNull { it >= wanted } ?: cap).coerceAtMost(cap)
    }

    fun boundedSampleSize(w: Int, h: Int, target: Int): Int {
      var sample = sampleSizeFor(maxOf(w, h), target.coerceAtLeast(1))
      while (((w.toLong() + sample - 1) / sample) * ((h.toLong() + sample - 1) / sample) > MAX_SOURCE_PIXELS) {
        sample *= 2
      }
      return sample
    }
    // Originals within this factor of the target aren't worth a copy (little texture saving).
    private const val MIN_RATIO = 1.25

    /** Stable, path-safe file name for the copy of an original with this identity. */
    fun keyFor(path: String, bytes: Long, mtime: Long, edge: Int = TARGET_EDGE): String {
      val identity = "$path|$bytes|$mtime" + if (edge == TARGET_EDGE) "" else "|edge=$edge"
      val d = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray())
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
