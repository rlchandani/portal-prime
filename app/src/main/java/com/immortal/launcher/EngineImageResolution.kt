/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** Code-defined cache-path outcomes; no source identity or pixel dimensions. */
internal enum class EngineImageResolutionResult(val key: String) {
  MISSING_ORIGINAL("missing-original"), REQUESTED_TIER_READY("requested-tier-ready"),
  LARGER_TIER_READY("larger-tier-ready"), ORIGINAL_FALLBACK("original-fallback"),
  BOUNDED_LOAD_PENDING("bounded-load-pending")
}

internal fun interface EngineImageResolutionMeasurement {
  fun record(result: EngineImageResolutionResult, requestedEdge: Int, deliveredEdge: Int, maximum: Int)
}

/** Primitive aggregate totals only. Caller serializes access; JSON is built on explicit reads. */
internal class EngineImageResolutionSamples {
  private enum class Tier(val key: String) {
    EDGE_600("600"), EDGE_1200("1200"), EDGE_1800("1800"), EDGE_2400("2400"),
    CUSTOM_MAXIMUM("custom-maximum")
  }
  private val results = LongArray(EngineImageResolutionResult.entries.size)
  private val requestedTiers = LongArray(Tier.entries.size)
  private val deliveredTiers = LongArray(Tier.entries.size)
  private val deliveryPairs = Array(Tier.entries.size) { LongArray(Tier.entries.size) }
  private var records = 0L
  private var invalidRecords = 0L

  fun record(result: EngineImageResolutionResult, requestedEdge: Int, deliveredEdge: Int,
      maximum: Int = EngineScaledCache.TARGET_EDGE): Boolean {
    if (maximum !in 1..EngineScaledCache.TARGET_EDGE) { invalidRecords++; return false }
    // The JS argument is untrusted. Reuse the production rounding/cap; never make numeric keys.
    val requested = EngineScaledCache.derivativeEdge(requestedEdge, maximum)
    val requestedTier = tier(requested, maximum)
    val deliveredTier = tier(deliveredEdge, maximum)
    val valid = when (result) {
      EngineImageResolutionResult.MISSING_ORIGINAL, EngineImageResolutionResult.ORIGINAL_FALLBACK,
      EngineImageResolutionResult.BOUNDED_LOAD_PENDING ->
        deliveredEdge == 0
      EngineImageResolutionResult.REQUESTED_TIER_READY -> deliveredTier != null && deliveredEdge == requested
      EngineImageResolutionResult.LARGER_TIER_READY -> deliveredTier != null && deliveredEdge > requested
    }
    if (!valid || requestedTier == null) { invalidRecords++; return false }
    records++
    results[result.ordinal]++
    requestedTiers[requestedTier.ordinal]++
    if (deliveredTier != null) {
      deliveredTiers[deliveredTier.ordinal]++
      deliveryPairs[requestedTier.ordinal][deliveredTier.ordinal]++
    }
    return true
  }

  private fun tier(edge: Int, maximum: Int): Tier? {
    if (edge !in 1..maximum) return null
    return when (edge) {
      600 -> Tier.EDGE_600
      1200 -> Tier.EDGE_1200
      1800 -> Tier.EDGE_1800
      2400 -> Tier.EDGE_2400
      maximum -> Tier.CUSTOM_MAXIMUM
      else -> null
    }
  }

  fun snapshot(): JSONObject = JSONObject()
      .put("measurement", "native local image bridge resolution totals; derivative cache tiers")
      .put("pixelDimensionsMeasured", false).put("presentationMeasured", false)
      .put("activeStyleAttributed", false)
      .put("records", records).put("invalidRecords", invalidRecords)
      .put("results", JSONObject().apply {
        for (result in EngineImageResolutionResult.entries) put(result.key, results[result.ordinal])
      }).put("requestedTiers", tierSnapshot(requestedTiers)).put("deliveredTiers", tierSnapshot(deliveredTiers))
      .put("deliveryPairs", JSONObject().apply {
        for (tier in Tier.entries) put(tier.key, tierSnapshot(deliveryPairs[tier.ordinal]))
      })

  private fun tierSnapshot(counts: LongArray) = JSONObject().apply {
    for (tier in Tier.entries) put(tier.key, counts[tier.ordinal])
  }
}

/** Select once before resolution. Neither capture failure nor replacement may change resolution. */
internal class EngineImageResolutionProbe(
    @PublishedApi internal val currentMeasurement: () -> EngineImageResolutionMeasurement?,
) {
  // Inlining keeps the caller's resolver closure off the inactive bridge allocation path.
  inline fun <T> resolve(block: (EngineImageResolutionMeasurement?) -> T): T {
    val measurement = runCatching { currentMeasurement() }.getOrNull()
    return block(measurement)
  }
}
