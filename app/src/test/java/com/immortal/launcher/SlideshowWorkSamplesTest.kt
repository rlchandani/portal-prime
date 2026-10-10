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

class SlideshowWorkSamplesTest {
  @Test fun emptySnapshotExposesOnlyFixedStagesAndNullDurations() {
    val snapshot = SlideshowWorkSamples().snapshot()
    assertCounts(snapshot, 0L)
    assertEquals(0, snapshot.getJSONArray("samples").length())
    expectedKinds.keys.forEach { assertStage(snapshot, it) }
  }

  @Test fun elapsedStagesAggregateFractionalDurationsSeparatelyBySuppliedThread() {
    val samples = SlideshowWorkSamples()
    assertTrue(samples.record(SlideshowWorkStage.METADATA_PREPARATION, 12L, 250_000L, true))
    assertTrue(samples.record(SlideshowWorkStage.METADATA_PREPARATION, 13L, 1_500_000L, false))
    assertTrue(samples.record(SlideshowWorkStage.METADATA_PREPARATION, 14L, 3_250_000L, true))
    assertTrue(samples.record(SlideshowWorkStage.WEB_VIEW_ADMISSION, 15L, 1L, false))
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 4L)
    assertStage(snapshot, "metadataPreparation", 3L, 2L, 1L, 5.0, 5.0 / 3.0, 3.25,
        3.5, 1.75, 3.25, 1.5, 1.5, 1.5)
    assertStage(snapshot, "webViewAdmission", records = 1L, other = 1L,
        total = .000001, mean = .000001, max = .000001,
        otherTotal = .000001, otherMean = .000001, otherMax = .000001)
    assertStage(snapshot, "enginePageFinished")
    assertStage(snapshot, "engineRetired")
    val retained = snapshot.getJSONArray("samples")
    assertSample(retained.getJSONObject(0), "metadataPreparation", 12L, .25, true)
    assertSample(retained.getJSONObject(1), "metadataPreparation", 13L, 1.5, false)
    assertSample(retained.getJSONObject(2), "metadataPreparation", 14L, 3.25, true)
    assertSample(retained.getJSONObject(3), "webViewAdmission", 15L, .000001, false)
  }

  @Test fun eventsCountThreadsWithoutInventingElapsedDurations() {
    val samples = SlideshowWorkSamples()
    assertTrue(samples.record(SlideshowWorkStage.ENGINE_PAGE_FINISHED, 2L, 0L, true))
    assertTrue(samples.record(SlideshowWorkStage.ENGINE_PAGE_FINISHED, 3L, 0L, false))
    assertTrue(samples.record(SlideshowWorkStage.ENGINE_RETIRED, 4L, 0L, true))
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 3L)
    assertStage(snapshot, "enginePageFinished", records = 2L, main = 1L, other = 1L)
    assertStage(snapshot, "engineRetired", records = 1L, main = 1L)
    assertSample(snapshot.getJSONArray("samples").getJSONObject(2), "engineRetired", 4L, 0.0, true)
  }

  @Test fun zeroAndInclusiveUpperBoundsAreAcceptedForEveryStage() {
    val samples = SlideshowWorkSamples()
    for (stage in SlideshowWorkStage.entries) {
      assertTrue(samples.record(stage, 0L, 0L, true))
      assertTrue(samples.record(stage, 900_000L, if (stage.event) 0L else 900_000_000_000L, false))
    }
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 8L)
    for ((key, kind) in expectedKinds) {
      if (kind == "event") assertStage(snapshot, key, records = 2L, main = 1L, other = 1L)
      else assertStage(snapshot, key, 2L, 1L, 1L, 900_000.0, 450_000.0, 900_000.0,
          0.0, 0.0, 0.0, 900_000.0, 900_000.0, 900_000.0)
    }
    val retained = snapshot.getJSONArray("samples")
    assertSample(retained.getJSONObject(0), "metadataPreparation", 0L, 0.0, true)
    assertSample(retained.getJSONObject(1), "metadataPreparation", 900_000L, 900_000.0, false)
    assertSample(retained.getJSONObject(7), "engineRetired", 900_000L, 0.0, false)
  }

  @Test fun invalidBoundsCannotAlterRetentionOrStageAggregates() {
    val samples = SlideshowWorkSamples()
    assertTrue(samples.record(SlideshowWorkStage.WEB_VIEW_ADMISSION, 5L, 250_000L, false))
    val invalid = listOf(
        -1L to 0L, 900_001L to 0L, Long.MIN_VALUE to 0L, Long.MAX_VALUE to 0L,
        5L to -1L, 5L to 900_000_000_001L, 5L to Long.MIN_VALUE, 5L to Long.MAX_VALUE)
    for (stage in SlideshowWorkStage.entries) for ((elapsed, duration) in invalid)
      assertFalse(samples.record(stage, elapsed, duration, true))
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 1L, invalid = 32L)
    assertEquals(1, snapshot.getJSONArray("samples").length())
    assertStage(snapshot, "webViewAdmission", records = 1L, other = 1L,
        total = .25, mean = .25, max = .25, otherTotal = .25, otherMean = .25, otherMax = .25)
    assertStage(snapshot, "metadataPreparation")
    assertStage(snapshot, "enginePageFinished")
    assertStage(snapshot, "engineRetired")
    // Invalid input must not poison subsequent admission.
    assertTrue(samples.record(SlideshowWorkStage.METADATA_PREPARATION, 0L, 0L, true))
    assertCounts(samples.snapshot(), 2L, invalid = 32L)
  }

  @Test fun eventStagesRejectEveryNonZeroDuration() {
    val samples = SlideshowWorkSamples()
    for (stage in listOf(SlideshowWorkStage.ENGINE_PAGE_FINISHED, SlideshowWorkStage.ENGINE_RETIRED)) {
      for (duration in listOf(-1L, 1L, 250_000L, 900_000_000_000L, 900_000_000_001L))
        assertFalse(samples.record(stage, 10L, duration, false))
      assertTrue(samples.record(stage, 10L, 0L, false))
    }
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 2L, invalid = 10L)
    assertStage(snapshot, "enginePageFinished", records = 1L, other = 1L)
    assertStage(snapshot, "engineRetired", records = 1L, other = 1L)
    assertEquals(2, snapshot.getJSONArray("samples").length())
  }

  @Test fun first128SamplesRemainWhileDiscardedRecordsContinueToAggregate() {
    val samples = SlideshowWorkSamples()
    repeat(128) { index ->
      assertTrue(samples.record(SlideshowWorkStage.METADATA_PREPARATION, index.toLong(),
          (index + 1) * 1_000_000L, index % 2 == 0))
    }
    assertTrue(samples.record(SlideshowWorkStage.METADATA_PREPARATION, 800_000L, 3_000_000L, false))
    assertTrue(samples.record(SlideshowWorkStage.WEB_VIEW_ADMISSION, 900_000L, 2_000_000L, true))
    assertTrue(samples.record(SlideshowWorkStage.ENGINE_RETIRED, 900_000L, 0L, false))
    assertFalse(samples.record(SlideshowWorkStage.METADATA_PREPARATION, 900_001L, 0L, true))
    assertFalse(samples.record(SlideshowWorkStage.ENGINE_RETIRED, 1L, 1L, true))
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 131L, invalid = 2L, discarded = 3L)
    assertStage(snapshot, "metadataPreparation", 129L, 64L, 65L, 8259.0, 8259.0 / 129.0, 128.0,
        4096.0, 64.0, 127.0, 4163.0, 4163.0 / 65.0, 128.0)
    assertStage(snapshot, "webViewAdmission", records = 1L, main = 1L,
        total = 2.0, mean = 2.0, max = 2.0, mainTotal = 2.0, mainMean = 2.0, mainMax = 2.0)
    assertStage(snapshot, "engineRetired", records = 1L, other = 1L)
    val retained = snapshot.getJSONArray("samples")
    assertEquals(128, retained.length())
    repeat(128) { index ->
      assertSample(retained.getJSONObject(index), "metadataPreparation", index.toLong(),
          (index + 1).toDouble(), index % 2 == 0)
    }
  }

  @Test fun maximalValidDurationsAggregateBeyondLongNanosecondCapacity() {
    val samples = SlideshowWorkSamples()
    // Each thread bucket exceeds Long.MAX_VALUE in summed nanoseconds; retention stays constant.
    repeat(20_500_000) { index ->
      samples.record(SlideshowWorkStage.METADATA_PREPARATION, 0L, 900_000_000_000L, index % 2 == 0)
    }
    val snapshot = samples.snapshot()
    assertCounts(snapshot, 20_500_000L, discarded = 20_499_872L)
    assertStage(snapshot, "metadataPreparation", records = 20_500_000L,
        main = 10_250_000L, other = 10_250_000L,
        total = 18_450_000_000_000.0, mean = 900_000.0, max = 900_000.0,
        mainTotal = 9_225_000_000_000.0, mainMean = 900_000.0, mainMax = 900_000.0,
        otherTotal = 9_225_000_000_000.0, otherMean = 900_000.0, otherMax = 900_000.0)
    assertEquals(128, snapshot.getJSONArray("samples").length())
  }

  @Test fun snapshotsDoNotShareStateWithLaterRecordsOrReturnedJsonMutation() {
    val samples = SlideshowWorkSamples()
    samples.record(SlideshowWorkStage.WEB_VIEW_ADMISSION, 4L, 1_500_000L, true)
    val first = samples.snapshot()
    val sibling = samples.snapshot()
    samples.record(SlideshowWorkStage.WEB_VIEW_ADMISSION, 5L, 250_000L, false)
    first.put("records", 999L)
    first.getJSONArray("samples").getJSONObject(0).put("durationMs", 999.0)
    first.getJSONObject("stages").getJSONObject("webViewAdmission").put("mainThreadTotalMs", 999.0)
    first.getJSONObject("stages").remove("engineRetired")
    assertCounts(sibling, 1L)
    assertSample(sibling.getJSONArray("samples").getJSONObject(0), "webViewAdmission", 4L, 1.5, true)
    assertStage(sibling, "webViewAdmission", records = 1L, main = 1L,
        total = 1.5, mean = 1.5, max = 1.5, mainTotal = 1.5, mainMean = 1.5, mainMax = 1.5)
    val latest = samples.snapshot()
    assertCounts(latest, 2L)
    assertStage(latest, "webViewAdmission", 2L, 1L, 1L, 1.75, .875, 1.5,
        1.5, 1.5, 1.5, .25, .25, .25)
  }

  @Test fun independentSessionsDoNotShareSamplesCountersOrThreadDurations() {
    val first = SlideshowWorkSamples()
    val second = SlideshowWorkSamples()
    repeat(129) { first.record(SlideshowWorkStage.WEB_VIEW_ADMISSION, 1L, 1_000_000L, true) }
    first.record(SlideshowWorkStage.ENGINE_RETIRED, 1L, 1L, true)
    assertCounts(second.snapshot(), 0L)
    second.record(SlideshowWorkStage.METADATA_PREPARATION, 2L, 1_500_000L, false)
    second.record(SlideshowWorkStage.ENGINE_RETIRED, 3L, 0L, true)
    assertCounts(first.snapshot(), 129L, invalid = 1L, discarded = 1L)
    assertStage(first.snapshot(), "webViewAdmission", records = 129L, main = 129L,
        total = 129.0, mean = 1.0, max = 1.0, mainTotal = 129.0, mainMean = 1.0, mainMax = 1.0)
    assertCounts(second.snapshot(), 2L)
    assertStage(second.snapshot(), "metadataPreparation", records = 1L, other = 1L,
        total = 1.5, mean = 1.5, max = 1.5, otherTotal = 1.5, otherMean = 1.5, otherMax = 1.5)
    assertStage(second.snapshot(), "webViewAdmission")
  }

  private val expectedKinds = linkedMapOf(
      "metadataPreparation" to "elapsed", "webViewAdmission" to "elapsed",
      "enginePageFinished" to "event", "engineRetired" to "event")

  private fun assertCounts(snapshot: JSONObject, records: Long, invalid: Long = 0L, discarded: Long = 0L) {
    assertEquals(setOf("measurement", "records", "invalidRecords", "discardedRecords",
        "maxRecords", "samples", "stages"), snapshot.keys().asSequence().toSet())
    assertEquals("elapsed wall time; overlapping spans; not CPU time", snapshot.getString("measurement"))
    assertEquals(128, snapshot.getInt("maxRecords"))
    assertEquals(records, snapshot.getLong("records"))
    assertEquals(invalid, snapshot.getLong("invalidRecords"))
    assertEquals(discarded, snapshot.getLong("discardedRecords"))
    assertEquals(expectedKinds.keys, snapshot.getJSONObject("stages").keys().asSequence().toSet())
  }

  private fun assertStage(snapshot: JSONObject, key: String, records: Long = 0L,
      main: Long = 0L, other: Long = 0L, total: Double? = null, mean: Double? = null, max: Double? = null,
      mainTotal: Double? = null, mainMean: Double? = null, mainMax: Double? = null,
      otherTotal: Double? = null, otherMean: Double? = null, otherMax: Double? = null) {
    val stage = snapshot.getJSONObject("stages").getJSONObject(key)
    assertEquals(setOf("kind", "records", "mainThreadRecords", "otherThreadRecords",
        "totalMs", "meanMs", "maxMs", "mainThreadTotalMs", "mainThreadMeanMs", "mainThreadMaxMs",
        "otherThreadTotalMs", "otherThreadMeanMs", "otherThreadMaxMs"), stage.keys().asSequence().toSet())
    assertEquals(expectedKinds.getValue(key), stage.getString("kind"))
    assertEquals(records, stage.getLong("records"))
    assertEquals(main, stage.getLong("mainThreadRecords"))
    assertEquals(other, stage.getLong("otherThreadRecords"))
    for ((field, value) in mapOf("totalMs" to total, "meanMs" to mean, "maxMs" to max,
        "mainThreadTotalMs" to mainTotal, "mainThreadMeanMs" to mainMean, "mainThreadMaxMs" to mainMax,
        "otherThreadTotalMs" to otherTotal, "otherThreadMeanMs" to otherMean, "otherThreadMaxMs" to otherMax)) {
      if (value == null) assertSame(field, JSONObject.NULL, stage.get(field))
      else assertEquals(field, value, stage.getDouble(field), 1e-9)
    }
  }

  private fun assertSample(sample: JSONObject, stage: String, elapsedMs: Long,
      durationMs: Double, onMain: Boolean) {
    assertEquals(setOf("stage", "elapsedMs", "durationMs", "onMain"), sample.keys().asSequence().toSet())
    assertEquals(stage, sample.getString("stage"))
    assertEquals(elapsedMs, sample.getLong("elapsedMs"))
    assertEquals(durationMs, sample.getDouble("durationMs"), 0.0)
    assertEquals(onMain, sample.getBoolean("onMain"))
  }
}
