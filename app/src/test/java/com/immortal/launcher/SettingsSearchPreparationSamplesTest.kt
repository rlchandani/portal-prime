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

class SettingsSearchPreparationSamplesTest {
    @Test fun emptySnapshotHasExactSchemaAndNullDurations() {
        val snapshot = SettingsSearchPreparationSamples().snapshot()

        assertEquals(32, SettingsSearchPreparationSamples.MAX_RECORDS)
        assertCounts(snapshot, records = 0L)
        assertSame(JSONObject.NULL, snapshot.get("meanMs"))
        assertSame(JSONObject.NULL, snapshot.get("maxMs"))
        assertEquals(0, snapshot.getJSONArray("samples").length())
    }

    @Test fun fractionalMillisecondsArePreservedInSamplesMeanAndMaximum() {
        val samples = SettingsSearchPreparationSamples()
        assertTrue(samples.record(elapsedMs = 12L, durationNs = 250_000L,
            entryCount = 7, onMain = true))
        assertTrue(samples.record(elapsedMs = 87L, durationNs = 1_500_000L,
            entryCount = 19, onMain = false))

        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = 2L, main = 1L, other = 1L)
        assertEquals(0.875, snapshot.getDouble("meanMs"), 0.0)
        assertEquals(1.5, snapshot.getDouble("maxMs"), 0.0)
        val retained = snapshot.getJSONArray("samples")
        assertEquals(2, retained.length())
        assertSample(retained.getJSONObject(0), 12L, 0.25, 7, true)
        assertSample(retained.getJSONObject(1), 87L, 1.5, 19, false)
    }

    @Test fun zeroDurationAndZeroMetadataAreValidAndHaveNumericAggregates() {
        val samples = SettingsSearchPreparationSamples()
        assertTrue(samples.record(elapsedMs = 0L, durationNs = 0L,
            entryCount = 0, onMain = true))

        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = 1L, main = 1L)
        assertFalse(snapshot.isNull("meanMs"))
        assertFalse(snapshot.isNull("maxMs"))
        assertEquals(0.0, snapshot.getDouble("meanMs"), 0.0)
        assertEquals(0.0, snapshot.getDouble("maxMs"), 0.0)
        assertEquals(1, snapshot.getJSONArray("samples").length())
        assertSample(snapshot.getJSONArray("samples").getJSONObject(0), 0L, 0.0, 0, true)
    }

    @Test fun inclusiveUpperBoundsAndMaximumEntryCountAreValid() {
        val samples = SettingsSearchPreparationSamples()
        assertTrue(samples.record(elapsedMs = 900_000L, durationNs = 900_000_000_000L,
            entryCount = Int.MAX_VALUE, onMain = false))

        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = 1L, other = 1L)
        assertEquals(900_000.0, snapshot.getDouble("meanMs"), 0.0)
        assertEquals(900_000.0, snapshot.getDouble("maxMs"), 0.0)
        assertEquals(1, snapshot.getJSONArray("samples").length())
        assertSample(snapshot.getJSONArray("samples").getJSONObject(0),
            900_000L, 900_000.0, Int.MAX_VALUE, false)
    }

    @Test fun invalidBoundsIncrementOnlyInvalidRecordsAndKeepEmptyDurationsNull() {
        val samples = SettingsSearchPreparationSamples()
        val invalidInputs = listOf(
            Triple(-1L, 250_000L, 7),
            Triple(900_001L, 250_000L, 7),
            Triple(12L, -1L, 7),
            Triple(12L, 900_000_000_001L, 7),
            Triple(12L, 250_000L, -1),
            Triple(12L, 250_000L, Int.MIN_VALUE),
        )
        invalidInputs.forEachIndexed { index, (elapsedMs, durationNs, entryCount) ->
            assertFalse("Invalid input $index was accepted",
                samples.record(elapsedMs, durationNs, entryCount, onMain = index % 2 == 0))
            val snapshot = samples.snapshot()
            assertCounts(snapshot, records = 0L, invalid = index.toLong() + 1L)
            assertSame(JSONObject.NULL, snapshot.get("meanMs"))
            assertSame(JSONObject.NULL, snapshot.get("maxMs"))
            assertEquals(0, snapshot.getJSONArray("samples").length())
        }

        assertTrue(samples.record(12L, 250_000L, 7, onMain = false))
        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = 1L, invalid = 6L, other = 1L)
        assertEquals(0.25, snapshot.getDouble("meanMs"), 0.0)
        assertEquals(0.25, snapshot.getDouble("maxMs"), 0.0)
        assertEquals(1, snapshot.getJSONArray("samples").length())
        assertSample(snapshot.getJSONArray("samples").getJSONObject(0), 12L, 0.25, 7, false)
    }

    @Test fun extremeLongInputsDoNotChangeExistingSamplesOrAggregates() {
        val samples = SettingsSearchPreparationSamples()
        assertTrue(samples.record(12L, 250_000L, 7, onMain = true))
        val invalidInputs = listOf(
            Long.MIN_VALUE to 250_000L,
            Long.MAX_VALUE to 250_000L,
            12L to Long.MIN_VALUE,
            12L to Long.MAX_VALUE,
        )
        invalidInputs.forEachIndexed { index, (elapsedMs, durationNs) ->
            assertFalse(samples.record(elapsedMs, durationNs, 7, onMain = index % 2 == 0))
            val snapshot = samples.snapshot()
            assertCounts(snapshot, records = 1L, invalid = index.toLong() + 1L, main = 1L)
            assertEquals(0.25, snapshot.getDouble("meanMs"), 0.0)
            assertEquals(0.25, snapshot.getDouble("maxMs"), 0.0)
            assertEquals(1, snapshot.getJSONArray("samples").length())
            assertSample(snapshot.getJSONArray("samples").getJSONObject(0), 12L, 0.25, 7, true)
        }
    }

    @Test fun onlyFirst32SamplesAreRetainedWhileLaterRecordsStillUpdateAllAggregates() {
        val samples = SettingsSearchPreparationSamples()
        repeat(32) { index ->
            assertTrue(samples.record(elapsedMs = index * 3L, durationNs = (index + 1) * 250_000L,
                entryCount = 100 + index, onMain = index % 2 == 0))
        }
        val full = samples.snapshot()
        assertCounts(full, records = 32L, main = 16L, other = 16L)
        assertEquals(32, full.getJSONArray("samples").length())
        assertEquals(4.125, full.getDouble("meanMs"), 0.0)
        assertEquals(8.0, full.getDouble("maxMs"), 0.0)

        assertTrue(samples.record(800_000L, 20_000_000L, Int.MAX_VALUE, onMain = true))
        assertTrue(samples.record(900_000L, 0L, 0, onMain = false))
        assertFalse(samples.record(900_001L, 250_000L, 7, onMain = false))
        val snapshot = samples.snapshot()
        assertCounts(snapshot, records = 34L, invalid = 1L, discarded = 2L, main = 17L, other = 17L)
        // The retained durations total 132 ms; both later valid durations count in the mean.
        assertEquals(152.0 / 34.0, snapshot.getDouble("meanMs"), 1e-12)
        assertEquals(20.0, snapshot.getDouble("maxMs"), 0.0)
        val retained = snapshot.getJSONArray("samples")
        assertEquals(32, retained.length())
        repeat(32) { index ->
            assertSample(retained.getJSONObject(index), index * 3L,
                (index + 1) * 0.25, 100 + index, index % 2 == 0)
        }
    }

    @Test fun snapshotsAreIndependentOfLaterRecordsAndReturnedJsonMutation() {
        val samples = SettingsSearchPreparationSamples()
        assertTrue(samples.record(12L, 250_000L, 7, onMain = true))
        val first = samples.snapshot()
        val sibling = samples.snapshot()
        assertNotSame(first, sibling)
        assertNotSame(first.getJSONArray("samples"), sibling.getJSONArray("samples"))
        assertNotSame(first.getJSONArray("samples").getJSONObject(0),
            sibling.getJSONArray("samples").getJSONObject(0))

        assertTrue(samples.record(87L, 1_500_000L, 19, onMain = false))
        assertCounts(first, records = 1L, main = 1L)
        assertEquals(1, first.getJSONArray("samples").length())
        assertEquals(0.25, first.getDouble("meanMs"), 0.0)
        assertEquals(0.25, first.getDouble("maxMs"), 0.0)

        first.put("records", 99L).put("meanMs", 99.0).put("maxMs", 99.0)
        first.getJSONArray("samples").getJSONObject(0)
            .put("entryCount", -1).put("durationMs", 99.0).put("onMain", false)
        first.getJSONArray("samples").put(JSONObject().put("unexpected", true))

        assertCounts(sibling, records = 1L, main = 1L)
        assertEquals(0.25, sibling.getDouble("meanMs"), 0.0)
        assertEquals(0.25, sibling.getDouble("maxMs"), 0.0)
        assertEquals(1, sibling.getJSONArray("samples").length())
        assertSample(sibling.getJSONArray("samples").getJSONObject(0), 12L, 0.25, 7, true)

        val latest = samples.snapshot()
        assertCounts(latest, records = 2L, main = 1L, other = 1L)
        assertEquals(0.875, latest.getDouble("meanMs"), 0.0)
        assertEquals(1.5, latest.getDouble("maxMs"), 0.0)
        assertEquals(2, latest.getJSONArray("samples").length())
        assertSample(latest.getJSONArray("samples").getJSONObject(0), 12L, 0.25, 7, true)
        assertSample(latest.getJSONArray("samples").getJSONObject(1), 87L, 1.5, 19, false)
    }

    @Test fun instancesKeepSeparateValidInvalidThreadAndDurationState() {
        val first = SettingsSearchPreparationSamples()
        val second = SettingsSearchPreparationSamples()
        assertTrue(first.record(12L, 250_000L, 7, onMain = true))
        assertFalse(first.record(12L, 250_000L, -1, onMain = false))
        val empty = second.snapshot()
        assertCounts(empty, records = 0L)
        assertSame(JSONObject.NULL, empty.get("meanMs"))
        assertSame(JSONObject.NULL, empty.get("maxMs"))
        assertEquals(0, empty.getJSONArray("samples").length())

        assertTrue(second.record(87L, 1_500_000L, 19, onMain = false))
        val firstSnapshot = first.snapshot()
        assertCounts(firstSnapshot, records = 1L, invalid = 1L, main = 1L)
        assertEquals(0.25, firstSnapshot.getDouble("meanMs"), 0.0)
        assertEquals(0.25, firstSnapshot.getDouble("maxMs"), 0.0)
        assertEquals(1, firstSnapshot.getJSONArray("samples").length())
        assertSample(firstSnapshot.getJSONArray("samples").getJSONObject(0), 12L, 0.25, 7, true)
        val secondSnapshot = second.snapshot()
        assertCounts(secondSnapshot, records = 1L, other = 1L)
        assertEquals(1.5, secondSnapshot.getDouble("meanMs"), 0.0)
        assertEquals(1.5, secondSnapshot.getDouble("maxMs"), 0.0)
        assertEquals(1, secondSnapshot.getJSONArray("samples").length())
        assertSample(secondSnapshot.getJSONArray("samples").getJSONObject(0), 87L, 1.5, 19, false)
    }

    private fun assertCounts(
        snapshot: JSONObject,
        records: Long,
        invalid: Long = 0L,
        discarded: Long = 0L,
        main: Long = 0L,
        other: Long = 0L,
    ) {
        assertEquals(setOf("measurement", "records", "invalidRecords", "discardedRecords",
            "maxRecords", "mainThreadRecords", "otherThreadRecords", "meanMs", "maxMs", "samples"),
            snapshot.keys().asSequence().toSet())
        assertEquals("elapsed wall time; not CPU time", snapshot.getString("measurement"))
        assertEquals(32, snapshot.get("maxRecords"))
        // Object getters also enforce Long counters rather than accepting coerced numbers.
        assertEquals(records, snapshot.get("records"))
        assertEquals(invalid, snapshot.get("invalidRecords"))
        assertEquals(discarded, snapshot.get("discardedRecords"))
        assertEquals(main, snapshot.get("mainThreadRecords"))
        assertEquals(other, snapshot.get("otherThreadRecords"))
    }

    private fun assertSample(
        sample: JSONObject,
        elapsedMs: Long,
        durationMs: Double,
        entryCount: Int,
        onMain: Boolean,
    ) {
        assertEquals(setOf("elapsedMs", "durationMs", "entryCount", "onMain"),
            sample.keys().asSequence().toSet())
        assertEquals(elapsedMs, sample.get("elapsedMs"))
        assertTrue(sample.get("durationMs") is Double)
        assertEquals(durationMs, sample.getDouble("durationMs"), 0.0)
        assertEquals(entryCount, sample.get("entryCount"))
        assertEquals(onMain, sample.get("onMain"))
    }
}
