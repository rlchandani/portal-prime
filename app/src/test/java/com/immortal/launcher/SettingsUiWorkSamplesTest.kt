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

class SettingsUiWorkSamplesTest {
  @Test fun emptySnapshotHasOnlyFixedSchemaAndNullDurations() {
    val snapshot = SettingsUiWorkSamples().snapshot()

    assertEquals(128, SettingsUiWorkSamples.MAX_RECORDS)
    assertEquals(expectedStageKinds, SettingsUiWorkStage.entries.associate {
      it.key to if (it.event) "event" else "elapsed"
    })
    assertCounts(snapshot, records = 0L)
    assertEquals(0, snapshot.getJSONArray("samples").length())
    assertEmptyStages(snapshot)
  }

  @Test fun mixedElapsedAndEventRecordsAggregatePerStageAndThread() {
    val samples = SettingsUiWorkSamples()
    assertTrue(samples.record(SettingsUiWorkStage.SHELL_COMPOSITION, 12L, 250_000L, true))
    assertTrue(samples.record(SettingsUiWorkStage.VERSION_PUBLISHED, 13L, 0L, true))
    assertTrue(samples.record(SettingsUiWorkStage.SEARCH_MEASURE, 14L, 2_000_000L, false))
    assertTrue(samples.record(SettingsUiWorkStage.SHELL_COMPOSITION, 15L, 1_500_000L, false))
    assertTrue(samples.record(SettingsUiWorkStage.VERSION_PUBLISHED, 16L, 0L, false))
    assertTrue(samples.record(SettingsUiWorkStage.SEARCH_MEASURE, 17L, 4_000_000L, true))
    assertTrue(samples.record(SettingsUiWorkStage.PANE_EFFECT_START, 18L, 0L, false))
    assertTrue(samples.record(SettingsUiWorkStage.SHELL_COMPOSITION, 19L, 3_250_000L, true))

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 8L)
    assertStage(snapshot, "shellComposition", records = 3L, main = 2L, other = 1L,
        mean = 5.0 / 3.0, max = 3.25)
    assertStage(snapshot, "searchMeasure", records = 2L, main = 1L, other = 1L,
        mean = 3.0, max = 4.0)
    assertStage(snapshot, "versionPublished", records = 2L, main = 1L, other = 1L)
    assertStage(snapshot, "paneEffectStart", records = 1L, other = 1L)
    assertEmptyStages(snapshot, setOf("shellComposition", "searchMeasure",
        "versionPublished", "paneEffectStart"))
    val retained = snapshot.getJSONArray("samples")
    assertEquals(8, retained.length())
    assertSample(retained.getJSONObject(0), "shellComposition", 12L, 0.25, true)
    assertSample(retained.getJSONObject(1), "versionPublished", 13L, 0.0, true)
    assertSample(retained.getJSONObject(2), "searchMeasure", 14L, 2.0, false)
    assertSample(retained.getJSONObject(3), "shellComposition", 15L, 1.5, false)
    assertSample(retained.getJSONObject(4), "versionPublished", 16L, 0.0, false)
    assertSample(retained.getJSONObject(5), "searchMeasure", 17L, 4.0, true)
    assertSample(retained.getJSONObject(6), "paneEffectStart", 18L, 0.0, false)
    assertSample(retained.getJSONObject(7), "shellComposition", 19L, 3.25, true)
  }

  @Test fun fractionalMillisecondsArePreservedWithoutIntegerTruncation() {
    val samples = SettingsUiWorkSamples()
    assertTrue(samples.record(SettingsUiWorkStage.DETAIL_MEASURE, 12L, 250_000L, true))
    assertTrue(samples.record(SettingsUiWorkStage.DETAIL_MEASURE, 87L, 1_500_000L, false))
    assertTrue(samples.record(SettingsUiWorkStage.SEARCH_COMPOSITION, 88L, 1L, false))

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 3L)
    assertStage(snapshot, "detailMeasure", records = 2L, main = 1L, other = 1L,
        mean = 0.875, max = 1.5)
    assertStage(snapshot, "searchComposition", records = 1L, other = 1L,
        mean = 0.000001, max = 0.000001)
    assertEmptyStages(snapshot, setOf("detailMeasure", "searchComposition"))
    val retained = snapshot.getJSONArray("samples")
    assertEquals(3, retained.length())
    assertSample(retained.getJSONObject(0), "detailMeasure", 12L, 0.25, true)
    assertSample(retained.getJSONObject(1), "detailMeasure", 87L, 1.5, false)
    assertSample(retained.getJSONObject(2), "searchComposition", 88L, 0.000001, false)
  }

  @Test fun everyStageAcceptsZeroElapsedAndZeroDuration() {
    val samples = SettingsUiWorkSamples()
    SettingsUiWorkStage.entries.forEach { stage ->
      assertTrue(stage.key, samples.record(stage, 0L, 0L, onMain = !stage.event))
    }

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 11L)
    val retained = snapshot.getJSONArray("samples")
    assertEquals(11, retained.length())
    expectedStageKinds.entries.forEachIndexed { index, (key, kind) ->
      val event = kind == "event"
      assertStage(snapshot, key, records = 1L,
          main = if (event) 0L else 1L, other = if (event) 1L else 0L,
          mean = if (event) null else 0.0, max = if (event) null else 0.0)
      assertSample(retained.getJSONObject(index), key, 0L, 0.0, !event)
    }
  }

  @Test fun inclusiveUpperBoundsAreAcceptedForTimedAndEventStages() {
    val samples = SettingsUiWorkSamples()
    SettingsUiWorkStage.entries.forEach { stage ->
      val durationNs = if (stage.event) 0L else 900_000_000_000L
      assertTrue(stage.key, samples.record(stage, 900_000L, durationNs, onMain = stage.event))
    }

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 11L)
    val retained = snapshot.getJSONArray("samples")
    assertEquals(11, retained.length())
    expectedStageKinds.entries.forEachIndexed { index, (key, kind) ->
      val event = kind == "event"
      assertStage(snapshot, key, records = 1L,
          main = if (event) 1L else 0L, other = if (event) 0L else 1L,
          mean = if (event) null else 900_000.0, max = if (event) null else 900_000.0)
      assertSample(retained.getJSONObject(index), key, 900_000L,
          if (event) 0.0 else 900_000.0, event)
    }
  }

  @Test fun invalidBoundsOnlyIncrementInvalidRecordsForEmptyAndPopulatedState() {
    val invalidInputs = listOf(
      -1L to 0L,
      900_001L to 0L,
      Long.MIN_VALUE to 0L,
      Long.MAX_VALUE to 0L,
      12L to -1L,
      12L to 900_000_000_001L,
      12L to Long.MIN_VALUE,
      12L to Long.MAX_VALUE,
    )
    for (populated in listOf(false, true)) {
      val samples = SettingsUiWorkSamples()
      if (populated) {
        assertTrue(samples.record(SettingsUiWorkStage.SHELL_MEASURE, 12L, 250_000L, true))
      }
      invalidInputs.forEachIndexed { index, (elapsedMs, durationNs) ->
        assertFalse("Invalid input $index was accepted (populated=$populated)",
            samples.record(SettingsUiWorkStage.SHELL_MEASURE, elapsedMs, durationNs,
                onMain = index % 2 == 0))
        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = if (populated) 1L else 0L,
            invalid = index.toLong() + 1L)
        assertStage(snapshot, "shellMeasure", records = if (populated) 1L else 0L,
            main = if (populated) 1L else 0L,
            mean = if (populated) 0.25 else null, max = if (populated) 0.25 else null)
        assertEmptyStages(snapshot, setOf("shellMeasure"))
        val retained = snapshot.getJSONArray("samples")
        assertEquals(if (populated) 1 else 0, retained.length())
        if (populated) assertSample(retained.getJSONObject(0), "shellMeasure", 12L, 0.25, true)
      }

      assertTrue(samples.record(SettingsUiWorkStage.DETAIL_MEASURE, 0L, 0L, false))
      val recovered = samples.snapshot()
      assertCounts(recovered, records = if (populated) 2L else 1L, invalid = 8L)
      assertStage(recovered, "detailMeasure", records = 1L, other = 1L, mean = 0.0, max = 0.0)
      val retained = recovered.getJSONArray("samples")
      assertEquals(if (populated) 2 else 1, retained.length())
      assertSample(retained.getJSONObject(if (populated) 1 else 0), "detailMeasure", 0L, 0.0, false)
    }
  }

  @Test fun eventsRejectNonZeroDurationsAndOutOfRangeElapsedTimes() {
    val samples = SettingsUiWorkSamples()
    assertTrue(samples.record(SettingsUiWorkStage.VERSION_PUBLISHED, 12L, 0L, true))
    val invalidInputs = listOf(
      12L to -1L,
      12L to 1L,
      12L to 250_000L,
      12L to 900_000_000_000L,
      -1L to 0L,
      900_001L to 0L,
      Long.MIN_VALUE to 0L,
      Long.MAX_VALUE to 0L,
    )
    var invalid = 0L
    for (stage in listOf(SettingsUiWorkStage.VERSION_PUBLISHED,
        SettingsUiWorkStage.PANE_EFFECT_START, SettingsUiWorkStage.PANE_EFFECT_END)) {
      for ((elapsedMs, durationNs) in invalidInputs) {
        assertFalse(stage.key, samples.record(stage, elapsedMs, durationNs, false))
        invalid++
        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = 1L, invalid = invalid)
        assertStage(snapshot, "versionPublished", records = 1L, main = 1L)
        assertEmptyStages(snapshot, setOf("versionPublished"))
        val retained = snapshot.getJSONArray("samples")
        assertEquals(1, retained.length())
        assertSample(retained.getJSONObject(0), "versionPublished", 12L, 0.0, true)
      }
    }
  }

  @Test fun first128SamplesAreRetainedWhileDiscardedRecordsStillAggregate() {
    val samples = SettingsUiWorkSamples()
    repeat(128) { index ->
      val stage = if (index % 2 == 0) SettingsUiWorkStage.SHELL_COMPOSITION
          else SettingsUiWorkStage.VERSION_PUBLISHED
      val durationNs = if (index % 2 == 0) (index + 1) * 250_000L else 0L
      assertTrue(samples.record(stage, index * 3L, durationNs, onMain = index % 4 < 2))
    }
    val full = samples.snapshot()
    assertCounts(full, records = 128L)
    assertEquals(128, full.getJSONArray("samples").length())
    assertStage(full, "shellComposition", records = 64L, main = 32L, other = 32L,
        mean = 16.0, max = 31.75)
    assertStage(full, "versionPublished", records = 64L, main = 32L, other = 32L)
    assertEmptyStages(full, setOf("shellComposition", "versionPublished"))

    assertTrue(samples.record(SettingsUiWorkStage.SHELL_COMPOSITION, 800_000L, 64_000_000L, true))
    assertTrue(samples.record(SettingsUiWorkStage.VERSION_PUBLISHED, 900_000L, 0L, false))
    assertTrue(samples.record(SettingsUiWorkStage.DETAIL_MEASURE, 5L, 1_500_000L, false))
    assertFalse(samples.record(SettingsUiWorkStage.SHELL_COMPOSITION, 900_001L, 250_000L, true))
    assertFalse(samples.record(SettingsUiWorkStage.VERSION_PUBLISHED, 10L, 1L, true))

    val snapshot = samples.snapshot()
    assertCounts(snapshot, records = 131L, invalid = 2L, discarded = 3L)
    // The first 64 timed durations total 1024 ms; the discarded 64 ms span still counts.
    assertStage(snapshot, "shellComposition", records = 65L, main = 33L, other = 32L,
        mean = 1088.0 / 65.0, max = 64.0)
    assertStage(snapshot, "versionPublished", records = 65L, main = 32L, other = 33L)
    assertStage(snapshot, "detailMeasure", records = 1L, other = 1L, mean = 1.5, max = 1.5)
    assertEmptyStages(snapshot, setOf("shellComposition", "versionPublished", "detailMeasure"))
    val retained = snapshot.getJSONArray("samples")
    assertEquals(128, retained.length())
    repeat(128) { index ->
      assertSample(retained.getJSONObject(index),
          if (index % 2 == 0) "shellComposition" else "versionPublished", index * 3L,
          if (index % 2 == 0) (index + 1) * 0.25 else 0.0, index % 4 < 2)
    }
  }

  @Test fun snapshotsAreDetachedFromLaterRecordsAndReturnedJsonMutation() {
    val samples = SettingsUiWorkSamples()
    assertTrue(samples.record(SettingsUiWorkStage.SHELL_MEASURE, 12L, 250_000L, true))
    assertTrue(samples.record(SettingsUiWorkStage.PANE_EFFECT_START, 13L, 0L, false))
    val first = samples.snapshot()
    val sibling = samples.snapshot()
    assertNotSame(first, sibling)
    assertNotSame(first.getJSONArray("samples"), sibling.getJSONArray("samples"))
    repeat(2) { index ->
      assertNotSame(first.getJSONArray("samples").getJSONObject(index),
          sibling.getJSONArray("samples").getJSONObject(index))
    }
    assertNotSame(first.getJSONObject("stages"), sibling.getJSONObject("stages"))
    expectedStageKinds.keys.forEach { key ->
      assertNotSame(first.getJSONObject("stages").getJSONObject(key),
          sibling.getJSONObject("stages").getJSONObject(key))
    }

    assertTrue(samples.record(SettingsUiWorkStage.SHELL_MEASURE, 87L, 1_500_000L, false))
    assertFalse(samples.record(SettingsUiWorkStage.SHELL_MEASURE, -1L, 0L, true))
    assertCounts(first, records = 2L)
    assertStage(first, "shellMeasure", records = 1L, main = 1L, mean = 0.25, max = 0.25)
    assertStage(first, "paneEffectStart", records = 1L, other = 1L)
    assertEmptyStages(first, setOf("shellMeasure", "paneEffectStart"))
    assertEquals(2, first.getJSONArray("samples").length())
    assertSample(first.getJSONArray("samples").getJSONObject(0), "shellMeasure", 12L, 0.25, true)

    first.put("records", 99L).put("invalidRecords", 99L).put("discardedRecords", 99L)
    first.getJSONArray("samples").getJSONObject(0)
        .put("stage", "detailMeasure").put("elapsedMs", 99L).put("durationMs", 99.0).put("onMain", false)
    first.getJSONArray("samples").put(JSONObject())
    first.getJSONObject("stages").getJSONObject("shellMeasure")
        .put("records", 99L).put("mainThreadRecords", 99L).put("meanMs", 99.0).put("maxMs", 99.0)
    first.getJSONObject("stages").remove("detailComposition")

    assertCounts(sibling, records = 2L)
    assertStage(sibling, "shellMeasure", records = 1L, main = 1L, mean = 0.25, max = 0.25)
    assertStage(sibling, "paneEffectStart", records = 1L, other = 1L)
    assertEmptyStages(sibling, setOf("shellMeasure", "paneEffectStart"))
    assertEquals(2, sibling.getJSONArray("samples").length())
    assertSample(sibling.getJSONArray("samples").getJSONObject(0), "shellMeasure", 12L, 0.25, true)
    assertSample(sibling.getJSONArray("samples").getJSONObject(1), "paneEffectStart", 13L, 0.0, false)

    val latest = samples.snapshot()
    assertCounts(latest, records = 3L, invalid = 1L)
    assertStage(latest, "shellMeasure", records = 2L, main = 1L, other = 1L, mean = 0.875, max = 1.5)
    assertStage(latest, "paneEffectStart", records = 1L, other = 1L)
    assertEmptyStages(latest, setOf("shellMeasure", "paneEffectStart"))
    assertEquals(3, latest.getJSONArray("samples").length())
    assertSample(latest.getJSONArray("samples").getJSONObject(0), "shellMeasure", 12L, 0.25, true)
    assertSample(latest.getJSONArray("samples").getJSONObject(1), "paneEffectStart", 13L, 0.0, false)
    assertSample(latest.getJSONArray("samples").getJSONObject(2), "shellMeasure", 87L, 1.5, false)
  }

  @Test fun instancesHaveSeparateRetentionCountersAndStageAggregates() {
    val first = SettingsUiWorkSamples()
    val second = SettingsUiWorkSamples()
    repeat(129) { index ->
      assertTrue(first.record(SettingsUiWorkStage.SHELL_MEASURE, index.toLong(), 250_000L, true))
    }
    assertFalse(first.record(SettingsUiWorkStage.SHELL_MEASURE, -1L, 0L, false))
    val empty = second.snapshot()
    assertCounts(empty, records = 0L)
    assertEmptyStages(empty)
    assertEquals(0, empty.getJSONArray("samples").length())

    assertTrue(second.record(SettingsUiWorkStage.SHELL_MEASURE, 87L, 1_500_000L, false))
    assertTrue(second.record(SettingsUiWorkStage.VERSION_PUBLISHED, 88L, 0L, true))
    val firstSnapshot = first.snapshot()
    assertCounts(firstSnapshot, records = 129L, invalid = 1L, discarded = 1L)
    assertStage(firstSnapshot, "shellMeasure", records = 129L, main = 129L, mean = 0.25, max = 0.25)
    assertEmptyStages(firstSnapshot, setOf("shellMeasure"))
    assertEquals(128, firstSnapshot.getJSONArray("samples").length())
    repeat(128) { index ->
      assertSample(firstSnapshot.getJSONArray("samples").getJSONObject(index),
          "shellMeasure", index.toLong(), 0.25, true)
    }
    val secondSnapshot = second.snapshot()
    assertCounts(secondSnapshot, records = 2L)
    assertStage(secondSnapshot, "shellMeasure", records = 1L, other = 1L, mean = 1.5, max = 1.5)
    assertStage(secondSnapshot, "versionPublished", records = 1L, main = 1L)
    assertEmptyStages(secondSnapshot, setOf("shellMeasure", "versionPublished"))
    assertEquals(2, secondSnapshot.getJSONArray("samples").length())
    assertSample(secondSnapshot.getJSONArray("samples").getJSONObject(0), "shellMeasure", 87L, 1.5, false)
    assertSample(secondSnapshot.getJSONArray("samples").getJSONObject(1), "versionPublished", 88L, 0.0, true)
  }

  private val expectedStageKinds = linkedMapOf(
    "shellComposition" to "elapsed",
    "searchComposition" to "elapsed",
    "sidebarItemComposition" to "elapsed",
    "detailComposition" to "elapsed",
    "shellMeasure" to "elapsed",
    "searchMeasure" to "elapsed",
    "sidebarMeasure" to "elapsed",
    "detailMeasure" to "elapsed",
    "versionPublished" to "event",
    "paneEffectStart" to "event",
    "paneEffectEnd" to "event",
  )

  private fun assertCounts(
    snapshot: JSONObject,
    records: Long,
    invalid: Long = 0L,
    discarded: Long = 0L,
  ) {
    assertEquals(setOf("measurement", "records", "invalidRecords", "discardedRecords",
        "maxRecords", "samples", "stages"), snapshot.keys().asSequence().toSet())
    assertEquals("elapsed wall time; overlapping spans; not CPU time", snapshot.getString("measurement"))
    assertEquals(128, snapshot.get("maxRecords"))
    assertEquals(records, snapshot.get("records"))
    assertEquals(invalid, snapshot.get("invalidRecords"))
    assertEquals(discarded, snapshot.get("discardedRecords"))
    assertEquals(expectedStageKinds.keys, snapshot.getJSONObject("stages").keys().asSequence().toSet())
  }

  private fun assertStage(
    snapshot: JSONObject,
    key: String,
    records: Long = 0L,
    main: Long = 0L,
    other: Long = 0L,
    mean: Double? = null,
    max: Double? = null,
  ) {
    val stage = snapshot.getJSONObject("stages").getJSONObject(key)
    assertEquals(setOf("kind", "records", "mainThreadRecords", "otherThreadRecords", "meanMs", "maxMs"),
        stage.keys().asSequence().toSet())
    assertEquals(expectedStageKinds.getValue(key), stage.getString("kind"))
    assertEquals(records, stage.get("records"))
    assertEquals(main, stage.get("mainThreadRecords"))
    assertEquals(other, stage.get("otherThreadRecords"))
    if (mean == null) assertSame(JSONObject.NULL, stage.get("meanMs")) else {
      assertTrue(stage.get("meanMs") is Double)
      assertEquals(mean, stage.getDouble("meanMs"), 1e-12)
    }
    if (max == null) assertSame(JSONObject.NULL, stage.get("maxMs")) else {
      assertTrue(stage.get("maxMs") is Double)
      assertEquals(max, stage.getDouble("maxMs"), 0.0)
    }
  }

  private fun assertEmptyStages(snapshot: JSONObject, except: Set<String> = emptySet()) {
    expectedStageKinds.keys.filterNot { it in except }.forEach { assertStage(snapshot, it) }
  }

  private fun assertSample(
    sample: JSONObject,
    stage: String,
    elapsedMs: Long,
    durationMs: Double,
    onMain: Boolean,
  ) {
    assertEquals(setOf("stage", "elapsedMs", "durationMs", "onMain"), sample.keys().asSequence().toSet())
    assertEquals(stage, sample.getString("stage"))
    assertEquals(elapsedMs, sample.get("elapsedMs"))
    assertTrue(sample.get("durationMs") is Double)
    assertEquals(durationMs, sample.getDouble("durationMs"), 0.0)
    assertEquals(onMain, sample.get("onMain"))
  }
}
