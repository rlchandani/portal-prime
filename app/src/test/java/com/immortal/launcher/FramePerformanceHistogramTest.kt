/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class FramePerformanceHistogramTest {
  @Test fun quantilesAndBudgetUseBoundedBuckets() {
    val h = FramePerformanceHistogram(16_666_667L)
    for (ms in listOf(10L, 20L, 40L)) assertTrue(h.record(ms * 1_000_000, 0, false))
    val j = h.snapshot()
    assertEquals(3L, j.getLong("frames"))
    assertEquals(2L, j.getLong("overBudget"))
    assertEquals(1L, j.getLong("over33_333Ms"))
    assertEquals(20.25, j.getDouble("p50Ms"), 0.0)
    assertEquals(40.25, j.getDouble("p95Ms"), 0.0)
    assertEquals(70.0 / 3, j.getDouble("meanMs"), 0.000001)
  }
  @Test fun overflowPercentileIsALowerBoundNotAFabricatedExactDuration() {
    val h = FramePerformanceHistogram(16_666_667L)
    assertTrue(h.record(600_000_000L, 0, false))
    val j = h.snapshot()
    assertTrue(j.isNull("p95Ms"))
    assertEquals(500.0, j.getDouble("p95AtLeastMs"), 0.0)
    assertEquals(1L, j.getLong("overflowSamples"))
    assertEquals(600.0, j.getDouble("maxMs"), 0.0)
  }
  @Test fun invalidMetricsDoNotPolluteMeansOrQuantiles() {
    val h = FramePerformanceHistogram(16_666_667L)
    for (bad in listOf(-1L, 0L, Long.MAX_VALUE)) assertFalse(h.record(bad, 0, false))
    val j=h.snapshot()
    assertEquals(0L, j.getLong("frames"))
    assertEquals(3L, j.getLong("invalidSamples"))
    assertTrue(j.isNull("meanMs")); assertTrue(j.isNull("p95Ms"))
  }
  @Test fun droppedCallbacksAndFirstFramesRemainVisible() {
    val h=FramePerformanceHistogram(8_333_333L)
    h.record(10_000_000L, 4, true)
    h.record(5_000_000L, -2, false)
    val j=h.snapshot()
    assertEquals(4L,j.getLong("callbackDrops"))
    assertEquals(1L,j.getLong("firstDrawFrames"))
    assertEquals(1L,j.getLong("overBudget"))
    assertEquals(1L,j.getLong("steadyFrames"))
    assertEquals(0L,j.getLong("steadyOverBudget"))
    assertEquals(8.333333,j.getDouble("budgetMs"),0.000001)
  }
  @Test fun componentMissingValuesAreCountedRatherThanZeroFilled() {
    val h=FramePerformanceHistogram(16_666_667L)
    h.record(10_000_000L,0,false)
    h.component(4,2_000_000L)
    h.record(12_000_000L,0,false)
    h.component(4,-1L)
    val draw=h.snapshot().getJSONObject("components").getJSONObject("draw")
    assertEquals(1L,draw.getLong("samples"))
    assertEquals(1L,draw.getLong("missing"))
    assertEquals(2.0,draw.getDouble("meanMs"),0.0)
  }
}
