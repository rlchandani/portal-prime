/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object WallpaperPreviewBudget {
  const val WIDTH = 240
  const val HEIGHT = 145
  const val MAX_DECODE_EDGE = WIDTH * 2
}

/** Reuse requested cache entries; one worker decodes misses and owns only new unpublished values. */
internal suspend fun <K, V> loadWallpaperPreviews(
    keys: List<K>, decode: (K) -> V?, discard: (V) -> Unit,
    existing: Map<K, V> = emptyMap(),
): Map<K, V> {
  val owned = LinkedHashMap<K, V>()
  var transferred = false
  try {
    val loaded = withContext(Dispatchers.IO) {
      val requested = LinkedHashMap<K, V>()
      for (key in keys) {
        ensureActive()
        if (requested.containsKey(key)) continue
        if (existing.containsKey(key)) {
          requested[key] = existing.getValue(key)
          continue
        }
        val next = try { decode(key) } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        if (next != null) {
          owned[key] = next
          requested[key] = next
        }
        ensureActive()
      }
      requested.toMap()
    }
    transferred = true
    return loaded
  } finally {
    if (!transferred) owned.values.forEach { runCatching { discard(it) } }
  }
}

/** Reuse the bounded, upright slideshow decoder; preserve the existing 240 × 145 presentation. */
internal fun decodeWallpaperPreview(context: Context, id: String): Bitmap? = try {
  val sampled = context.assets.open("photoframe_fallback/${id.removePrefix(WallpaperConfig.PHOTO_PREFIX)}")
      .use { SlideshowDecoder.stream(it, WallpaperPreviewBudget.MAX_DECODE_EDGE) }
  if (sampled == null) null else {
    var scaled: Bitmap? = null
    try {
      scaled = Bitmap.createScaledBitmap(sampled, WallpaperPreviewBudget.WIDTH, WallpaperPreviewBudget.HEIGHT, true)
      scaled
    } finally { if (scaled !== sampled) sampled.recycle() }
  }
} catch (_: java.io.IOException) { null }
  catch (_: RuntimeException) { null }
  catch (_: OutOfMemoryError) { null }
