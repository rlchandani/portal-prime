/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Focal-point math for face-aware screensaver framing.
 *
 * The detector (see [FaceFocusStore]) hands over raw face boxes; this reduces them to one
 * normalized point {fx, fy} and optionally their raw union bounds for crop consumers.
 * Pure and detector-agnostic so the math is unit-testable without ML Kit or a device.
 */
object FaceFocus {

  /** A face bounding box in bitmap pixel coordinates (may poke past the bitmap edge). */
  data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
  }

  /** Raw normalized union of real face boxes; crop consumers choose their own padding. */
  data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    internal fun isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            left < right && top < bottom
  }

  /** A normalized focal point, optionally accompanied by the region to keep in frame. */
  data class Focus(val fx: Float, val fy: Float, val bounds: Bounds? = null)

  // Faces narrower than this fraction of the image's short side are treated as detector
  // noise — in a wall-display photo a real subject's face is never a few pixels wide.
  private const val MIN_FACE_FRACTION = 0.04f

  // How strongly the focus leans toward the faces' area-weighted centroid rather than
  // the plain union center. 0 = pure union center (can land on empty space between a
  // close subject and a distant bystander); 1 = centroid only (a big foreground face
  // drowns out a group). Halfway keeps groups framed while favoring the real subject.
  // Equal-size faces have centroid == union center, so this never skews a balanced pair.
  private const val CENTROID_PULL = 0.5f

  /** Union real boxes after clipping to the image, excluding noise and empty intersections. */
  fun boundsFor(faces: List<Box>, w: Int, h: Int): Bounds? {
    if (w <= 0 || h <= 0) return null
    val minSide = minOf(w, h) * MIN_FACE_FRACTION
    val real = faces.filter { it.width >= minSide && it.height >= minSide }.map {
      Box(it.left.coerceIn(0, w), it.top.coerceIn(0, h),
          it.right.coerceIn(0, w), it.bottom.coerceIn(0, h))
    }.filter { it.width > 0 && it.height > 0 }
    if (real.isEmpty()) return null
    return Bounds(real.minOf { it.left }.toFloat() / w, real.minOf { it.top }.toFloat() / h,
        real.maxOf { it.right }.toFloat() / w, real.maxOf { it.bottom }.toFloat() / h)
  }

  /**
   * How "people-centric" a photo is, 0..1: 0 = no (real) faces, rising with face count and
   * the fraction of frame they cover, saturating at 1. Drives the People screensaver bias —
   * when someone is watching, the deck leans toward photos of people.
   */
  fun peopleWeight(faces: List<Box>, w: Int, h: Int): Float {
    if (w <= 0 || h <= 0) return 0f
    val minSide = minOf(w, h) * MIN_FACE_FRACTION
    val real = faces.filter { it.width >= minSide && it.height >= minSide &&
        it.right > 0 && it.bottom > 0 && it.left < w && it.top < h }
    if (real.isEmpty()) return 0f
    val coverage = real.sumOf { it.area }.toFloat() / (w.toLong() * h.toLong())
    // Count and coverage both matter: a group of small faces ≈ one large portrait.
    val score = real.size * 0.25f + coverage * 4f
    return score.coerceIn(0f, 1f)
  }

  /**
   * Reduce [faces] (in pixel coords of a [w]x[h] image) to one normalized focal point,
   * or null when there is nothing trustworthy to focus on (caller falls back to center).
   */
  fun focalPoint(faces: List<Box>, w: Int, h: Int): Focus? {
    if (w <= 0 || h <= 0) return null
    val minSide = minOf(w, h) * MIN_FACE_FRACTION
    val real = faces.filter { it.width >= minSide && it.height >= minSide &&
        it.right > 0 && it.bottom > 0 && it.left < w && it.top < h }
    if (real.isEmpty()) return null

    val union = Box(
        left = real.minOf { it.left },
        top = real.minOf { it.top },
        right = real.maxOf { it.right },
        bottom = real.maxOf { it.bottom },
    )
    val totalArea = real.sumOf { it.area }.toFloat()
    val centroidX = real.sumOf { (it.cx * it.area).toDouble() }.toFloat() / totalArea
    val centroidY = real.sumOf { (it.cy * it.area).toDouble() }.toFloat() / totalArea
    val fx = (union.cx + (centroidX - union.cx) * CENTROID_PULL) / w
    val fy = (union.cy + (centroidY - union.cy) * CENTROID_PULL) / h
    return Focus(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
  }
}
