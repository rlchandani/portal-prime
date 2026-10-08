/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.max
import kotlin.math.min

/** Geometry uses upright bitmap pixels and viewport pixels, independent of Android views. */
object SlideshowGeometry {
  /** A credible protected box has finite, ordered coordinates entirely inside the image. */
  data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

  /** Maps a bitmap point to the viewport as (scale * px + x, scale * py + y). */
  data class Transform(val scale: Float, val x: Float, val y: Float)

  data class Motion(val start: Transform, val end: Transform, val whole: Boolean) {
    /**
     * All coverage/containment inequalities are linear in (scale, x, y). Their feasible set
     * is convex, so sharing one interpolation parameter preserves them along the entire path.
     * Callers may ease progress, but must not ease scale and translation independently.
     */
    fun at(progress: Float): Transform {
      val t = if (progress.isNaN()) 0.0 else progress.coerceIn(0f, 1f).toDouble()
      if (t == 0.0) return start
      if (t == 1.0) return end
      fun lerp(a: Float, b: Float) = (a.toDouble() * (1.0 - t) + b.toDouble() * t).toFloat()
      return Transform(lerp(start.scale, end.scale), lerp(start.x, end.x), lerp(start.y, end.y))
    }
  }

  /**
   * Missing/invalid analysis, whole-photo selection and incompatible groups always contain.
   * Valid boxes get 20% side/bottom padding and 30% top headroom, clipped to the image.
   * Fill is admitted only if the complete padded union fits. Motion adds at most 3% zoom.
   */
  fun plan(
      imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int,
      regions: List<Region>, fill: Boolean, motion: Boolean, reverse: Boolean = false
  ): Motion {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
      val empty = Transform(0f, 0f, 0f)
      return Motion(empty, empty, true)
    }
    val iw = imageWidth.toDouble()
    val ih = imageHeight.toDouble()
    val vw = viewportWidth.toDouble()
    val vh = viewportHeight.toDouble()
    val contain = roundDown(min(vw / iw, vh / ih))
    val whole = Transform(contain, ((vw - iw * contain) / 2).toFloat(),
        ((vh - ih * contain) / 2).toFloat())
    val fallback = Motion(whole, whole, true)
    // A corrupt/unbounded metadata list must not monopolize a preparation worker.
    if (!fill || regions.isEmpty() || regions.size > 256) return fallback

    var left = 1.0
    var top = 1.0
    var right = 0.0
    var bottom = 0.0
    for (region in regions) {
      if (!region.left.isFinite() || !region.top.isFinite() ||
          !region.right.isFinite() || !region.bottom.isFinite() ||
          region.left < 0f || region.top < 0f || region.right > 1f || region.bottom > 1f ||
          region.left >= region.right || region.top >= region.bottom) return fallback
      val width = region.right.toDouble() - region.left
      val height = region.bottom.toDouble() - region.top
      left = min(left, max(0.0, region.left - width * .2))
      top = min(top, max(0.0, region.top - height * .3))
      right = max(right, min(1.0, region.right + width * .2))
      bottom = max(bottom, min(1.0, region.bottom + height * .2))
    }
    left *= iw
    right *= iw
    top *= ih
    bottom *= ih
    val cover = roundUp(max(vw / iw, vh / ih))
    val safeMaximum = min(vw / (right - left), vh / (bottom - top))
    if (cover.toDouble() > safeMaximum) return fallback

    fun frame(scale: Float): Transform? {
      val s = scale.toDouble()
      val x = translation(max(vw - iw * s, -left * s), min(0.0, vw - right * s),
          vw / 2 - (left + right) * s / 2) ?: return null
      val y = translation(max(vh - ih * s, -top * s), min(0.0, vh - bottom * s),
          vh / 2 - (top + bottom) * s / 2) ?: return null
      return Transform(scale, x, y)
    }
    val start = frame(cover) ?: return fallback
    val endScale = if (motion) roundDown(min(cover.toDouble() * 1.03, safeMaximum)) else cover
    val end = if (endScale > cover) frame(endScale) ?: start else start
    return if (reverse) Motion(end, start, false) else Motion(start, end, false)
  }

  private fun roundDown(value: Double): Float {
    val result = value.toFloat()
    return if (result.toDouble() > value) Math.nextDown(result) else result
  }

  private fun roundUp(value: Double): Float {
    val result = value.toFloat()
    return if (result.toDouble() < value) Math.nextUp(result) else result
  }

  /** Reject an interval with no representable Float instead of forcing an unsafe crop. */
  private fun translation(low: Double, high: Double, target: Double): Float? {
    if (low > high) return null
    var result = target.coerceIn(low, high).toFloat()
    if (result.toDouble() < low) result = Math.nextUp(result)
    if (result.toDouble() > high) result = Math.nextDown(result)
    return result.takeIf { it.isFinite() && it.toDouble() >= low && it.toDouble() <= high }
  }
}
