/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockRevealRefreshTest {
  @Test fun fadeFramesInOneMinutePublishOnlyTheFirstTime() {
    val published = mutableListOf<Long>()
    val refresh = ClockRevealRefresh(published::add)
    repeat(180) { refresh.update(12_000L + it * 16L) }
    assertEquals(listOf(12_000L), published)
  }

  @Test fun minuteBoundaryDuringRevealPublishesTheNewMinuteImmediately() {
    val published = mutableListOf<Long>()
    val refresh = ClockRevealRefresh(published::add)
    listOf(86_399_980L, 86_399_996L, 86_400_012L, 86_400_028L).forEach(refresh::update)
    assertEquals(listOf(86_399_980L, 86_400_012L), published)
  }

  @Test fun newRevealRefreshesEvenIfItStartsInTheSameMinute() {
    val published = mutableListOf<Long>()
    val refresh = ClockRevealRefresh(published::add)
    refresh.update(12_000L)
    refresh.reset()
    refresh.update(14_000L)
    assertEquals(listOf(12_000L, 14_000L), published)
  }

  @Test fun correctedWallClockAlsoRefreshesAnEarlierMinute() {
    val published = mutableListOf<Long>()
    val refresh = ClockRevealRefresh(published::add)
    refresh.update(120_000L)
    refresh.update(59_999L)
    refresh.update(59_998L)
    assertEquals(listOf(120_000L, 59_999L), published)
  }
}
