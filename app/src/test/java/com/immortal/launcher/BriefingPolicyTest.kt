/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Good morning card's window and the once-a-day open after the alarm. */
class BriefingPolicyTest {
  private val zone = TimeZone.getTimeZone("Europe/Dublin")
  /** 17 November 2026 00:00 in Dublin (UTC+0 in winter). */
  private val midnight = 1_794_873_600_000L
  private fun at(h: Int, m: Int = 0) = midnight + (h * 60 + m) * 60_000L
  private val on = BriefingConfig.Settings()
  private val today = BriefingPolicy.localDay(at(7), zone)

  @Test fun cardShowsFromSixUntilEleven() {
    assertFalse(BriefingPolicy.cardVisible(on, at(5, 59), zone, null))
    assertTrue(BriefingPolicy.cardVisible(on, at(6), zone, null))
    assertTrue(BriefingPolicy.cardVisible(on, at(10, 59), zone, null))
    assertFalse(BriefingPolicy.cardVisible(on, at(11), zone, null))
  }

  @Test fun dismissedCardStaysHiddenUntilTomorrow() {
    assertFalse(BriefingPolicy.cardVisible(on, at(8), zone, today))
    assertTrue(BriefingPolicy.cardVisible(on, at(8) + 86_400_000L, zone, today))
  }

  @Test fun offHidesTheCardAndNeverOpensByItself() {
    val off = on.copy(enabled = false)
    assertFalse(BriefingPolicy.cardVisible(off, at(8), zone, null))
    assertFalse(BriefingPolicy.opensAfterWake(off, at(7), zone, null))
  }

  @Test fun opensAfterWakeOnceADay_onlyAtAlarmTime() {
    assertTrue(BriefingPolicy.opensAfterWake(on, at(7), zone, null))
    assertFalse(BriefingPolicy.opensAfterWake(on, at(7, 20), zone, today))
    assertFalse(BriefingPolicy.opensAfterWake(on.copy(trigger = BriefingTrigger.TAP.wire), at(7), zone, null))
  }

  @Test fun minuteOfDayFollowsTheLocalZone() {
    val newYork = TimeZone.getTimeZone("America/New_York")
    assertEquals(7 * 60 + 5, BriefingPolicy.minuteOfDay(at(7, 5), zone))
    assertEquals(2 * 60 + 5, BriefingPolicy.minuteOfDay(at(7, 5), newYork))
  }

  @Test fun defaultsAreOnAtAlarmTimeAndSilent() {
    assertTrue(on.enabled)
    assertEquals(BriefingTrigger.ALARM, on.triggerMode)
    assertFalse(on.speak)
    assertEquals(BriefingTrigger.ALARM, on.copy(trigger = "bogus").triggerMode)
  }
}
