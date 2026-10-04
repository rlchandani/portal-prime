/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Face-aware focal points for screensaver photos: where in each image the faces are, so
 * crops and pans can keep them in frame instead of decapitating a portrait.
 *
 * Detection (ML Kit, bundled model, "fast" mode) runs once per photo on a ~320px decode,
 * off the UI thread; results are reduced by [FaceFocus.focalPoint] and cached to a JSON
 * file keyed by URL, so the cost is paid once per photo ever. Photos without a cached
 * entry simply have no focal point yet — callers fall back to center framing, and the
 * entry appears on the next screensaver start. All failures are swallowed to null: this
 * runs inside the always-on dream process, and a broken detector must never take it down.
 */
class FaceFocusStore(private val context: Context) {

  private val cacheFile: File get() = File(context.filesDir, "face_focus.json")

  // URL -> "fx,fy" ("" = analyzed, no faces). Loaded once; missing/corrupt file = empty.
  private val cache: MutableMap<String, String> by lazy { load() }

  private fun load(): MutableMap<String, String> =
      runCatching {
        val o = JSONObject(cacheFile.readText())
        val m = HashMap<String, String>(o.length())
        o.keys().forEach { k -> m[k] = o.optString(k) }
        m
      }.getOrElse { HashMap() }

  @Synchronized
  private fun persist() {
    runCatching {
      val o = JSONObject()
      cache.forEach { (k, v) -> o.put(k, v) }
      cacheFile.writeText(o.toString())
    }
  }

  /** The cached focal point for [url], or null if unanalyzed / no faces found. */
  fun focusFor(url: String): FaceFocus.Focus? {
    val v = cache[url] ?: return null
    if (v.isEmpty()) return null
    val parts = v.split(",")
    if (parts.size < 2) return null
    val fx = parts[0].toFloatOrNull() ?: return null
    val fy = parts[1].toFloatOrNull() ?: return null
    return FaceFocus.Focus(fx, fy)
  }

  /** The cached people-weight (0..1) for [url]; 0 if unanalyzed / no faces. */
  fun weightFor(url: String): Float {
    val v = cache[url] ?: return 0f
    if (v.isEmpty()) return 0f
    return v.split(",").getOrNull(2)?.toFloatOrNull() ?: 0f
  }

  /**
   * Analyze every not-yet-cached photo in [urls] (file:// or http(s), [authHeaders] for
   * the latter), persisting as it goes. Blocking — call from a background executor. Stops
   * quietly on repeated failures so an unreachable source can't spin the worker forever.
   */
  fun warmUp(urls: List<String>, authHeaders: Map<String, String> = emptyMap()) {
    Log.i(TAG, "warmUp: entered with ${urls.size} urls")
    // Unanalyzed photos, plus pre-weight cache entries ("fx,fy" without the third field)
    // that need one re-pass to gain a people-weight. ""-entries (no faces) stay final.
    val pending =
        urls.filter { u ->
          val v = cache[u]
          v == null || (v.isNotEmpty() && v.split(",").size < 3)
        }
    if (pending.isEmpty()) return
    Log.i(TAG, "warmUp: ${pending.size} photos pending; building detector")
    val detector =
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.06f)
                .build())
    Log.i(TAG, "warmUp: detector ready; analyzing")
    var failStreak = 0
    try {
      for (url in pending) {
        val entry = runCatching { detect(detector, url, authHeaders) }.getOrNull()
        if (entry == null && ++failStreak >= MAX_FAIL_STREAK) {
          Log.w(TAG, "face warm-up aborted after $failStreak consecutive failures")
          break
        }
        if (entry != null) failStreak = 0
        // null = decode/detect failed (retry next run); "" = analyzed, no faces (final).
        if (entry != null) cache[url] = entry
        persist()
      }
    } finally {
      runCatching { detector.close() }
      Log.i(TAG, "warmUp: finished; cache now has ${cache.size} entries")
    }
  }

  /**
   * Decode a small bitmap for [url] and run detection. Returns the cache entry
   * ("fx,fy,weight"; "" when the photo analyzed clean but has no faces) or null on failure.
   */
  private fun detect(
      detector: com.google.mlkit.vision.face.FaceDetector,
      url: String,
      authHeaders: Map<String, String>,
  ): String? {
    val bmp = decodeSmall(url, authHeaders) ?: return null
    try {
      val faces =
          Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), DETECT_TIMEOUT_S, TimeUnit.SECONDS)
      val boxes = faces.map {
        FaceFocus.Box(it.boundingBox.left, it.boundingBox.top, it.boundingBox.right, it.boundingBox.bottom)
      }
      val focus = FaceFocus.focalPoint(boxes, bmp.width, bmp.height) ?: return ""
      val weight = FaceFocus.peopleWeight(boxes, bmp.width, bmp.height)
      return "${focus.fx},${focus.fy},$weight"
    } finally {
      bmp.recycle()
    }
  }

  /** Decode [url] at roughly [TARGET_PX] on the long side — plenty for face boxes. */
  private fun decodeSmall(url: String, authHeaders: Map<String, String>): Bitmap? {
    val bytes: ByteArray =
        when {
          url.startsWith("file://") -> runCatching { File(url.removePrefix("file://")).readBytes() }.getOrNull()
          url.startsWith("http") -> Http.getBytes(url, headers = authHeaders)
          else -> null
        } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }

  companion object {
    private const val TAG = "FaceFocusStore"
    private const val TARGET_PX = 320
    private const val DETECT_TIMEOUT_S = 10L
    private const val MAX_FAIL_STREAK = 8
  }
}
