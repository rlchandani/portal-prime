/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Only code-defined identities may cross the diagnostics boundary. */
internal enum class SlideshowStyle(val wireId: String) {
  KEN_BURNS("kenBurns"), DIPTYCH("diptych"), FRAMED("framed"), GALLERY_WALL("galleryWall"),
  PUSH("push"), MEMORIES("memories"), PANORAMA("panorama"), POLAROID("polaroid"),
  MOSAIC("mosaic"), FILMSTRIP("filmstrip"), CINEMA("cinema"), HERO_SIDEBAR("heroSidebar");

  companion object {
    fun fromWire(value: String): SlideshowStyle? =
        if (value.length > 32) null else entries.firstOrNull { it.wireId == value }
  }
}

internal enum class SlideshowStyleHost(val key: String) {
  PREVIEW("PhotoFramePreviewActivity"), DREAM("PhotoDreamService"), UNKNOWN("unknownHost")
}

internal fun interface SlideshowStyleReport { fun accept() }

/** Pure, bounded promotion observations. Caller serializes access. */
internal class SlideshowStyleSamples {
  private data class Event(val host: SlideshowStyleHost, val style: SlideshowStyle,
      val receivedElapsedMs: Long, val acceptedElapsedMs: Long)
  private val events = ArrayList<Event>(MAX_RECORDS)
  private val counts = Array(SlideshowStyle.entries.size) { LongArray(SlideshowStyleHost.entries.size) }
  private var records = 0L
  private var invalidRecords = 0L
  private var discardedRecords = 0L

  fun record(host: SlideshowStyleHost, style: SlideshowStyle, receivedElapsedMs: Long,
      acceptedElapsedMs: Long): Boolean {
    if (receivedElapsedMs !in 0L..900_000L || acceptedElapsedMs !in receivedElapsedMs..900_000L) {
      invalidRecords++
      return false
    }
    records++
    counts[style.ordinal][host.ordinal]++
    if (events.size < MAX_RECORDS) events.add(Event(host, style, receivedElapsedMs, acceptedElapsedMs))
    else discardedRecords++
    return true
  }

  fun snapshot(): JSONObject = JSONObject()
      .put("measurement", "scheduler promotion bridge receipt and guarded Main acceptance; not presentation or vsync")
      .put("presentationTimeMeasured", false).put("photoAdvancementMeasured", false)
      .put("styleBeforeFirstReportKnown", false)
      .put("records", records).put("invalidRecords", invalidRecords)
      .put("discardedRecords", discardedRecords).put("maxRecords", MAX_RECORDS)
      .put("events", JSONArray().apply {
        for (event in events) put(JSONObject().put("host", event.host.key).put("style", event.style.wireId)
            .put("receivedElapsedMs", event.receivedElapsedMs).put("acceptedElapsedMs", event.acceptedElapsedMs)
            .put("mainAdmissionDelayMs", event.acceptedElapsedMs - event.receivedElapsedMs))
      })
      .put("styles", JSONObject().apply {
        for (style in SlideshowStyle.entries) {
          val totals = counts[style.ordinal]
          put(style.wireId, JSONObject().put("records", totals.sum())
              .put("previewRecords", totals[SlideshowStyleHost.PREVIEW.ordinal])
              .put("dreamRecords", totals[SlideshowStyleHost.DREAM.ordinal])
              .put("unknownHostRecords", totals[SlideshowStyleHost.UNKNOWN.ordinal]))
        }
      })
  companion object { const val MAX_RECORDS = 128 }
}

/** Bridge admission is optional; ownership is checked on Main immediately before acceptance. */
internal class SlideshowStyleReporter(
    private val host: SlideshowStyleHost,
    private val prepare: (SlideshowStyleHost, SlideshowStyle) -> SlideshowStyleReport?,
    private val postMain: (() -> Unit) -> Unit,
    private val isCurrent: () -> Boolean,
) {
  fun receive(wireId: String) {
    val style = SlideshowStyle.fromWire(wireId) ?: return
    val report = runCatching { prepare(host, style) }.getOrNull() ?: return
    // No Main post when capture is inactive. Any optional diagnostic failure stays contained.
    runCatching {
      postMain { if (runCatching(isCurrent).getOrDefault(false)) runCatching { report.accept() } }
    }
  }
}
