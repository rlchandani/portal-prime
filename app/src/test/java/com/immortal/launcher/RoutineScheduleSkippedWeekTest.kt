/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.MON
import com.immortal.launcher.RoutineFixtures.at
import com.immortal.launcher.RoutineFixtures.doc
import com.immortal.launcher.RoutineFixtures.morning
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: the next start was searched in a fixed 14-day window, so a weekly routine whose next
 * week was skipped had no occurrence, nextWake returned null and no alarm was armed for it.
 */
class RoutineScheduleSkippedWeekTest {
  private val mondaysOnly = morning().copy(days = List(7) { it == 0 })

  @Test fun aWeeklyRoutineWithNextWeekSkippedStillHasANextOccurrence() {
    val r = mondaysOnly.copy(skips = listOf(MON + 7))
    assertEquals(MON + 14, RoutineSchedule.nextOccurrence(r, emptyMap(), at(MON, 9, 0))?.day)
  }

  @Test fun aWeeklyRoutineWithNextWeekSkippedStillArmsAWake() {
    val r = mondaysOnly.copy(skips = listOf(MON + 7))
    assertEquals(RoutineDates.localMs(MON + 14, r.start), RoutineSchedule.nextWake(doc(r), emptyMap(), at(MON, 9, 0)))
  }

  @Test fun severalSkippedWeeksInARowAreSteppedOver() {
    val r = mondaysOnly.copy(skips = listOf(MON + 7, MON + 14, MON + 21))
    assertEquals(MON + 28, RoutineSchedule.nextOccurrence(r, emptyMap(), at(MON, 9, 0))?.day)
  }
}
