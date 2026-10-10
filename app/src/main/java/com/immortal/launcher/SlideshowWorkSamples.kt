/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal enum class SlideshowWorkStage(val key: String, val event: Boolean = false) {
  METADATA_PREPARATION("metadataPreparation"),
  WEB_VIEW_ADMISSION("webViewAdmission"),
  ENGINE_PAGE_FINISHED("enginePageFinished", true),
  ENGINE_RETIRED("engineRetired", true),
  CONTROLLER_CONSTRUCTION("controllerConstruction"),
  VIEW_BUILD("viewBuild"),
  CONTROLLER_START("controllerStart"),
  WEB_VIEW_CONSTRUCTION("webViewConstruction"),
}

/**
 * Pure, bounded primitive diagnostics; caller serializes access and supplies thread attribution.
 * Retains no source data, view references or failures. Discarded samples still contribute totals.
 */
internal class SlideshowWorkSamples {
  private class Sample(
    val stageIndex: Int,
    val elapsedMs: Long,
    val durationMs: Double,
    val onMain: Boolean,
  )

  private class StageTotals {
    var records = 0L
    var mainThreadRecords = 0L
    var otherThreadRecords = 0L
    var totalDurationMs = 0.0
    var maxDurationMs = 0.0
    var mainThreadDurationMs = 0.0
    var mainThreadMaxMs = 0.0
    var otherThreadDurationMs = 0.0
    var otherThreadMaxMs = 0.0
  }

  private val samples = ArrayList<Sample>(MAX_RECORDS)
  private val stageTotals = Array(SlideshowWorkStage.entries.size) { StageTotals() }
  private var records = 0L
  private var invalidRecords = 0L
  private var discardedRecords = 0L

  fun record(stage: SlideshowWorkStage, elapsedMs: Long, durationNs: Long, onMain: Boolean): Boolean {
    if (elapsedMs !in 0L..900_000L || durationNs !in 0L..900_000_000_000L ||
        (stage.event && durationNs != 0L)) {
      invalidRecords++
      return false
    }

    val durationMs = durationNs / 1_000_000.0
    val totals = stageTotals[stage.ordinal]
    records++
    totals.records++
    if (onMain) totals.mainThreadRecords++ else totals.otherThreadRecords++
    if (!stage.event) {
      // Convert before summing: repeated valid nanosecond durations could overflow a Long.
      totals.totalDurationMs += durationMs
      totals.maxDurationMs = maxOf(totals.maxDurationMs, durationMs)
      if (onMain) {
        totals.mainThreadDurationMs += durationMs
        totals.mainThreadMaxMs = maxOf(totals.mainThreadMaxMs, durationMs)
      } else {
        totals.otherThreadDurationMs += durationMs
        totals.otherThreadMaxMs = maxOf(totals.otherThreadMaxMs, durationMs)
      }
    }
    if (samples.size < MAX_RECORDS) {
      samples.add(Sample(stage.ordinal, elapsedMs, durationMs, onMain))
    } else {
      discardedRecords++
    }
    return true
  }

  fun snapshot(): JSONObject {
    val retained = JSONArray()
    for (sample in samples) {
      retained.put(JSONObject()
          .put("stage", SlideshowWorkStage.entries[sample.stageIndex].key)
          .put("elapsedMs", sample.elapsedMs).put("durationMs", sample.durationMs)
          .put("onMain", sample.onMain))
    }
    val stages = JSONObject()
    for (stage in SlideshowWorkStage.entries) {
      val totals = stageTotals[stage.ordinal]
      val timed = !stage.event && totals.records != 0L
      val timedMain = !stage.event && totals.mainThreadRecords != 0L
      val timedOther = !stage.event && totals.otherThreadRecords != 0L
      stages.put(stage.key, JSONObject()
          .put("kind", if (stage.event) "event" else "elapsed")
          .put("records", totals.records)
          .put("mainThreadRecords", totals.mainThreadRecords)
          .put("otherThreadRecords", totals.otherThreadRecords)
          .put("totalMs", if (timed) totals.totalDurationMs else JSONObject.NULL)
          .put("meanMs", if (timed) totals.totalDurationMs / totals.records else JSONObject.NULL)
          .put("maxMs", if (timed) totals.maxDurationMs else JSONObject.NULL)
          .put("mainThreadTotalMs", if (timedMain) totals.mainThreadDurationMs else JSONObject.NULL)
          .put("mainThreadMeanMs", if (timedMain) totals.mainThreadDurationMs / totals.mainThreadRecords else JSONObject.NULL)
          .put("mainThreadMaxMs", if (timedMain) totals.mainThreadMaxMs else JSONObject.NULL)
          .put("otherThreadTotalMs", if (timedOther) totals.otherThreadDurationMs else JSONObject.NULL)
          .put("otherThreadMeanMs", if (timedOther) totals.otherThreadDurationMs / totals.otherThreadRecords else JSONObject.NULL)
          .put("otherThreadMaxMs", if (timedOther) totals.otherThreadMaxMs else JSONObject.NULL))
    }
    return JSONObject()
        .put("measurement", "elapsed wall time; overlapping spans; not CPU time")
        .put("records", records).put("invalidRecords", invalidRecords)
        .put("discardedRecords", discardedRecords).put("maxRecords", MAX_RECORDS)
        .put("samples", retained).put("stages", stages)
  }

  companion object { const val MAX_RECORDS = 128 }
}
