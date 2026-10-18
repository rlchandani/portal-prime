/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class GoldenHourSchedulerTest {
  private val day = 86_400_000L
  private val today = Weather.SunTimes(6 * 3_600_000L, 20 * 3_600_000L, 0)
  private val tomorrow = Weather.SunTimes(day + 6 * 3_600_000L + 120_000, day + 20 * 3_600_000L - 60_000, 0)

  @Test fun `after sunrise use tomorrow actual sunrise rather than today plus one day`() {
    val next = ChimeScheduler.nextGoldenHourTimes(listOf(today, tomorrow), 12 * 3_600_000L)
    assertEquals(tomorrow.sunriseMillis, next.sunriseMillis)
    assertEquals(today.sunsetMillis, next.sunsetMillis)
  }
  @Test fun `after sunset use both actual next day events`() {
    val next = ChimeScheduler.nextGoldenHourTimes(listOf(today, tomorrow), 22 * 3_600_000L)
    assertEquals(tomorrow.sunriseMillis, next.sunriseMillis)
    assertEquals(tomorrow.sunsetMillis, next.sunsetMillis)
  }

  @Test fun `just fired event is excluded while the other today event remains`() {
    val next = ChimeScheduler.nextGoldenHourTimes(listOf(today, tomorrow), today.sunriseMillis)
    assertEquals(tomorrow.sunriseMillis, next.sunriseMillis)
    assertEquals(today.sunsetMillis, next.sunsetMillis)
  }

  @Test fun `choose earliest future independently of forecast order`() {
    val next = ChimeScheduler.nextGoldenHourTimes(listOf(tomorrow, today), 0)
    assertEquals(today.sunriseMillis, next.sunriseMillis)
    assertEquals(today.sunsetMillis, next.sunsetMillis)
  }

  @Test fun `expired or absent horizon never invents another day`() {
    assertEquals(ChimeScheduler.GoldenHourTimes(null, null),
        ChimeScheduler.nextGoldenHourTimes(listOf(today), day))
    assertEquals(ChimeScheduler.GoldenHourTimes(null, null),
        ChimeScheduler.nextGoldenHourTimes(emptyList(), 0))
  }

  @Test fun `event availability can differ without a guessed sunrise`() {
    val next = ChimeScheduler.nextGoldenHourTimes(listOf(today), 12 * 3_600_000L)
    assertEquals(null, next.sunriseMillis)
    assertEquals(today.sunsetMillis, next.sunsetMillis)
  }

}
