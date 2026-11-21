/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.TimeInterpolator
import kotlin.math.abs

/**
 * A CSS-style cubic-bezier curve (the same curve as `PathInterpolator(x1, y1, x2, y2)`), solved
 * with a few Newton steps plus bisection. Pure Kotlin so the curves the mock specifies can be
 * unit-tested on the JVM and shared by animators and per-frame drawing code.
 */
class CubicEase(x1: Float, private val y1: Float, x2: Float, private val y2: Float) : TimeInterpolator {
  private val cx = 3f * x1
  private val bx = 3f * (x2 - x1) - cx
  private val ax = 1f - cx - bx
  private val cy = 3f * y1
  private val by = 3f * (y2 - y1) - cy
  private val ay = 1f - cy - by

  init {
    require(x1 in 0f..1f && x2 in 0f..1f) { "cubic-bezier x control points must lie in 0..1" }
  }

  private fun sampleX(t: Float) = ((ax * t + bx) * t + cx) * t
  private fun sampleY(t: Float) = ((ay * t + by) * t + cy) * t
  private fun slopeX(t: Float) = (3f * ax * t + 2f * bx) * t + cx

  override fun getInterpolation(input: Float): Float {
    if (input.isNaN() || input <= 0f) return 0f
    if (input >= 1f) return 1f
    var t = input
    repeat(NEWTON_STEPS) {
      val error = sampleX(t) - input
      if (abs(error) < EPSILON) return sampleY(t)
      val slope = slopeX(t)
      if (abs(slope) < EPSILON) return@repeat
      t -= error / slope
    }
    // Newton can stall on flat segments; bisection always converges on the monotone x(t).
    var low = 0f
    var high = 1f
    t = input
    repeat(BISECTION_STEPS) {
      val x = sampleX(t)
      if (abs(x - input) < EPSILON) return sampleY(t)
      if (x < input) low = t else high = t
      t = (low + high) / 2f
    }
    return sampleY(t)
  }

  private companion object {
    const val NEWTON_STEPS = 8
    const val BISECTION_STEPS = 24
    const val EPSILON = 1e-5f
  }
}

/** The named curves of the premium screensaver mock (EZ.* in the before/after site). */
object HearthEasing {
  /** Photo dissolves: gentle ease-in-out with no visible start or stop. */
  val SOFT = CubicEase(.45f, 0f, .2f, 1f)
  /** Photo settle motion: quick start, very long tail. */
  val DRIFT = CubicEase(.33f, 0f, .15f, 1f)
  /** Text arriving (minute change, captions, clock emergence). */
  val OUT = CubicEase(.22f, 1f, .36f, 1f)
  /** CSS sine ease-in-out (mock EZ.sine): a long pan that starts and stops without a jolt. */
  val SINE_IN_OUT = android.animation.TimeInterpolator { t -> (-(Math.cos(Math.PI * t) - 1) / 2).toFloat() }

  const val DISSOLVE_MS = 1600L
  /** Settle motion: begins with the dissolve and is finished well before the next photo. */
  const val SETTLE_MS = 8000L
}
