/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.util.Log

/**
 * Everything the premium stage and overlay derive from one photo, built once on the
 * preparation worker: a small pre-blurred copy (backdrop fill and frosted cards) and the
 * [PhotoAnalysis]. The backdrop is a few hundred KB and is left to the garbage collector,
 * because a display list may still reference it after its photo leaves the screen.
 */
internal class PhotoLook(val backdrop: Bitmap, val analysis: PhotoAnalysis) {
  companion object {
    /** Backdrop long edge: one eighth of the panel, kept between 160 and 320 pixels. */
    fun backdropEdge(displayLongEdge: Int): Int = (displayLongEdge / 8).coerceIn(160, 320)

    /** Blur radius in backdrop pixels; about 3% of the panel's long edge once scaled up. */
    private const val BLUR_RADIUS = 4

    /** Worker thread only, while the caller still owns [photo]. Null on any failure. */
    fun derive(photo: Bitmap, displayLongEdge: Int): PhotoLook? {
      if (photo.isRecycled || photo.width <= 0 || photo.height <= 0) return null
      var small: Bitmap? = null
      var thumb: Bitmap? = null
      return try {
        val edge = backdropEdge(displayLongEdge)
        val scale = edge.toFloat() / maxOf(photo.width, photo.height)
        val w = (photo.width * scale).toInt().coerceAtLeast(1)
        val h = (photo.height * scale).toInt().coerceAtLeast(1)
        small = Bitmap.createScaledBitmap(photo, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        // Analysis reads a still-sharp thumbnail; the backdrop is blurred afterwards.
        val tScale = PhotoAnalysis.THUMB_EDGE.toFloat() / maxOf(w, h)
        val tw = (w * tScale).toInt().coerceIn(1, w)
        val th = (h * tScale).toInt().coerceIn(1, h)
        thumb = Bitmap.createScaledBitmap(small, tw, th, true)
        val thumbPixels = IntArray(tw * th)
        thumb.getPixels(thumbPixels, 0, tw, 0, 0, tw, th)
        val analysis = PhotoAnalysis.of(thumbPixels, tw, th)
        AmbientBlur.blur(pixels, w, h, BLUR_RADIUS)
        val backdrop = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        backdrop.prepareToDraw()
        PhotoLook(backdrop, analysis)
      } catch (error: RuntimeException) {
        Log.w("ImmortalSlideshow", "photo look unavailable: ${error.javaClass.simpleName}")
        null
      } catch (error: OutOfMemoryError) {
        Log.w("ImmortalSlideshow", "photo look skipped: out of memory")
        null
      } finally {
        if (thumb != null && thumb !== small && thumb !== photo) thumb.recycle()
        if (small != null && small !== photo) small.recycle()
      }
    }
  }
}
