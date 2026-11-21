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
  // One bucket per cache tier (EngineScaledCache.TIERS) plus a device's own smaller maximum.
  private val tierKeys = EngineScaledCache.TIERS.map { it.toString() } + "custom-maximum"
  private val results = LongArray(EngineImageResolutionResult.entries.size)
  private val requestedTiers = LongArray(tierKeys.size)
  private val deliveredTiers = LongArray(tierKeys.size)
  private val deliveryPairs = Array(tierKeys.size) { LongArray(tierKeys.size) }
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
    requestedTiers[requestedTier]++
    if (deliveredTier != null) {
      deliveredTiers[deliveredTier]++
      deliveryPairs[requestedTier][deliveredTier]++
    }
    return true
  }

  /** Index into [tierKeys]: a cache tier, else the device maximum, else null (invalid). */
  private fun tier(edge: Int, maximum: Int): Int? {
    if (edge !in 1..maximum) return null
    val index = EngineScaledCache.TIERS.indexOf(edge)
    return when {
      index >= 0 -> index
      edge == maximum -> tierKeys.size - 1
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
        tierKeys.forEachIndexed { i, key -> put(key, tierSnapshot(deliveryPairs[i])) }
      })

  private fun tierSnapshot(counts: LongArray) = JSONObject().apply {
    tierKeys.forEachIndexed { i, key -> put(key, counts[i]) }
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
