/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs
import kotlin.math.hypot

/** A 2-D point in pixels (or normalised units, by context). */
internal data class Pt(val x: Float, val y: Float)

/**
 * A 2×3 affine map `x' = a·x + b·y + c`, `y' = d·x + e·y + f`. Immutable; used both ways in the
 * face pipeline (frame → model input for sampling, model input → frame for detections).
 */
internal data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
  fun map(p: Pt): Pt = Pt(a * p.x + b * p.y + c, d * p.x + e * p.y + f)

  fun inverse(): Affine {
    val det = a * e - b * d
    require(abs(det) > 1e-12f) { "affine is not invertible" }
    val ia = e / det
    val ib = -b / det
    val id = -d / det
    val ie = a / det
    return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
  }

  /** Apply this map first, then [other]: `other(this(p))`. */
  fun then(other: Affine): Affine = Affine(
      other.a * a + other.b * d, other.a * b + other.b * e, other.a * c + other.b * f + other.c,
      other.d * a + other.e * d, other.d * b + other.e * e, other.d * c + other.e * f + other.f)

  companion object {
    val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)
  }
}

internal object FaceGeometry {
  /** SFace/ArcFace 112×112 five-point template: eyes, nose, mouth corners (image left first). */
  val TEMPLATE = listOf(Pt(38.2946f, 51.6963f), Pt(73.5318f, 51.5014f), Pt(56.0252f, 71.7366f),
      Pt(41.5493f, 92.3655f), Pt(70.7299f, 92.2041f))
  /** BlazeFace gives one mouth-centre point, so we align to the corners' midpoint. */
  val TEMPLATE_4 = listOf(TEMPLATE[0], TEMPLATE[1], TEMPLATE[2],
      Pt((TEMPLATE[3].x + TEMPLATE[4].x) / 2, (TEMPLATE[3].y + TEMPLATE[4].y) / 2))
  const val ALIGNED_SIZE = 112

  /**
   * Least-squares similarity (rotation, uniform scale, translation) mapping [src] onto [dst]
   * (Umeyama without reflection). Returns null for degenerate input.
   */
  fun similarity(src: List<Pt>, dst: List<Pt>): Affine? {
    if (src.size != dst.size || src.size < 2) return null
    val n = src.size.toFloat()
    val sx = src.sumOf { it.x.toDouble() }.toFloat() / n
    val sy = src.sumOf { it.y.toDouble() }.toFloat() / n
    val dx = dst.sumOf { it.x.toDouble() }.toFloat() / n
    val dy = dst.sumOf { it.y.toDouble() }.toFloat() / n
    var num1 = 0f
    var num2 = 0f
    var den = 0f
    for (i in src.indices) {
      val px = src[i].x - sx
      val py = src[i].y - sy
      val qx = dst[i].x - dx
      val qy = dst[i].y - dy
      num1 += px * qx + py * qy
      num2 += px * qy - py * qx
      den += px * px + py * py
    }
    if (den < 1e-6f) return null
    val a = num1 / den // s·cosθ
    val b = num2 / den // s·sinθ
    return Affine(a, -b, dx - (a * sx - b * sy), b, a, dy - (b * sx + a * sy))
  }

  /**
   * The transform that maps a frame onto the 112×112 aligned face, from BlazeFace keypoints
   * (eyes, nose tip, mouth centre). Eyes are ordered by image x, so a mirrored stream aligns too.
   */
  fun alignment(eyeA: Pt, eyeB: Pt, nose: Pt, mouth: Pt): Affine? {
    val (left, right) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
    return similarity(listOf(left, right, nose, mouth), TEMPLATE_4)
  }

  /** Inter-ocular distance, the scale reference for pose and size checks. */
  fun eyeDistance(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

  /**
   * Rough head pose from keypoints, in eye-distance units: yaw ≈ nose offset sideways from the
   * eye midpoint, pitch ≈ nose drop below the eye line. Enough to guide "turn left / tip up".
   */
  fun pose(eyeA: Pt, eyeB: Pt, nose: Pt): Pair<Float, Float> {
    val dist = eyeDistance(eyeA, eyeB).coerceAtLeast(1e-3f)
    val midX = (eyeA.x + eyeB.x) / 2
    val midY = (eyeA.y + eyeB.y) / 2
    return (nose.x - midX) / dist to (nose.y - midY) / dist
  }

  /**
   * Letterbox a `srcW × srcH` frame, rotated by [rotation] degrees (0/90/180/270, clockwise, to
   * make faces upright), into a `size × size` square. Returns model-input → frame.
   */
  fun letterbox(srcW: Int, srcH: Int, size: Int, rotation: Int): Affine {
    val rot = ((rotation % 360) + 360) % 360
    val (uw, uh) = if (rot == 90 || rot == 270) srcH to srcW else srcW to srcH
    val scale = size.toFloat() / maxOf(uw, uh)
    val padX = (size - uw * scale) / 2
    val padY = (size - uh * scale) / 2
    // input → upright frame
    val toUpright = Affine(1 / scale, 0f, -padX / scale, 0f, 1 / scale, -padY / scale)
    // upright frame → raw frame
    val toRaw = when (rot) {
      90 -> Affine(0f, 1f, 0f, -1f, 0f, srcH.toFloat())      // upright(x,y) = raw(H - y, x)
      180 -> Affine(-1f, 0f, srcW.toFloat(), 0f, -1f, srcH.toFloat())
      270 -> Affine(0f, -1f, srcW.toFloat(), 1f, 0f, 0f)     // upright(x,y) = raw(y, W - x)
      else -> Affine.IDENTITY
    }
    return toUpright.then(toRaw)
  }
}
