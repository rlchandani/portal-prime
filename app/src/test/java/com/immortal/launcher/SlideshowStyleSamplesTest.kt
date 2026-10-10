/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SlideshowStyleSamplesTest {
  @Test fun onlyTwelveExactCodeDefinedIdsAreAccepted() {
    val expected = listOf("kenBurns", "diptych", "framed", "galleryWall", "push", "memories",
        "panorama", "polaroid", "mosaic", "filmstrip", "cinema", "heroSidebar")
    assertEquals(expected, SlideshowStyle.entries.map { it.wireId })
    for (id in expected) assertEquals(id, SlideshowStyle.fromWire(id)?.wireId)
    for (id in listOf("", "shuffle", "Filmstrip", " filmstrip", "filmstrip\n", "file:///synthetic.jpg", "x".repeat(4096)))
      assertNull(SlideshowStyle.fromWire(id))
  }

  @Test fun reportRetainsHostStyleAndBothMonotonicReceiptClocks() {
    val s = SlideshowStyleSamples()
    assertTrue(s.record(SlideshowStyleHost.PREVIEW, SlideshowStyle.FILMSTRIP, 100, 104))
    val snapshot = s.snapshot()
    assertEquals(1L, snapshot.getLong("records"))
    val event = snapshot.getJSONArray("events").getJSONObject(0)
    assertEquals("PhotoFramePreviewActivity", event.getString("host"))
    assertEquals("filmstrip", event.getString("style"))
    assertEquals(100L, event.getLong("receivedElapsedMs"))
    assertEquals(104L, event.getLong("acceptedElapsedMs"))
    assertEquals(4L, event.getLong("mainAdmissionDelayMs"))
    assertFalse(snapshot.getBoolean("presentationTimeMeasured"))
  }

  @Test fun malformedTimestampsAreRejectedWithoutAnEventOrStyleTotal() {
    val s = SlideshowStyleSamples()
    for ((received, accepted) in listOf(-1L to 0L, 0L to -1L, 901_000L to 901_000L,
        0L to 900_001L, 12L to 11L, Long.MAX_VALUE to Long.MAX_VALUE))
      assertFalse(s.record(SlideshowStyleHost.DREAM, SlideshowStyle.CINEMA, received, accepted))
    val snapshot = s.snapshot()
    assertEquals(0L, snapshot.getLong("records"))
    assertEquals(6L, snapshot.getLong("invalidRecords"))
    assertEquals(0, snapshot.getJSONArray("events").length())
    assertEquals(0L, snapshot.getJSONObject("styles").getJSONObject("cinema").getLong("records"))
  }

  @Test fun retainedEventsAreBoundedWhileAllValidReportsContributeCounts() {
    val s = SlideshowStyleSamples()
    repeat(SlideshowStyleSamples.MAX_RECORDS + 7) {
      assertTrue(s.record(SlideshowStyleHost.DREAM, SlideshowStyle.MOSAIC, it.toLong(), it.toLong()))
    }
    val snapshot = s.snapshot()
    assertEquals(135L, snapshot.getLong("records"))
    assertEquals(128, snapshot.getJSONArray("events").length())
    assertEquals(7L, snapshot.getLong("discardedRecords"))
    val totals = snapshot.getJSONObject("styles").getJSONObject("mosaic")
    assertEquals(135L, totals.getLong("records"))
    assertEquals(135L, totals.getLong("dreamRecords"))
    assertEquals(0L, totals.getLong("previewRecords"))
  }

  @Test fun invalidLayerStateCannotEnterDiagnosticsOrDiscardValidStyleReadiness() {
    val s = SlideshowStyleSamples()
    for (layer in listOf(
        SlideshowLayerObservation(-1, true, true, 2160, 1440),
        SlideshowLayerObservation(3, true, true, 2160, 1440),
        SlideshowLayerObservation(2, true, true, -1, 1440),
        SlideshowLayerObservation(2, true, true, 2160, 32_769)))
      assertTrue(s.record(SlideshowStyleHost.PREVIEW, SlideshowStyle.FILMSTRIP, 0, 1, layer))
    val snapshot = s.snapshot()
    assertEquals(4L, snapshot.getLong("records"))
    assertEquals(0L, snapshot.getLong("layerRecords"))
    assertEquals(4L, snapshot.getLong("invalidLayerRecords"))
    repeat(4) { assertFalse(snapshot.getJSONArray("events").getJSONObject(it).has("webViewLayer")) }
  }

  @Test fun boundedLayerSnapshotsAreDetachedAndContainOnlyFiveScalars() {
    val s = SlideshowStyleSamples()
    val layer = SlideshowLayerObservation(2, true, true, 2160, 1440)
    repeat(SlideshowStyleSamples.MAX_RECORDS + 1) {
      s.record(SlideshowStyleHost.PREVIEW, SlideshowStyle.FILMSTRIP, 0, 1, layer)
    }
    val snapshot = s.snapshot()
    assertEquals(129L, snapshot.getLong("layerRecords"))
    assertEquals(0L, snapshot.getLong("invalidLayerRecords"))
    assertEquals(128, snapshot.getJSONArray("events").length())
    val observed = snapshot.getJSONArray("events").getJSONObject(0).getJSONObject("webViewLayer")
    assertEquals(setOf("layerType", "hardwareAccelerated", "attached", "widthPx", "heightPx"), observed.keys().asSequence().toSet())
    observed.put("widthPx", 1)
    assertEquals(2160, s.snapshot().getJSONArray("events").getJSONObject(0)
        .getJSONObject("webViewLayer").getInt("widthPx"))
  }

  @Test fun snapshotsAreDetachedFromTheStoredEvents() {
    val s = SlideshowStyleSamples()
    s.record(SlideshowStyleHost.UNKNOWN, SlideshowStyle.FRAMED, 0, 900_000)
    s.snapshot().getJSONArray("events").getJSONObject(0).put("style", "synthetic mutation")
    assertEquals("framed", s.snapshot().getJSONArray("events").getJSONObject(0).getString("style"))
  }
}
