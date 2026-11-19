/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * A physical pose of the device, from [android.view.OrientationEventListener]'s angle (0° upright,
 * 90° turned so the display must show at ROTATION_270, 270° turned the other way → ROTATION_90).
 * [displayRotation] is the `Surface.ROTATION_*` index the pose needs.
 */
internal enum class RotationPose(val angle: Int, val displayRotation: Int) {
  PORTRAIT(0, 0),
  /** Turned right (the angle reads ~90°): needs ROTATION_270, which the Mini's platform never proposes. */
  REVERSE_LANDSCAPE(90, 3),
  /** Turned left (~270°): ROTATION_90, which the platform already follows. */
  LANDSCAPE(270, 1),
}

/**
 * Debounced, hysteretic pose tracking for the in-app rotation follower. Pure (JVM tested).
 *
 * - **Enter window:** a reading counts as a pose only within ±[enterWindow]° of the pose's angle.
 * - **Hysteresis:** once a pose is settled, readings within ±[stayWindow]° of it keep it, so
 *   hand-held wobble near 45° never flips the screen back and forth.
 * - **Debounce:** a new pose must be held continuously for [holdMs] before it settles; any reading
 *   outside every window (flat, upside down, mid-turn) restarts the hold.
 *
 * The listener only reports changed angles, so a held pose is completed by [onTick] at [Decision.Wait.untilMs].
 */
internal class RotationFollowPolicy(
    val holdMs: Long = HOLD_MS,
    val enterWindow: Int = ENTER_WINDOW,
    val stayWindow: Int = STAY_WINDOW,
) {
  sealed interface Decision {
    /** Nothing to do. */
    data object None : Decision
    /** A new pose is being held; call [onTick] at [untilMs] if no other reading arrives. */
    data class Wait(val pose: RotationPose, val untilMs: Long) : Decision
    /** [pose] has settled. */
    data class Settled(val pose: RotationPose) : Decision
  }

  var settled: RotationPose? = null
    private set
  private var candidate: RotationPose? = null
  private var since = 0L

  /** The pose a reading means given the current settled pose, or null (unknown or between windows). */
  fun classify(degrees: Int): RotationPose? {
    if (degrees < 0) return null
    val a = degrees % 360
    settled?.let { if (distance(a, it.angle) <= stayWindow) return it }
    return RotationPose.entries.firstOrNull { distance(a, it.angle) <= enterWindow }
  }

  fun onAngle(degrees: Int, nowMs: Long): Decision {
    val pose = classify(degrees)
    if (pose == null || pose == settled) {
      candidate = null
      return Decision.None
    }
    if (pose != candidate) {
      candidate = pose
      since = nowMs
      return Decision.Wait(pose, nowMs + holdMs)
    }
    return onTick(nowMs)
  }

  /** Completes a held pose once [holdMs] has passed since it was first read. */
  fun onTick(nowMs: Long): Decision {
    val pose = candidate ?: return Decision.None
    if (nowMs - since < holdMs) return Decision.Wait(pose, since + holdMs)
    settled = pose
    candidate = null
    return Decision.Settled(pose)
  }

  /** Forget everything (the follower was switched off or the activity left). */
  fun reset() {
    settled = null
    candidate = null
  }

  companion object {
    const val HOLD_MS = 600L
    const val ENTER_WINDOW = 30
    const val STAY_WINDOW = 60

    fun distance(a: Int, b: Int): Int {
      val d = Math.floorMod(a - b, 360)
      return minOf(d, 360 - d)
    }
  }
}

/**
 * Whether the platform ignores a settled pose: the pose has been held past the platform's own
 * rotation latency ([graceMs] after it settled) and the display still isn't at the rotation it
 * needs. On the Portal Mini this is true for [RotationPose.REVERSE_LANDSCAPE] only (its sensor judge
 * never proposes ROTATION_270); a device whose platform follows every pose never trips it. Pure.
 */
internal object RotationPlatformProbe {
  const val GRACE_MS = 700L

  fun ignored(pose: RotationPose, displayRotation: Int, msSinceSettled: Long, graceMs: Long = GRACE_MS): Boolean =
      msSinceSettled >= graceMs && displayRotation != pose.displayRotation

  /**
   * What an activity should request for [pose]. The follower only takes over the pose the platform
   * ignores; for every other pose it hands back the activity's own declared orientation so the
   * platform's native sensor path (and its speed) stays in charge.
   */
  fun requestFor(pose: RotationPose, ignoredPoses: Set<RotationPose>, declared: Int): Int? = when {
    !follows(declared) -> null
    pose in ignoredPoses && allows(declared, pose) -> requested(pose)
    else -> declared
  }

  /** Declared orientations the follower may override: free ones and the sensor-landscape family. */
  fun follows(declared: Int): Boolean = declared in FREE || declared in LANDSCAPE_FAMILY

  fun allows(declared: Int, pose: RotationPose): Boolean =
      declared in FREE || (declared in LANDSCAPE_FAMILY && pose != RotationPose.PORTRAIT)

  fun requested(pose: RotationPose): Int = when (pose) {
    RotationPose.PORTRAIT -> SCREEN_ORIENTATION_PORTRAIT
    RotationPose.LANDSCAPE -> SCREEN_ORIENTATION_LANDSCAPE
    RotationPose.REVERSE_LANDSCAPE -> SCREEN_ORIENTATION_REVERSE_LANDSCAPE
  }

  // android.content.pm.ActivityInfo.SCREEN_ORIENTATION_* (literal so the policy stays pure JVM).
  const val SCREEN_ORIENTATION_UNSPECIFIED = -1
  const val SCREEN_ORIENTATION_LANDSCAPE = 0
  const val SCREEN_ORIENTATION_PORTRAIT = 1
  const val SCREEN_ORIENTATION_USER = 2
  const val SCREEN_ORIENTATION_SENSOR = 4
  const val SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6
  const val SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8
  const val SCREEN_ORIENTATION_FULL_SENSOR = 10
  const val SCREEN_ORIENTATION_USER_LANDSCAPE = 11
  const val SCREEN_ORIENTATION_FULL_USER = 13

  private val FREE = setOf(SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_USER, SCREEN_ORIENTATION_SENSOR,
      SCREEN_ORIENTATION_FULL_SENSOR, SCREEN_ORIENTATION_FULL_USER)
  private val LANDSCAPE_FAMILY = setOf(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SCREEN_ORIENTATION_USER_LANDSCAPE)
}
