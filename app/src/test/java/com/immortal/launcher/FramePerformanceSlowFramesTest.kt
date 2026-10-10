/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class FramePerformanceSlowFramesTest {
  @Test fun belowThresholdAndInvalidValuesDoNotBecomeTailRecords() {
    val frames = FramePerformanceSlowFrames()
    for (value in listOf(-1L, 0L, 99_999_999L, 60_000_000_001L))
      assertFalse(frames.record(value, false, 1, LongArray(8)))
    for (elapsed in listOf(-1L, 900_001L)) assertFalse(frames.record(100_000_000, false, elapsed, LongArray(8)))
    assertEquals(0L, frames.snapshot().getLong("eligibleFrames"))
    assertEquals(0, frames.snapshot().getJSONArray("frames").length())
  }

  @Test fun thresholdRecordCopiesAllPairedFieldsAndHandlesMissingComponents() {
    val frames = FramePerformanceSlowFrames()
    val parts = longArrayOf(70_000_000, -1, 2_000_000, 0, 0, 0, 20_000_000, 60_000_000_001)
    assertTrue(frames.record(100_000_000, false, 34, parts)); parts.fill(999)
    val event = frames.snapshot().getJSONArray("frames").getJSONObject(0)
    assertEquals(100.0, event.getDouble("totalMs"), 0.0)
    assertEquals(34L, event.getLong("elapsedMs")); assertFalse(event.getBoolean("firstDraw"))
    assertEquals(70.0, event.getJSONObject("components").getDouble("queued"), 0.0)
    assertEquals(20.0, event.getJSONObject("components").getDouble("commands"), 0.0)
    assertTrue(event.getJSONObject("components").isNull("input")); assertTrue(event.getJSONObject("components").isNull("swap"))
  }

  @Test fun longStreamsRetainOnlyThe32SlowestFramesIncludingLaterSteadyFrames() {
    val frames = FramePerformanceSlowFrames()
    val parts = LongArray(8)
    repeat(10_000) { i ->
      parts[0] = i.toLong()
      assertTrue(frames.record((100L + i) * 1_000_000, i == 0, i.toLong(), parts))
    }
    val snapshot = frames.snapshot()
    assertEquals(10_000L, snapshot.getLong("eligibleFrames")); assertEquals(9_968L, snapshot.getLong("omittedFrames"))
    val retained = snapshot.getJSONArray("frames")
    assertEquals(32, retained.length())
    assertEquals(10_099.0, retained.getJSONObject(0).getDouble("totalMs"), 0.0)
    assertEquals(9_999L, retained.getJSONObject(0).getLong("elapsedMs"))
    assertEquals(10_068.0, retained.getJSONObject(31).getDouble("totalMs"), 0.0)
    assertTrue((0 until retained.length()).all { !retained.getJSONObject(it).getBoolean("firstDraw") })
  }

  @Test fun laterSmallFramesCannotEvictEarlierBiggerFramesAndEqualMinimumKeepsEarlier() {
    val frames = FramePerformanceSlowFrames()
    repeat(32) { i -> frames.record((200L + i) * 1_000_000, false, i.toLong(), LongArray(8)) }
    frames.record(200_000_000, true, 90, LongArray(8))
    frames.record(100_000_000, true, 100, LongArray(8))
    val snapshot = frames.snapshot()
    assertEquals(34L, snapshot.getLong("eligibleFrames")); assertEquals(2L, snapshot.getLong("omittedFrames"))
    val retained = snapshot.getJSONArray("frames")
    assertEquals(200.0, retained.getJSONObject(31).getDouble("totalMs"), 0.0)
    assertEquals(0L, retained.getJSONObject(31).getLong("elapsedMs"))
  }

  @Test fun arityIsValidatedBeforeMutationAndSnapshotsAreDetached() {
    val frames = FramePerformanceSlowFrames()
    try { frames.record(100_000_000, true, 0, LongArray(7)); fail("Bad arity accepted") }
    catch (_: IllegalArgumentException) {}
    assertEquals(0L, frames.snapshot().getLong("eligibleFrames"))
    frames.record(100_000_000, true, 0, LongArray(8))
    frames.snapshot().getJSONArray("frames").getJSONObject(0).put("totalMs", 999.0)
    assertEquals(100.0, frames.snapshot().getJSONArray("frames").getJSONObject(0).getDouble("totalMs"), 0.0)
  }
}
