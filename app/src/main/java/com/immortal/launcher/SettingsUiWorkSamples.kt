/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal enum class SettingsUiWorkStage(val key: String, val event: Boolean = false) {
  SHELL_COMPOSITION("shellComposition"),
  SEARCH_COMPOSITION("searchComposition"),
  SIDEBAR_ITEM_COMPOSITION("sidebarItemComposition"),
  DETAIL_COMPOSITION("detailComposition"),
  SHELL_MEASURE("shellMeasure"),
  SEARCH_MEASURE("searchMeasure"),
  SIDEBAR_MEASURE("sidebarMeasure"),
  DETAIL_MEASURE("detailMeasure"),
  VERSION_PUBLISHED("versionPublished", true),
  PANE_EFFECT_START("paneEffectStart", true),
  PANE_EFFECT_END("paneEffectEnd", true),
}

/** Pure, bounded primitive diagnostics; caller serializes access. */
internal class SettingsUiWorkSamples {
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
  }

  private val samples = ArrayList<Sample>(MAX_RECORDS)
  private val stageTotals = Array(SettingsUiWorkStage.entries.size) { StageTotals() }
  private var records = 0L
  private var invalidRecords = 0L
  private var discardedRecords = 0L

  fun record(stage: SettingsUiWorkStage, elapsedMs: Long, durationNs: Long, onMain: Boolean): Boolean {
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
      // Double totals avoid overflow from repeatedly summing valid nanosecond durations.
      totals.totalDurationMs += durationMs
      totals.maxDurationMs = maxOf(totals.maxDurationMs, durationMs)
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
          .put("stage", SettingsUiWorkStage.entries[sample.stageIndex].key)
          .put("elapsedMs", sample.elapsedMs).put("durationMs", sample.durationMs)
          .put("onMain", sample.onMain))
    }
    val stages = JSONObject()
    for (stage in SettingsUiWorkStage.entries) {
      val totals = stageTotals[stage.ordinal]
      val hasDurations = !stage.event && totals.records != 0L
      stages.put(stage.key, JSONObject()
          .put("kind", if (stage.event) "event" else "elapsed")
          .put("records", totals.records)
          .put("mainThreadRecords", totals.mainThreadRecords)
          .put("otherThreadRecords", totals.otherThreadRecords)
          .put("meanMs", if (hasDurations) totals.totalDurationMs / totals.records else JSONObject.NULL)
          .put("maxMs", if (hasDurations) totals.maxDurationMs else JSONObject.NULL))
    }
    return JSONObject()
        .put("measurement", "elapsed wall time; overlapping spans; not CPU time")
        .put("records", records).put("invalidRecords", invalidRecords)
        .put("discardedRecords", discardedRecords).put("maxRecords", MAX_RECORDS)
        .put("samples", retained).put("stages", stages)
  }

  companion object { const val MAX_RECORDS = 128 }
}
