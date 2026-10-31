/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class DarkRoomPolicyTest {
  @Test fun replacingThresholdPreservesNightAndRequiresFreshBrightDwell() {
    val policy = DarkRoomPolicy(darkLux = 3f, initialNight = true)
    assertFalse(policy.read(13f, 100))
    assertTrue(policy.night)
    assertEquals(5_100L, policy.deadlineMs)
    assertFalse(policy.settle(5_099))
    assertTrue(policy.settle(5_100))
    assertFalse(policy.night)
  }

  @Test fun replacingThresholdInDarknessKeepsTheRetainedClockWithoutAnotherDarkDwell() {
    val policy = DarkRoomPolicy(darkLux = 3f, initialNight = true)
    assertFalse(policy.read(0f, 100))
    assertTrue(policy.night)
    assertNull(policy.deadlineMs)
  }
  @Test fun aSingleOnChangeReadingSettlesWithoutMoreSensorEvents() {
    val policy = DarkRoomPolicy()
    assertFalse(policy.read(0f, 1_000))
    assertEquals(21_000L, policy.deadlineMs)
    assertFalse(policy.settle(20_999))
    assertTrue(policy.settle(21_000))
    assertTrue(policy.night)
    assertNull(policy.deadlineMs)
  }

  @Test fun aBriefShadowDoesNotEnterNight() {
    val policy = DarkRoomPolicy()
    policy.read(1f, 0)
    policy.read(20f, 19_999)
    assertFalse(policy.settle(30_000))
    assertFalse(policy.night)
  }

  @Test fun deadBandCancelsPendingTransitionAndPreventsChatter() {
    val policy = DarkRoomPolicy()
    policy.read(2f, 0)
    policy.read(5f, 1_000)
    assertNull(policy.deadlineMs)
    policy.read(2f, 2_000)
    assertTrue(policy.settle(22_000))
    policy.read(5f, 23_000)
    assertTrue(policy.night)
    assertNull(policy.deadlineMs)
  }

  @Test fun brightRoomResumesOnlyAfterItsOwnDwell() {
    val policy = DarkRoomPolicy()
    policy.read(0f, 0)
    policy.settle(20_000)
    policy.read(12f, 21_000)
    assertTrue(policy.night)
    assertFalse(policy.settle(25_999))
    assertTrue(policy.settle(26_000))
    assertFalse(policy.night)
  }

  @Test fun invalidReadingsCancelPendingDecisionWithoutInventingDarkness() {
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
      val policy = DarkRoomPolicy()
      policy.read(0f, 0)
      assertFalse(policy.read(bad, 5_000))
      assertNull(policy.deadlineMs)
      assertFalse(policy.settle(30_000))
      assertFalse(policy.night)
    }
  }

  @Test fun hidingHostCancelsDwellButRetainsItsDisplayedMode() {
    val policy = DarkRoomPolicy()
    policy.read(0f, 0)
    policy.cancelPending()
    assertFalse(policy.settle(30_000))
    policy.read(0f, 40_000)
    policy.settle(60_000)
    policy.read(20f, 61_000)
    policy.cancelPending()
    assertTrue(policy.night)
    assertNull(policy.deadlineMs)
  }

  @Test fun repeatedReadingsDoNotPushDeadlineForward() {
    val policy = DarkRoomPolicy()
    policy.read(1f, 0)
    policy.read(2f, 10_000)
    assertEquals(20_000L, policy.deadlineMs)
    assertTrue(policy.settle(20_000))
  }

  @Test fun customizedDarkThresholdStillHasASeparateBrightThreshold() {
    val policy = DarkRoomPolicy(darkLux = 8f)
    policy.read(7f, 0)
    policy.settle(20_000)
    policy.read(15f, 21_000)
    assertNull(policy.deadlineMs)
    policy.read(24f, 22_000)
    assertTrue(policy.settle(27_000))
    assertFalse(policy.night)
  }

  @Test fun retainedHostAndNightModeBothGatePhotoWork() {
    assertTrue(DarkRoomPolicy.photosActive(hostVisible = true, night = false))
    assertFalse(DarkRoomPolicy.photosActive(hostVisible = true, night = true))
    assertFalse(DarkRoomPolicy.photosActive(hostVisible = false, night = false))
    assertFalse(DarkRoomPolicy.photosActive(hostVisible = false, night = true))
  }

  @Test fun clockSchedulesOnlyTheNextMinuteBoundary() {
    assertEquals(60_000L, DarkRoomPolicy.nextMinuteDelay(0))
    assertEquals(1L, DarkRoomPolicy.nextMinuteDelay(59_999))
    assertEquals(59_999L, DarkRoomPolicy.nextMinuteDelay(60_001))
  }
}
