/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FramePerformanceSamplingDetailTest {
  @Test fun explicitSamplingRetainsRealHomeTailFramesWithoutChangingTimingAggregates() {
    val sampled = requireNotNull(FramePerformanceRequest.parse(JSONObject()
        .put("action", "start").put("durationSec", 10).put("phase", "apps").put("sampleMain", true)))
    val ordinary = sampled.copy(sampleMain = false)
    val sampledFrames = FramePerformanceHistogram(16_666_667L, sampled.slowFrameThresholdNs)
    val ordinaryFrames = FramePerformanceHistogram(16_666_667L, ordinary.slowFrameThresholdNs)
    val parts = longArrayOf(1_000_000L, 0, 32_000_000L, 0, 7_000_000L, 0, 4_000_000L, 1_000_000L)
    for (frames in listOf(sampledFrames, ordinaryFrames))
      assertTrue(frames.recordDetailed(45_000_000L, 0, false, 110, parts))
    val sampledResult = sampledFrames.snapshot()
    val ordinaryResult = ordinaryFrames.snapshot()
    for (key in listOf("frames", "meanMs", "p95Ms", "p99Ms", "overBudget"))
      assertEquals(ordinaryResult.get(key), sampledResult.get(key))
    val tail = sampledResult.getJSONObject("slowFrames")
    assertEquals(32.0, tail.getDouble("minTotalMs"), 0.0)
    assertEquals(1, tail.getJSONArray("frames").length())
    val event = tail.getJSONArray("frames").getJSONObject(0)
    assertEquals(110L, event.getLong("elapsedMs"))
    assertEquals(32.0, event.getJSONObject("components").getDouble("animation"), 0.0)
    assertEquals(0, ordinaryResult.getJSONObject("slowFrames").getJSONArray("frames").length())
  }

  @Test fun ordinaryProtocolDefaultsKeepTheEstablished100msDetailThreshold() {
    val request = requireNotNull(FramePerformanceRequest.parse(JSONObject().put("action", "start")))
    assertFalse(request.sampleMain)
    val frames = FramePerformanceHistogram(16_666_667L, request.slowFrameThresholdNs)
    frames.recordDetailed(99_999_999L, 0, false, 20, LongArray(8))
    frames.recordDetailed(100_000_000L, 0, false, 30, LongArray(8))
    val tail = frames.snapshot().getJSONObject("slowFrames")
    assertEquals(100.0, tail.getDouble("minTotalMs"), 0.0)
    assertEquals(1, tail.getJSONArray("frames").length())
    assertEquals(30L, tail.getJSONArray("frames").getJSONObject(0).getLong("elapsedMs"))
  }

  @Test fun lowerThresholdHasExactBoundaryAndCopiesPairedDetail() {
    val frames = FramePerformanceSlowFrames(32_000_000L)
    assertFalse(frames.record(31_999_999L, false, 1, LongArray(8)))
    val parts = LongArray(8) { (it + 1) * 1_000_000L }
    assertTrue(frames.record(32_000_000L, true, 10, parts))
    parts.fill(999)
    val tail = frames.snapshot()
    assertEquals(1L, tail.getLong("eligibleFrames"))
    val event = tail.getJSONArray("frames").getJSONObject(0)
    assertTrue(event.getBoolean("firstDraw"))
    assertEquals(8.0, event.getJSONObject("components").getDouble("swap"), 0.0)
  }

  @Test fun configurableThresholdRetainsOnly32WorstFrames() {
    val frames = FramePerformanceSlowFrames(32_000_000L)
    repeat(100) { frames.record((32L + it) * 1_000_000L, false, it.toLong(), LongArray(8)) }
    val tail = frames.snapshot()
    assertEquals(100L, tail.getLong("eligibleFrames"))
    assertEquals(68L, tail.getLong("omittedFrames"))
    val events = tail.getJSONArray("frames")
    assertEquals(32, events.length())
    assertEquals(131.0, events.getJSONObject(0).getDouble("totalMs"), 0.0)
    assertEquals(100.0, events.getJSONObject(31).getDouble("totalMs"), 0.0)
  }

  @Test fun impossibleThresholdsAreRejectedBeforeAnyCapture() {
    for (bad in listOf(-1L, 0L, 60_000_000_001L, Long.MAX_VALUE)) {
      try { FramePerformanceSlowFrames(bad); fail("Invalid detail threshold accepted") }
      catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun remoteCallerCannotChooseAnArbitraryDetailThreshold() {
    assertNull(FramePerformanceRequest.parse(JSONObject().put("action", "start")
        .put("sampleMain", true).put("durationSec", 10).put("slowFrameThresholdNs", 1)))
  }
}
