/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SlideshowGeometry.Motion
import com.immortal.launcher.SlideshowGeometry.Region
import com.immortal.launcher.SlideshowGeometry.Transform
import kotlin.math.max
import kotlin.math.min

/**
 * Smart frame framing: subject-aware cover crops, the aspect-mismatch rule, and a short settle
 * path. Pure geometry in bitmap/viewport pixels, independent of Android views.
 *
 * - A photo whose aspect differs from the viewport by more than [MISMATCH] (e.g. a portrait on
 *   the Portal+, or a landscape on a portrait Portal Mini) is shown whole at [INSET] of its
 *   contain size, over its own blurred backdrop.
 * - Otherwise it covers the viewport, with its subject placed near [Target] and never revealing
 *   an edge. Known faces must stay inside the frame with the same padding the classic Smart
 *   frame used; if they cannot, the photo is shown whole instead of cropping a person.
 * - Motion is a settle: one zoom of [ZOOM_FROM]..[ZOOM_TO] with a small lateral glide. Coverage
 *   and face constraints are linear in (scale, x, y), so every point between two admissible
 *   endpoints is admissible too; callers may ease the shared progress.
 */
object PremiumFraming {
  const val MISMATCH = 1.4
  const val INSET = .86f
  const val INSET_ZOOM_TO = .872f
  const val ZOOM_FROM = 1.015f
  const val ZOOM_TO = 1.065f
  /** Lateral glide as a fraction of the viewport width. */
  const val GLIDE = .012f

  /** Where the subject should land, as viewport fractions (rule-of-thirds leaning). */
  data class Target(val x: Float, val y: Float) {
    companion object {
      /** Subject leans right, away from the bottom-left clock. */
      val DEFAULT = Target(.58f, .42f)
    }
  }

  /** A focus point in normalized image coordinates. */
  data class Focus(val x: Float, val y: Float)

  data class Frame(val motion: Motion, val contained: Boolean) {
    /** The photo's on-screen rectangle at the smaller end of its path (backdrop clipping). */
    fun innerRect(imageWidth: Int, imageHeight: Int): FloatArray {
      val t = if (motion.start.scale <= motion.end.scale) motion.start else motion.end
      return floatArrayOf(t.x, t.y, t.x + imageWidth * t.scale, t.y + imageHeight * t.scale)
    }
  }

  fun mismatch(imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int): Double {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return Double.MAX_VALUE
    val ia = imageWidth.toDouble() / imageHeight
    val va = viewportWidth.toDouble() / viewportHeight
    return max(ia / va, va / ia)
  }

  /**
   * @param regions known face boxes; null when analysis is missing or pending, empty when the
   *   photo was analysed and has no faces. Only consulted when [keepPeople] is set.
   * @param fill false when the user chose "fit" — always show the whole photo.
   * @param focus a saliency estimate, used when no faces are known; null centres the crop.
   */
  fun plan(
      imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int,
      regions: List<Region>?, keepPeople: Boolean, fill: Boolean, motion: Boolean,
      focus: Focus?, target: Target = Target.DEFAULT, reverse: Boolean = false,
      mismatchLimit: Double = MISMATCH,
  ): Frame {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
      val empty = Transform(0f, 0f, 0f)
      return Frame(Motion(empty, empty, true), true)
    }
    val contained = contained(imageWidth, imageHeight, viewportWidth, viewportHeight, motion, reverse)
    if (!fill || mismatch(imageWidth, imageHeight, viewportWidth, viewportHeight) > mismatchLimit) return contained
    val faces = if (keepPeople) regions.orEmpty() else emptyList()
    val safe = SafeBox.of(faces, imageWidth, imageHeight) ?: return contained
    // Known faces outrank a saliency estimate; with neither the crop stays centred.
    return cover(imageWidth, imageHeight, viewportWidth, viewportHeight, safe, motion,
        safe.focus() ?: focus ?: Focus(.5f, .5f), target, reverse) ?: contained
  }

  private fun contained(
      iw: Int, ih: Int, vw: Int, vh: Int, motion: Boolean, reverse: Boolean,
  ): Frame {
    val contain = min(vw.toDouble() / iw, vh.toDouble() / ih)
    fun centred(fraction: Float): Transform {
      val s = (contain * fraction).toFloat()
      return Transform(s, ((vw - iw * s.toDouble()) / 2).toFloat(), ((vh - ih * s.toDouble()) / 2).toFloat())
    }
    val start = centred(INSET)
    val end = if (motion) centred(INSET_ZOOM_TO) else start
    return Frame(if (reverse) Motion(end, start, true) else Motion(start, end, true), true)
  }

  private fun cover(
      iw: Int, ih: Int, vw: Int, vh: Int, safe: SafeBox, motion: Boolean,
      focus: Focus, target: Target, reverse: Boolean,
  ): Frame? {
    val cover = max(vw.toDouble() / iw, vh.toDouble() / ih)
    val ceiling = safe.maxScale(vw, vh)
    if (cover > ceiling) return null
    val glide = if (reverse) -GLIDE else GLIDE
    /** Place scale [s] so image point (fx, fy) lands near (desiredX, desiredY), staying admissible. */
    fun place(s: Double, desiredX: Double, desiredY: Double): Transform? {
      val x = clampInto(desiredX - focus.x * iw * s,
          max(vw - iw * s, -safe.left * s), min(0.0, vw - safe.right * s)) ?: return null
      val y = clampInto(desiredY - focus.y * ih * s,
          max(vh - ih * s, -safe.top * s), min(0.0, vh - safe.bottom * s)) ?: return null
      return Transform(s.toFloat(), x, y)
    }
    val from = min(cover * (if (motion) ZOOM_FROM else 1f), ceiling)
    val start = place(from, vw * target.x.toDouble(), vh * target.y.toDouble())
        ?: place(cover, vw * target.x.toDouble(), vh * target.y.toDouble()) ?: return null
    if (!motion) return Frame(Motion(start, start, false), false)
    // Zoom toward the subject where it now sits on screen, with a small lateral glide.
    val anchorX = start.x + focus.x * iw * start.scale.toDouble()
    val anchorY = start.y + focus.y * ih * start.scale.toDouble()
    val end = place(min(cover * ZOOM_TO, ceiling), anchorX + vw * glide, anchorY) ?: start
    return Frame(Motion(start, end, false), false)
  }

  /** Clamp into [low, high], keeping the Float result inside the exact Double interval. */
  private fun clampInto(value: Double, low: Double, high: Double): Float? {
    if (low > high) return null
    var result = value.coerceIn(low, high).toFloat()
    if (result.toDouble() < low) result = Math.nextUp(result)
    if (result.toDouble() > high) result = Math.nextDown(result)
    return result.takeIf { it.isFinite() && it.toDouble() in low..high }
  }

  /** The padded face union in image pixels; the whole-image box when there are no faces. */
  private class SafeBox(
      val left: Double, val top: Double, val right: Double, val bottom: Double,
      private val iw: Int, private val ih: Int, private val faces: List<Region>,
  ) {
    fun maxScale(vw: Int, vh: Int): Double =
        if (faces.isEmpty()) Double.MAX_VALUE else min(vw / (right - left), vh / (bottom - top))

    fun focus(): Focus? = if (faces.isEmpty()) null else regionFocus(faces)

    companion object {
      /** Same padding policy as [SlideshowGeometry.plan]; null rejects corrupt metadata. */
      fun of(faces: List<Region>, iw: Int, ih: Int): SafeBox? {
        if (faces.size > 256) return null
        if (faces.isEmpty()) return SafeBox(iw.toDouble(), ih.toDouble(), 0.0, 0.0, iw, ih, faces)
        var left = 1.0
        var top = 1.0
        var right = 0.0
        var bottom = 0.0
        for (r in faces) {
          if (!r.left.isFinite() || !r.top.isFinite() || !r.right.isFinite() || !r.bottom.isFinite() ||
              r.left < 0f || r.top < 0f || r.right > 1f || r.bottom > 1f ||
              r.left >= r.right || r.top >= r.bottom) return null
          val w = r.right.toDouble() - r.left
          val h = r.bottom.toDouble() - r.top
          left = min(left, max(0.0, r.left - w * .2))
          top = min(top, max(0.0, r.top - h * .3))
          right = max(right, min(1.0, r.right + w * .2))
          bottom = max(bottom, min(1.0, r.bottom + h * .2))
        }
        return SafeBox(left * iw, top * ih, right * iw, bottom * ih, iw, ih, faces)
      }
    }
  }

  /** Area-weighted face focus, the same pull [FaceFocus.focalPoint] applies to raw boxes. */
  fun regionFocus(regions: List<Region>): Focus? {
    val boxes = regions.map {
      FaceFocus.Box((it.left * SCALE).toInt(), (it.top * SCALE).toInt(),
          (it.right * SCALE).toInt(), (it.bottom * SCALE).toInt())
    }
    return FaceFocus.focalPoint(boxes, SCALE, SCALE)?.let { Focus(it.fx, it.fy) }
  }

  private const val SCALE = 10_000
}
