/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class EngineImageResolutionSamplesTest {
  @Test fun finiteResultsAndTiersAreCountedWithoutPerRequestRecords() {
    val samples = EngineImageResolutionSamples()
    assertTrue(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, 1000, 1200))
    assertTrue(samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 1000, 2400))
    assertTrue(samples.record(EngineImageResolutionResult.ORIGINAL_FALLBACK, 1, 0))
    assertTrue(samples.record(EngineImageResolutionResult.MISSING_ORIGINAL, 1800, 0))
    val snapshot = samples.snapshot()
    assertEquals(4L, snapshot.getLong("records"))
    val results = snapshot.getJSONObject("results")
    for (key in listOf("missing-original", "requested-tier-ready", "larger-tier-ready", "original-fallback"))
      assertEquals(1L, results.getLong(key))
    val requested = snapshot.getJSONObject("requestedTiers")
    assertEquals(1L, requested.getLong("600")); assertEquals(2L, requested.getLong("1200"))
    assertEquals(1L, requested.getLong("1800"))
    val delivered = snapshot.getJSONObject("deliveredTiers")
    assertEquals(1L, delivered.getLong("1200")); assertEquals(1L, delivered.getLong("2400"))
    assertFalse(snapshot.has("samples")); assertFalse(snapshot.has("events"))
  }

  @Test fun extremeRequestedIntegersCanonicalizeLikeProductionAndCustomMaximaUseOneLabel() {
    val samples = EngineImageResolutionSamples()
    assertTrue(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, Int.MIN_VALUE, 2400))
    assertTrue(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, Int.MAX_VALUE, 2400))
    for (maximum in 1..2399) {
      assertTrue(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, Int.MAX_VALUE, maximum, maximum))
    }
    val snapshot = samples.snapshot()
    val tiers = snapshot.getJSONObject("deliveredTiers")
    assertEquals(setOf("600", "1200", "1800", "2400", "custom-maximum"), tiers.keys().asSequence().toSet())
    assertEquals(2396L, tiers.getLong("custom-maximum"))
    assertEquals(2L, tiers.getLong("2400")); assertEquals(1L, tiers.getLong("600"))
    assertEquals(1L, tiers.getLong("1200")); assertEquals(1L, tiers.getLong("1800"))
  }

  @Test fun untrustedDeliveredIntegersAndInconsistentBranchesAreRejected() {
    val samples = EngineImageResolutionSamples()
    for (edge in listOf(Int.MIN_VALUE, -1, 0, 1, 601, 1199, 2401, Int.MAX_VALUE))
      assertFalse(samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 600, edge))
    assertFalse(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, 600, 1200))
    assertFalse(samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 1200, 600))
    assertFalse(samples.record(EngineImageResolutionResult.ORIGINAL_FALLBACK, 600, 1200))
    assertFalse(samples.record(EngineImageResolutionResult.MISSING_ORIGINAL, 600, 600))
    assertFalse(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, 600, 600, 0))
    assertFalse(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, 600, 600, 2401))
    assertEquals(0L, samples.snapshot().getLong("records"))
    assertEquals(14L, samples.snapshot().getLong("invalidRecords"))
  }

  @Test fun snapshotIsDetachedAndContainsOnlyFixedNumericCountsAndScopeLabels() {
    val samples = EngineImageResolutionSamples()
    samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 601, 1800)
    val first = samples.snapshot()
    first.getJSONObject("results").put("original-fallback", 999L)
    first.getJSONObject("requestedTiers").put("1200", 999L)
    samples.record(EngineImageResolutionResult.ORIGINAL_FALLBACK, 600, 0)
    val fresh = samples.snapshot()
    assertEquals(2L, fresh.getLong("records"))
    assertEquals(1L, first.getLong("records"))
    assertEquals(1L, fresh.getJSONObject("results").getLong("original-fallback"))
    assertEquals(1L, fresh.getJSONObject("requestedTiers").getLong("1200"))
    val exported = fresh.toString()
    for (forbidden in listOf("file://", "https://", "synthetic-original", "source", "path", "oversized"))
      assertFalse(forbidden, exported.contains(forbidden))
    assertFalse(fresh.getBoolean("pixelDimensionsMeasured"))
    assertFalse(fresh.getBoolean("presentationMeasured"))
  }

  @Test fun inactiveProbeCallsResolverOnceWithoutSelectingASinkLater() {
    var selections = 0; var resolutions = 0; var records = 0
    var active = false
    val probe = EngineImageResolutionProbe {
      selections++; if (active) EngineImageResolutionMeasurement { _, _, _, _ -> records++ } else null
    }
    val result = Any()
    assertSame(result, probe.resolve { measurement ->
      resolutions++; active = true; assertNull(measurement); result
    })
    assertEquals(1, selections); assertEquals(1, resolutions); assertEquals(0, records)
  }

  @Test fun probeSelectsBeforeResolutionAndPreservesOriginalWorkFailure() {
    val order = ArrayList<String>(); val original = IllegalStateException("synthetic work")
    val probe = EngineImageResolutionProbe {
      order.add("selection"); throw AssertionError("synthetic selection")
    }
    val failure = runCatching { probe.resolve { measurement ->
      order.add("resolution"); assertNull(measurement); throw original
    } }.exceptionOrNull()
    assertSame(original, failure); assertEquals(listOf("selection", "resolution"), order)
  }

  @Test fun fixedDeliveryMatrixKeepsSmallRequestsPairedWithTheirActualLargerTier() {
    val samples = EngineImageResolutionSamples()
    repeat(2) { assertTrue(samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 600, 2400)) }
    assertTrue(samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 1000, 1800))
    assertTrue(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, 2400, 2400))
    assertTrue(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, Int.MAX_VALUE, 2000, 2000))
    val pairs = samples.snapshot().getJSONObject("deliveryPairs")
    val keys = setOf("600", "1200", "1800", "2400", "custom-maximum")
    assertEquals(keys, pairs.keys().asSequence().toSet())
    var total = 0L
    for (requested in keys) {
      val row = pairs.getJSONObject(requested)
      assertEquals(keys, row.keys().asSequence().toSet())
      for (delivered in keys) total += row.getLong(delivered)
    }
    assertEquals(5L, total)
    assertEquals(2L, pairs.getJSONObject("600").getLong("2400"))
    assertEquals(1L, pairs.getJSONObject("1200").getLong("1800"))
    assertEquals(1L, pairs.getJSONObject("2400").getLong("2400"))
    assertEquals(1L, pairs.getJSONObject("custom-maximum").getLong("custom-maximum"))
    assertEquals(0L, pairs.getJSONObject("1200").getLong("2400"))
  }

  @Test fun deliveryPairsExcludeFallbacksAndInvalidResultsAndSnapshotsAreDetached() {
    val samples = EngineImageResolutionSamples()
    assertTrue(samples.record(EngineImageResolutionResult.LARGER_TIER_READY, 1000, 2400))
    val first = samples.snapshot()
    first.getJSONObject("deliveryPairs").getJSONObject("1200").put("2400", 999L)
    assertTrue(samples.record(EngineImageResolutionResult.ORIGINAL_FALLBACK, 600, 0))
    assertTrue(samples.record(EngineImageResolutionResult.MISSING_ORIGINAL, 1200, 0))
    assertFalse(samples.record(EngineImageResolutionResult.REQUESTED_TIER_READY, 600, 2400))
    val fresh = samples.snapshot()
    assertEquals(3L, fresh.getLong("records"))
    assertEquals(1L, fresh.getLong("invalidRecords"))
    val pairs = fresh.getJSONObject("deliveryPairs")
    assertEquals(1L, pairs.getJSONObject("1200").getLong("2400"))
    assertEquals(0L, pairs.getJSONObject("600").getLong("2400"))
    assertEquals(0L, pairs.getJSONObject("1200").getLong("1200"))
    assertEquals(5, pairs.length())
  }
}
