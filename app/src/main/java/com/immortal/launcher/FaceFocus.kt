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
 * normalized point {fx, fy} — "the place a crop or pan should keep in frame". Pure and
 * detector-agnostic so the blend rules are unit-testable without ML Kit or a device.
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

  /** A normalized focal point: fx/fy in 0..1, fractions of image width/height. */
  data class Focus(val fx: Float, val fy: Float)

  // Faces narrower than this fraction of the image's short side are treated as detector
  // noise — in a wall-display photo a real subject's face is never a few pixels wide.
  private const val MIN_FACE_FRACTION = 0.04f

  // How strongly the focus leans toward the faces' area-weighted centroid rather than
  // the plain union center. 0 = pure union center (can land on empty space between a
  // close subject and a distant bystander); 1 = centroid only (a big foreground face
  // drowns out a group). Halfway keeps groups framed while favoring the real subject.
  // Equal-size faces have centroid == union center, so this never skews a balanced pair.
  private const val CENTROID_PULL = 0.5f

  /**
   * Reduce [faces] (in pixel coords of a [w]x[h] image) to one normalized focal point,
   * or null when there is nothing trustworthy to focus on (caller falls back to center).
   */
  fun focalPoint(faces: List<Box>, w: Int, h: Int): Focus? {
    if (w <= 0 || h <= 0) return null
    val minSide = minOf(w, h) * MIN_FACE_FRACTION
    val real = faces.filter { it.width >= minSide && it.height >= minSide }
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
