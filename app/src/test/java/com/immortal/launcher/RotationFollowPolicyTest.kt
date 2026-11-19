/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RotationFollowPolicy.Decision
import com.immortal.launcher.RotationPlatformProbe.SCREEN_ORIENTATION_LANDSCAPE
import com.immortal.launcher.RotationPlatformProbe.SCREEN_ORIENTATION_PORTRAIT
import com.immortal.launcher.RotationPlatformProbe.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
import com.immortal.launcher.RotationPlatformProbe.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import com.immortal.launcher.RotationPlatformProbe.SCREEN_ORIENTATION_UNSPECIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationFollowPolicyTest {
  private fun settle(p: RotationFollowPolicy, deg: Int, start: Long): Decision {
    p.onAngle(deg, start)
    return p.onTick(start + p.holdMs)
  }

  @Test fun theWindowsMatchTheSpike() {
    val p = RotationFollowPolicy()
    assertEquals(RotationPose.PORTRAIT, p.classify(0))
    assertEquals(RotationPose.PORTRAIT, p.classify(355))
    assertEquals(RotationPose.PORTRAIT, p.classify(30))
    assertEquals(RotationPose.REVERSE_LANDSCAPE, p.classify(90))
    assertEquals(RotationPose.REVERSE_LANDSCAPE, p.classify(62))
    assertEquals(RotationPose.LANDSCAPE, p.classify(270))
    assertEquals(RotationPose.LANDSCAPE, p.classify(300))
    assertNull("between windows", p.classify(45))
    assertNull("upside down", p.classify(180))
    assertNull("flat / unknown", p.classify(-1))
  }

  @Test fun aPoseSettlesOnlyAfterTheHold() {
    val p = RotationFollowPolicy()
    assertEquals(Decision.Wait(RotationPose.REVERSE_LANDSCAPE, 1600), p.onAngle(88, 1000))
    assertEquals(Decision.Wait(RotationPose.REVERSE_LANDSCAPE, 1600), p.onAngle(91, 1300))
    assertEquals(Decision.Wait(RotationPose.REVERSE_LANDSCAPE, 1600), p.onTick(1599))
    assertEquals(Decision.Settled(RotationPose.REVERSE_LANDSCAPE), p.onTick(1600))
    assertEquals(RotationPose.REVERSE_LANDSCAPE, p.settled)
    assertEquals("an unchanged pose is quiet", Decision.None, p.onAngle(90, 2000))
  }

  @Test fun theHoldIsAboutSixHundredMilliseconds() {
    assertEquals(600L, RotationFollowPolicy.HOLD_MS)
  }

  @Test fun leavingTheWindowRestartsTheHold() {
    val p = RotationFollowPolicy()
    p.onAngle(90, 0)
    assertEquals(Decision.None, p.onAngle(45, 400))
    assertEquals(Decision.None, p.onTick(700))
    assertEquals(Decision.Wait(RotationPose.REVERSE_LANDSCAPE, 1400), p.onAngle(90, 800))
    assertEquals(Decision.Settled(RotationPose.REVERSE_LANDSCAPE), p.onTick(1400))
  }

  @Test fun aBriefPassThroughAnotherPoseNeverRotates() {
    val p = RotationFollowPolicy()
    settle(p, 0, 0)
    p.onAngle(270, 1000)
    p.onAngle(10, 1200) // back upright before the hold completes
    assertEquals(Decision.None, p.onTick(1700))
    assertEquals(RotationPose.PORTRAIT, p.settled)
  }

  @Test fun hysteresisKeepsTheSettledPoseThroughWobble() {
    val p = RotationFollowPolicy()
    assertEquals(Decision.Settled(RotationPose.REVERSE_LANDSCAPE), settle(p, 90, 0))
    // 35° is inside portrait's enter window but within 60° of the settled 90°: stays.
    assertEquals(RotationPose.REVERSE_LANDSCAPE, p.classify(35))
    assertEquals(Decision.None, p.onAngle(35, 2000))
    assertEquals(Decision.None, p.onTick(3000))
    // Clearly upright: portrait wins after the hold.
    p.onAngle(5, 4000)
    assertEquals(Decision.Settled(RotationPose.PORTRAIT), p.onTick(4600))
  }

  @Test fun resetForgetsThePose() {
    val p = RotationFollowPolicy()
    settle(p, 270, 0)
    p.reset()
    assertNull(p.settled)
    assertEquals(Decision.None, p.onTick(5000))
  }

  @Test fun theProbeFlagsOnlyAPoseThePlatformLeftUnrotated() {
    // The Mini: right turn held, display still ROTATION_0.
    assertTrue(RotationPlatformProbe.ignored(RotationPose.REVERSE_LANDSCAPE, 0, 800))
    // Too early to judge.
    assertFalse(RotationPlatformProbe.ignored(RotationPose.REVERSE_LANDSCAPE, 0, 100))
    // The platform followed: not ignored (left turn on the Mini, or any pose on a normal device).
    assertFalse(RotationPlatformProbe.ignored(RotationPose.LANDSCAPE, 1, 5000))
    assertFalse(RotationPlatformProbe.ignored(RotationPose.REVERSE_LANDSCAPE, 3, 5000))
    assertFalse(RotationPlatformProbe.ignored(RotationPose.PORTRAIT, 0, 5000))
  }

  @Test fun theFollowerTakesOverOnlyIgnoredPoses() {
    val ignored = setOf(RotationPose.REVERSE_LANDSCAPE)
    val free = SCREEN_ORIENTATION_UNSPECIFIED
    assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        RotationPlatformProbe.requestFor(RotationPose.REVERSE_LANDSCAPE, ignored, free))
    // Native poses go back to the activity's own orientation (the platform rotates at its own speed).
    assertEquals(free, RotationPlatformProbe.requestFor(RotationPose.PORTRAIT, ignored, free))
    assertEquals(free, RotationPlatformProbe.requestFor(RotationPose.LANDSCAPE, ignored, free))
    // Nothing learned yet: never override.
    assertEquals(free, RotationPlatformProbe.requestFor(RotationPose.REVERSE_LANDSCAPE, emptySet(), free))
  }

  @Test fun sensorLandscapeActivitiesFlipButNeverGoPortrait() {
    val all = RotationPose.entries.toSet()
    val sl = SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, RotationPlatformProbe.requestFor(RotationPose.REVERSE_LANDSCAPE, all, sl))
    assertEquals(SCREEN_ORIENTATION_LANDSCAPE, RotationPlatformProbe.requestFor(RotationPose.LANDSCAPE, all, sl))
    assertEquals(sl, RotationPlatformProbe.requestFor(RotationPose.PORTRAIT, all, sl))
  }

  @Test fun fixedActivitiesAreLeftAlone() {
    val all = RotationPose.entries.toSet()
    assertNull(RotationPlatformProbe.requestFor(RotationPose.REVERSE_LANDSCAPE, all, SCREEN_ORIENTATION_PORTRAIT))
    assertNull(RotationPlatformProbe.requestFor(RotationPose.PORTRAIT, all, SCREEN_ORIENTATION_LANDSCAPE))
  }

  @Test fun theDistanceWrapsAround() {
    assertEquals(10, RotationFollowPolicy.distance(355, 5))
    assertEquals(90, RotationFollowPolicy.distance(0, 270))
    assertEquals(0, RotationFollowPolicy.distance(720, 0))
  }
}
