/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Caller serializes access; only bounded primitive preparation metadata is retained. */
internal class SettingsSearchPreparationSamples {
  private class Sample(
    val elapsedMs: Long,
    val durationMs: Double,
    val entryCount: Int,
    val onMain: Boolean,
  )

  private val samples = ArrayList<Sample>(MAX_RECORDS)
  private var records = 0L
  private var invalidRecords = 0L
  private var discardedRecords = 0L
  private var mainThreadRecords = 0L
  private var otherThreadRecords = 0L
  private var totalDurationMs = 0.0
  private var maxDurationMs = 0.0

  fun record(elapsedMs: Long, durationNs: Long, entryCount: Int, onMain: Boolean): Boolean {
    if (elapsedMs !in 0L..900_000L || durationNs !in 0L..900_000_000_000L || entryCount < 0) {
      invalidRecords++
      return false
    }

    val durationMs = durationNs / 1_000_000.0
    records++
    // Keep the total as Double so repeated valid durations cannot overflow a Long sum.
    totalDurationMs += durationMs
    maxDurationMs = maxOf(maxDurationMs, durationMs)
    if (onMain) mainThreadRecords++ else otherThreadRecords++
    if (samples.size < MAX_RECORDS) {
      samples.add(Sample(elapsedMs, durationMs, entryCount, onMain))
    } else {
      discardedRecords++
    }
    return true
  }

  fun snapshot(): JSONObject {
    val retained = JSONArray()
    for (sample in samples) {
      retained.put(JSONObject()
          .put("elapsedMs", sample.elapsedMs).put("durationMs", sample.durationMs)
          .put("entryCount", sample.entryCount).put("onMain", sample.onMain))
    }
    return JSONObject()
        .put("measurement", "elapsed wall time; not CPU time")
        .put("records", records).put("invalidRecords", invalidRecords)
        .put("discardedRecords", discardedRecords).put("maxRecords", MAX_RECORDS)
        .put("mainThreadRecords", mainThreadRecords).put("otherThreadRecords", otherThreadRecords)
        .put("meanMs", if (records == 0L) JSONObject.NULL else totalDurationMs / records)
        .put("maxMs", if (records == 0L) JSONObject.NULL else maxDurationMs)
        .put("samples", retained)
  }

  companion object { const val MAX_RECORDS = 32 }
}
