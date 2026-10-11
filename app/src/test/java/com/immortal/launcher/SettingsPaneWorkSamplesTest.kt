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

class SettingsPaneWorkSamplesTest {
  @Test fun emptySnapshotUsesOnlyFixedPaneKeysAndNullUnobservedDurations() {
    val snapshot = SettingsPaneWorkSamples().snapshot()

    assertEquals(listOf("appearance", "touch", "wallpaper", "quickbar", "other"),
        SettingsPaneWorkId.entries.map { it.key })
    assertCounts(snapshot, records = 0L)
    assertEquals(0, snapshot.getJSONArray("samples").length())
    for (pane in paneKeys) for (active in listOf(false, true)) {
      assertPane(snapshot, pane, active)
    }
  }

  @Test fun activeAndInactiveWorkAndThreadCountsStaySeparateForEveryPane() {
    val samples = SettingsPaneWorkSamples()
    for (pane in SettingsPaneWorkId.entries) {
      assertTrue(samples.record(pane, true, 12L, 250_000L, 10, 100, 20, 200, true))
      assertTrue(samples.record(pane, false, 13L, 2_000_000L, 30, 300, 40, 400, false))
      assertTrue(samples.record(pane, true, 14L, 1_500_000L, 50, 500, 60, 600, false))
      assertTrue(samples.record(pane, false, 15L, 4_000_000L, 70, 700, 80, 800, true))
    }

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 20L)
    val retained = snapshot.getJSONArray("samples")
    assertEquals(20, retained.length())
    paneKeys.forEachIndexed { index, key ->
      assertPane(snapshot, key, true, records = 2L, main = 1L, other = 1L,
          total = 1.75, mean = 0.875, max = 1.5)
      assertPane(snapshot, key, false, records = 2L, main = 1L, other = 1L,
          total = 6.0, mean = 3.0, max = 4.0)
      assertSample(retained.getJSONObject(index * 4), key, true, 12L, 0.25, 10, 100, 20, 200, true)
      assertSample(retained.getJSONObject(index * 4 + 1), key, false, 13L, 2.0, 30, 300, 40, 400, false)
      assertSample(retained.getJSONObject(index * 4 + 2), key, true, 14L, 1.5, 50, 500, 60, 600, false)
      assertSample(retained.getJSONObject(index * 4 + 3), key, false, 15L, 4.0, 70, 700, 80, 800, true)
    }
  }

  @Test fun singleNanosecondSurvivesFractionalMillisecondConversion() {
    val samples = SettingsPaneWorkSamples()
    assertTrue(samples.record(SettingsPaneWorkId.TOUCH, false, 0L, 1L, 0, 1, 0, 1, false))

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 1L)
    assertPane(snapshot, "touch", false, records = 1L, other = 1L,
        total = 0.000001, mean = 0.000001, max = 0.000001)
    assertSample(snapshot.getJSONArray("samples").getJSONObject(0), "touch", false,
        0L, 0.000001, 0, 1, 0, 1, false)
  }

  @Test fun inclusiveTimeBoundsAndZeroOrUnboundedConstraintsAreAccepted() {
    val samples = SettingsPaneWorkSamples()
    for (pane in SettingsPaneWorkId.entries) {
      assertTrue(samples.record(pane, false, 0L, 0L, 0, 0, 0, 0, true))
      assertTrue(samples.record(pane, true, 900_000L, 900_000_000_000L,
          17, Int.MAX_VALUE, 19, Int.MAX_VALUE, false))
      assertTrue(samples.record(pane, true, 0L, 0L,
          Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, true))
    }

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 15L)
    paneKeys.forEachIndexed { index, key ->
      assertPane(snapshot, key, false, records = 1L, main = 1L, total = 0.0, mean = 0.0, max = 0.0)
      assertPane(snapshot, key, true, records = 2L, main = 1L, other = 1L,
          total = 900_000.0, mean = 450_000.0, max = 900_000.0)
      val retained = snapshot.getJSONArray("samples")
      assertSample(retained.getJSONObject(index * 3), key, false, 0L, 0.0, 0, 0, 0, 0, true)
      assertSample(retained.getJSONObject(index * 3 + 1), key, true, 900_000L, 900_000.0,
          17, Int.MAX_VALUE, 19, Int.MAX_VALUE, false)
      assertSample(retained.getJSONObject(index * 3 + 2), key, true, 0L, 0.0,
          Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, true)
    }
  }

  @Test fun invalidTimesNeverReachRetentionOrAggregatesEvenAfterBufferFills() {
    val invalidTimes = listOf(
      -1L to 0L, 900_001L to 0L, Long.MIN_VALUE to 0L, Long.MAX_VALUE to 0L,
      0L to -1L, 0L to 900_000_000_001L, 0L to Long.MIN_VALUE, 0L to Long.MAX_VALUE,
    )
    for (full in listOf(false, true)) {
      val samples = SettingsPaneWorkSamples()
      if (full) repeat(512) {
        assertTrue(samples.record(SettingsPaneWorkId.APPEARANCE, true,
            0L, 250_000L, 0, 100, 0, 100, true))
      }
      invalidTimes.forEachIndexed { index, (elapsedMs, durationNs) ->
        assertFalse(samples.record(SettingsPaneWorkId.APPEARANCE, true,
            elapsedMs, durationNs, 0, 100, 0, 100, false))
        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = if (full) 512L else 0L, invalid = index + 1L)
        assertEquals(if (full) 512 else 0, snapshot.getJSONArray("samples").length())
        assertPane(snapshot, "appearance", true, records = if (full) 512L else 0L,
            main = if (full) 512L else 0L, total = if (full) 128.0 else 0.0,
            mean = if (full) 0.25 else null, max = if (full) 0.25 else null)
      }
      assertTrue(samples.record(SettingsPaneWorkId.OTHER, false, 0L, 0L, 0, 0, 0, 0, false))
      val recovered = samples.snapshot()
      assertCounts(recovered, records = if (full) 513L else 1L, invalid = 8L,
          discarded = if (full) 1L else 0L)
      assertPane(recovered, "other", false, records = 1L, other = 1L,
          total = 0.0, mean = 0.0, max = 0.0)
    }
  }

  @Test fun negativeOrReversedConstraintsAreRejectedWithoutChangingValidSamples() {
    val samples = SettingsPaneWorkSamples()
    assertTrue(samples.record(SettingsPaneWorkId.WALLPAPER, false, 1L, 1_500_000L,
        10, 20, 30, 40, true))
    val invalidConstraints = listOf(
      intArrayOf(-1, 20, 30, 40), intArrayOf(10, -1, 30, 40),
      intArrayOf(10, 20, -1, 40), intArrayOf(10, 20, 30, -1),
      intArrayOf(21, 20, 30, 40), intArrayOf(10, 20, 41, 40),
      intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, 0, 0),
      intArrayOf(0, 0, Int.MIN_VALUE, Int.MAX_VALUE),
    )
    invalidConstraints.forEachIndexed { index, bounds ->
      assertFalse("Invalid constraints $index were accepted", samples.record(
          SettingsPaneWorkId.WALLPAPER, false, 0L, 0L,
          bounds[0], bounds[1], bounds[2], bounds[3], false))
      val snapshot = samples.snapshot()
      assertCounts(snapshot, records = 1L, invalid = index + 1L)
      assertPane(snapshot, "wallpaper", false, records = 1L, main = 1L,
          total = 1.5, mean = 1.5, max = 1.5)
      assertEquals(1, snapshot.getJSONArray("samples").length())
      assertSample(snapshot.getJSONArray("samples").getJSONObject(0), "wallpaper", false,
          1L, 1.5, 10, 20, 30, 40, true)
    }
  }

  @Test fun first512SamplesAreRetainedWhileLaterWorkStillUpdatesEveryAggregate() {
    val samples = SettingsPaneWorkSamples()
    repeat(512) { index ->
      assertTrue(samples.record(SettingsPaneWorkId.QUICKBAR, true, index.toLong(),
          250_000L, index, index + 10, index + 20, Int.MAX_VALUE, index % 2 == 0))
    }
    val full = samples.snapshot()
    assertCounts(full, records = 512L)
    assertEquals(512, full.getJSONArray("samples").length())
    assertPane(full, "quickbar", true, records = 512L, main = 256L, other = 256L,
        total = 128.0, mean = 0.25, max = 0.25)

    assertTrue(samples.record(SettingsPaneWorkId.QUICKBAR, true, 800_000L,
        64_000_000L, 0, 0, 0, 0, false))
    assertTrue(samples.record(SettingsPaneWorkId.QUICKBAR, false, 900_000L,
        2_000_000L, 0, Int.MAX_VALUE, 0, Int.MAX_VALUE, true))
    assertTrue(samples.record(SettingsPaneWorkId.OTHER, false, 5L,
        1_500_000L, 1, 1, 2, 2, false))
    assertFalse(samples.record(SettingsPaneWorkId.QUICKBAR, true, -1L, 0L, 0, 0, 0, 0, true))

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 515L, invalid = 1L, discarded = 3L)
    assertPane(snapshot, "quickbar", true, records = 513L, main = 256L, other = 257L,
        total = 192.0, mean = 192.0 / 513.0, max = 64.0)
    assertPane(snapshot, "quickbar", false, records = 1L, main = 1L,
        total = 2.0, mean = 2.0, max = 2.0)
    assertPane(snapshot, "other", false, records = 1L, other = 1L,
        total = 1.5, mean = 1.5, max = 1.5)
    val retained = snapshot.getJSONArray("samples")
    assertEquals(512, retained.length())
    repeat(512) { index ->
      assertSample(retained.getJSONObject(index), "quickbar", true, index.toLong(),
          0.25, index, index + 10, index + 20, Int.MAX_VALUE, index % 2 == 0)
    }
  }

  @Test fun totalDurationDoesNotOverflowWhenValidNanosecondSumExceedsLongRange() {
    val samples = SettingsPaneWorkSamples()
    // 10,248,192 * 900,000,000,000 ns exceeds Long.MAX_VALUE; the millisecond sum is exact.
    repeat(10_248_192) {
      if (!samples.record(SettingsPaneWorkId.TOUCH, true, 0L,
          900_000_000_000L, 0, 0, 0, 0, true)) fail("Valid duration was rejected")
    }

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 10_248_192L, discarded = 10_247_680L)
    assertEquals(512, snapshot.getJSONArray("samples").length())
    assertPane(snapshot, "touch", true, records = 10_248_192L, main = 10_248_192L,
        total = 9_223_372_800_000.0, mean = 900_000.0, max = 900_000.0)
    assertPane(snapshot, "touch", false)
  }

  @Test fun snapshotsAreDetachedAndCollectorsDoNotShareState() {
    val samples = SettingsPaneWorkSamples()
    val other = SettingsPaneWorkSamples()
    assertTrue(samples.record(SettingsPaneWorkId.APPEARANCE, true, 12L,
        250_000L, 10, 100, 20, 200, true))
    val earlier = samples.snapshot()
    val modified = samples.snapshot()
    modified.getJSONArray("samples").getJSONObject(0).put("pane", "untrusted")
    modified.getJSONObject("panes").getJSONObject("appearance")
        .getJSONObject("active").put("records", 99L)
    assertTrue(samples.record(SettingsPaneWorkId.APPEARANCE, true, 13L,
        1_500_000L, 30, 300, 40, 400, false))

    assertCounts(earlier, records = 1L)
    assertEquals(1, earlier.getJSONArray("samples").length())
    assertPane(earlier, "appearance", true, records = 1L, main = 1L,
        total = 0.25, mean = 0.25, max = 0.25)
    val latest = samples.snapshot()
    assertCounts(latest, records = 2L)
    assertPane(latest, "appearance", true, records = 2L, main = 1L, other = 1L,
        total = 1.75, mean = 0.875, max = 1.5)
    assertSample(latest.getJSONArray("samples").getJSONObject(0), "appearance", true,
        12L, 0.25, 10, 100, 20, 200, true)
    assertCounts(other.snapshot(), records = 0L)
    assertEquals(0, other.snapshot().getJSONArray("samples").length())
    assertPane(other.snapshot(), "appearance", true)
  }

  private val paneKeys = listOf("appearance", "touch", "wallpaper", "quickbar", "other")

  private fun assertCounts(
    snapshot: JSONObject,
    records: Long,
    invalid: Long = 0L,
    discarded: Long = 0L,
  ) {
    assertEquals(setOf("measurement", "records", "invalidRecords", "discardedRecords",
        "maxRecords", "samples", "panes"), snapshot.keys().asSequence().toSet())
    val measurement = snapshot.getString("measurement")
    assertTrue(measurement, measurement.contains("inclusive"))
    assertTrue(measurement, measurement.contains("wall time"))
    assertTrue(measurement, measurement.contains("nested"))
    assertTrue(measurement, measurement.contains("not additive"))
    assertEquals(512, snapshot.get("maxRecords"))
    assertEquals(512, SettingsPaneWorkSamples.MAX_RECORDS)
    assertEquals(records, snapshot.get("records"))
    assertEquals(invalid, snapshot.get("invalidRecords"))
    assertEquals(discarded, snapshot.get("discardedRecords"))
    val panes = snapshot.getJSONObject("panes")
    assertEquals(paneKeys.toSet(), panes.keys().asSequence().toSet())
    for (pane in paneKeys) {
      assertEquals(setOf("active", "inactive"), panes.getJSONObject(pane).keys().asSequence().toSet())
    }
  }

  private fun assertPane(
    snapshot: JSONObject,
    key: String,
    active: Boolean,
    records: Long = 0L,
    main: Long = 0L,
    other: Long = 0L,
    total: Double = 0.0,
    mean: Double? = null,
    max: Double? = null,
  ) {
    val pane = snapshot.getJSONObject("panes").getJSONObject(key)
        .getJSONObject(if (active) "active" else "inactive")
    assertEquals(setOf("records", "mainThreadRecords", "otherThreadRecords", "totalMs", "meanMs", "maxMs"),
        pane.keys().asSequence().toSet())
    assertEquals(records, pane.get("records"))
    assertEquals(main, pane.get("mainThreadRecords"))
    assertEquals(other, pane.get("otherThreadRecords"))
    assertTrue(pane.get("totalMs") is Double)
    assertEquals(total, pane.getDouble("totalMs"), 0.0)
    if (mean == null) assertSame(JSONObject.NULL, pane.get("meanMs")) else {
      assertTrue(pane.get("meanMs") is Double)
      assertEquals(mean, pane.getDouble("meanMs"), 1e-12)
    }
    if (max == null) assertSame(JSONObject.NULL, pane.get("maxMs")) else {
      assertTrue(pane.get("maxMs") is Double)
      assertEquals(max, pane.getDouble("maxMs"), 0.0)
    }
  }

  private fun assertSample(
    sample: JSONObject,
    pane: String,
    active: Boolean,
    elapsedMs: Long,
    durationMs: Double,
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int,
    onMain: Boolean,
  ) {
    assertEquals(setOf("pane", "active", "elapsedMs", "durationMs", "minWidth", "maxWidth",
        "minHeight", "maxHeight", "onMain"), sample.keys().asSequence().toSet())
    assertEquals(pane, sample.getString("pane"))
    assertEquals(active, sample.get("active"))
    assertEquals(elapsedMs, sample.get("elapsedMs"))
    assertTrue(sample.get("durationMs") is Double)
    assertEquals(durationMs, sample.getDouble("durationMs"), 0.0)
    assertEquals(minWidth, sample.get("minWidth"))
    assertEquals(maxWidth, sample.get("maxWidth"))
    assertEquals(minHeight, sample.get("minHeight"))
    assertEquals(maxHeight, sample.get("maxHeight"))
    assertEquals(onMain, sample.get("onMain"))
  }
}
