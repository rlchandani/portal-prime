/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal enum class SettingsPaneWorkId(val key: String) {
  APPEARANCE("appearance"),
  TOUCH("touch"),
  WALLPAPER("wallpaper"),
  QUICKBAR("quickbar"),
  OTHER("other"),
}

/**
 * Pure, bounded primitive diagnostics; caller serializes access.
 * Durations are inclusive elapsed wall time: nested spans are not additive or CPU time.
 */
internal class SettingsPaneWorkSamples {
  private class Sample(
    val paneIndex: Int,
    val active: Boolean,
    val elapsedMs: Long,
    val durationMs: Double,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val onMain: Boolean,
  )

  private class PaneTotals {
    var records = 0L
    var mainThreadRecords = 0L
    var otherThreadRecords = 0L
    var totalDurationMs = 0.0
    var maxDurationMs = 0.0
  }

  private val samples = ArrayList<Sample>(MAX_RECORDS)
  private val paneTotals = Array(SettingsPaneWorkId.entries.size * 2) { PaneTotals() }
  private var records = 0L
  private var invalidRecords = 0L
  private var discardedRecords = 0L

  fun record(
    pane: SettingsPaneWorkId,
    active: Boolean,
    elapsedMs: Long,
    durationNs: Long,
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int,
    onMain: Boolean,
  ): Boolean {
    if (elapsedMs !in 0L..900_000L || durationNs !in 0L..900_000_000_000L ||
        minWidth < 0 || maxWidth < minWidth || minHeight < 0 || maxHeight < minHeight) {
      invalidRecords++
      return false
    }

    val durationMs = durationNs / 1_000_000.0
    val totals = paneTotals[pane.ordinal * 2 + (if (active) 1 else 0)]
    records++
    totals.records++
    if (onMain) totals.mainThreadRecords++ else totals.otherThreadRecords++
    // Double millisecond totals avoid overflow from summing valid nanosecond durations.
    totals.totalDurationMs += durationMs
    totals.maxDurationMs = maxOf(totals.maxDurationMs, durationMs)
    if (samples.size < MAX_RECORDS) {
      samples.add(Sample(pane.ordinal, active, elapsedMs, durationMs,
          minWidth, maxWidth, minHeight, maxHeight, onMain))
    } else {
      discardedRecords++
    }
    return true
  }

  fun snapshot(): JSONObject {
    val retained = JSONArray()
    for (sample in samples) {
      retained.put(JSONObject()
          .put("pane", SettingsPaneWorkId.entries[sample.paneIndex].key)
          .put("active", sample.active)
          .put("elapsedMs", sample.elapsedMs).put("durationMs", sample.durationMs)
          .put("minWidth", sample.minWidth).put("maxWidth", sample.maxWidth)
          .put("minHeight", sample.minHeight).put("maxHeight", sample.maxHeight)
          .put("onMain", sample.onMain))
    }
    val panes = JSONObject()
    for (pane in SettingsPaneWorkId.entries) {
      panes.put(pane.key, JSONObject()
          .put("active", totalsSnapshot(paneTotals[pane.ordinal * 2 + 1]))
          .put("inactive", totalsSnapshot(paneTotals[pane.ordinal * 2])))
    }
    return JSONObject()
        .put("measurement", "inclusive elapsed wall time; nested spans are not additive; not CPU time")
        .put("records", records).put("invalidRecords", invalidRecords)
        .put("discardedRecords", discardedRecords).put("maxRecords", MAX_RECORDS)
        .put("samples", retained).put("panes", panes)
  }

  private fun totalsSnapshot(totals: PaneTotals): JSONObject = JSONObject()
      .put("records", totals.records)
      .put("mainThreadRecords", totals.mainThreadRecords)
      .put("otherThreadRecords", totals.otherThreadRecords)
      .put("totalMs", totals.totalDurationMs)
      .put("meanMs", if (totals.records != 0L) totals.totalDurationMs / totals.records else JSONObject.NULL)
      .put("maxMs", if (totals.records != 0L) totals.maxDurationMs else JSONObject.NULL)

  companion object { const val MAX_RECORDS = 512 }
}
