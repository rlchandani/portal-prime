/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.util.LruCache

/**
 * Small, cached square thumbnails for the folder picker. Images are decoded
 * downsampled; videos use a single decoded frame. Cheap and bounded — call off the
 * main thread.
 */
object Thumbnails {

  // Bounded LRU so browsing big folders can't grow memory without limit.
  private val cache = LruCache<String, Bitmap>(96)

  /** Drops least recently used thumbnails down to [fraction] of the cache (memory pressure). */
  fun trim(fraction: Float) = cache.trimToSize(MemoryTrimPolicy.budget(cache.maxSize(), fraction))

  /** Square thumbnail [px] wide for [item], or null if it can't be decoded. */
  fun get(item: MediaItem, px: Int): Bitmap? {
    cache.get(item.path)?.let {
      return it
    }
    val bmp =
        runCatching { if (item.isVideo) videoThumb(item.path, px) else imageThumb(item.path, px) }
            .getOrNull()
    if (bmp != null) cache.put(item.path, bmp)
    return bmp
  }

  private fun imageThumb(path: String, px: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (w / (sample * 2) >= px && h / (sample * 2) >= px) sample *= 2
    val full =
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
    return ThumbnailUtils.extractThumbnail(full, px, px)
  }

  private fun videoThumb(path: String, px: Int): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
      mmr.setDataSource(path)
      val frame = mmr.getFrameAtTime(0) ?: return null
      ThumbnailUtils.extractThumbnail(frame, px, px)
    } catch (t: Throwable) {
      null
    } finally {
      runCatching { mmr.release() }
    }
  }
}
