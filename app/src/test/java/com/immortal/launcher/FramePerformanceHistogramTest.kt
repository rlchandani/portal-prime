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
  private fun parts(vararg ms: Long): LongArray = ms.map { it * 1_000_000L }.toLongArray()

  @Test fun worstFramePairsItsOwnComponentsAndCallbackTimestamp() {
    val h = FramePerformanceHistogram(16_666_667L)
    val first = parts(1, 2, 3, 4, 5, 6, 7, 8)
    assertTrue(h.recordDetailed(120_000_000L, 2, true, 30L, first))
    first.fill(999_000_000L)
    assertTrue(h.recordDetailed(20_000_000L, 0, false, 40L, parts(0, 0, 0, 0, 0, 0, 18, 0)))
    val snapshot = h.snapshot()
    assertEquals(2L, snapshot.getLong("detailedFrames"))
    val worst = snapshot.getJSONObject("worstFrame")
    assertEquals(30L, worst.getLong("elapsedMs"))
    assertEquals(120.0, worst.getDouble("totalMs"), 0.0)
    assertTrue(worst.getBoolean("firstDraw"))
    assertEquals(7.0, worst.getJSONObject("components").getDouble("commands"), 0.0)
    assertEquals(18.0, snapshot.getJSONObject("components").getJSONObject("commands").getDouble("maxMs"), 0.0)
    assertEquals(2L, snapshot.getLong("callbackDrops"))
  }

  @Test fun largerDetailedFrameReplacesAllPairedFieldsAndEqualMaxKeepsFirst() {
    val h = FramePerformanceHistogram(16_666_667L)
    h.recordDetailed(30_000_000L, 0, true, 10L, parts(1, 1, 1, 1, 1, 1, 1, 1))
    h.recordDetailed(40_000_000L, 0, false, 20L, parts(2, 3, 4, 5, 6, 7, 8, 9))
    h.recordDetailed(40_000_000L, 0, true, 25L, parts(9, 9, 9, 9, 9, 9, 9, 9))
    val worst = h.snapshot().getJSONObject("worstFrame")
    assertEquals(20L, worst.getLong("elapsedMs"))
    assertFalse(worst.getBoolean("firstDraw"))
    assertEquals(8.0, worst.getJSONObject("components").getDouble("commands"), 0.0)
    assertEquals(9.0, worst.getJSONObject("components").getDouble("swap"), 0.0)
  }

  @Test fun missingComponentRemainsNullInPairedFrameAndMissingInAggregates() {
    val h = FramePerformanceHistogram(16_666_667L)
    val components = parts(0, 0, 0, 0, 0, 0, 0, 0)
    components[0] = -1L
    components[6] = 60_000_000_001L
    h.recordDetailed(10_000_000L, 0, false, 1L, components)
    val snapshot = h.snapshot()
    val paired = snapshot.getJSONObject("worstFrame").getJSONObject("components")
    assertTrue(paired.isNull("queued")); assertTrue(paired.isNull("commands"))
    assertEquals(0.0, paired.getDouble("draw"), 0.0)
    assertEquals(1L, snapshot.getJSONObject("components").getJSONObject("queued").getLong("missing"))
  }

  @Test fun invalidDetailsKeepValidAggregateButCannotImpersonateItsWorstFrame() {
    val h = FramePerformanceHistogram(16_666_667L)
    h.recordDetailed(10_000_000L, 0, false, 0L, LongArray(8))
    h.recordDetailed(30_000_000L, 0, false, -1L, LongArray(8))
    h.recordDetailed(20_000_000L, 0, false, 900_001L, LongArray(8))
    val snapshot = h.snapshot()
    assertEquals(3L, snapshot.getLong("frames"))
    assertEquals(1L, snapshot.getLong("detailedFrames"))
    assertEquals(2L, snapshot.getLong("invalidDetailSamples"))
    assertTrue(snapshot.isNull("worstFrame"))
    h.recordDetailed(40_000_000L, 0, true, 900_000L, LongArray(8))
    assertEquals(900_000L, h.snapshot().getJSONObject("worstFrame").getLong("elapsedMs"))
  }

  @Test fun invalidTotalCannotChangeDetailsOrComponentAggregates() {
    val h = FramePerformanceHistogram(16_666_667L)
    for (bad in listOf(0L, -1L, 60_000_000_001L))
      assertFalse(h.recordDetailed(bad, 1, false, 5L, parts(99, 99, 99, 99, 99, 99, 99, 99)))
    val snapshot = h.snapshot()
    assertEquals(0L, snapshot.getLong("detailedFrames"))
    assertEquals(0L, snapshot.getLong("invalidDetailSamples"))
    assertEquals(3L, snapshot.getLong("invalidSamples"))
    assertEquals(3L, snapshot.getLong("callbackDrops"))
    assertTrue(snapshot.isNull("worstFrame"))
    assertEquals(0L, snapshot.getJSONObject("components").getJSONObject("draw").getLong("samples"))
  }

  @Test fun incorrectComponentArityIsRejectedBeforeAnyMutation() {
    val h = FramePerformanceHistogram(16_666_667L)
    for (size in listOf(0, 7, 9)) {
      try {
        h.recordDetailed(10_000_000L, 1, true, 1L, LongArray(size))
        fail("Wrong number of components was accepted")
      } catch (_: IllegalArgumentException) {}
    }
    val snapshot = h.snapshot()
    assertEquals(0L, snapshot.getLong("frames"))
    assertEquals(0L, snapshot.getLong("callbackDrops"))
    assertEquals(0L, snapshot.getLong("detailedFrames"))
  }

  @Test fun unpairedAggregateMaximumDoesNotReturnAnOlderDetailedFrame() {
    val h = FramePerformanceHistogram(16_666_667L)
    h.recordDetailed(10_000_000L, 0, false, 1L, LongArray(8))
    h.record(20_000_000L, 0, true)
    assertTrue(h.snapshot().isNull("worstFrame"))
  }

  @Test fun detailedSnapshotsHaveFixedKeysAndDoNotShareReturnedJson() {
    val h = FramePerformanceHistogram(16_666_667L)
    h.recordDetailed(10_000_000L, 0, false, 1L, LongArray(8))
    val first = h.snapshot().getJSONObject("worstFrame")
    assertEquals(setOf("elapsedMs", "totalMs", "firstDraw", "components"), first.keys().asSequence().toSet())
    assertEquals(setOf("queued", "input", "animation", "layout", "draw", "sync", "commands", "swap"),
        first.getJSONObject("components").keys().asSequence().toSet())
    first.put("totalMs", 999.0); first.getJSONObject("components").put("draw", 999.0)
    val second = h.snapshot().getJSONObject("worstFrame")
    assertEquals(10.0, second.getDouble("totalMs"), 0.0)
    assertEquals(0.0, second.getJSONObject("components").getDouble("draw"), 0.0)
  }

  @Test fun detailedRetentionStaysOneFrameAcrossManyAcceptedRecords() {
    val h = FramePerformanceHistogram(16_666_667L)
    val reused = LongArray(8)
    repeat(100_000) { index ->
      h.recordDetailed((index + 1) * 1_000L, 0, index == 99_999, index.toLong(), reused)
    }
    val snapshot = h.snapshot()
    assertEquals(100_000L, snapshot.getLong("detailedFrames"))
    val worst = snapshot.getJSONObject("worstFrame")
    assertEquals(99_999L, worst.getLong("elapsedMs"))
    assertEquals(100.0, worst.getDouble("totalMs"), 0.0)
    assertTrue(worst.getBoolean("firstDraw"))
    assertEquals(8, worst.getJSONObject("components").length())
  }

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
