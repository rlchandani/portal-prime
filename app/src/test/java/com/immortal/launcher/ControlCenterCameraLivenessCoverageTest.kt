/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcCamLiveness] wording and edges, [CcCamInfo.of] from HA state, and [CcCamMemory]'s frame rule. */
class ControlCenterCameraLivenessCoverageTest {
  @Test fun platformNamesAndWhyNotLive() {
    assertEquals("Ring (MQTT)", CcCamLiveness.platformName("mqtt"))
    assertEquals("Ring", CcCamLiveness.platformName("ring"))
    assertEquals("Bambu printer", CcCamLiveness.platformName("bambu_lab"))
    assertEquals("Generic camera", CcCamLiveness.platformName("generic_camera"))
    assertNull(CcCamLiveness.platformName(""))
    assertNull(CcCamLiveness.platformName(null))
    assertTrue(CcCamLiveness.whyNotLive("mqtt").contains("ring-mqtt"))
    assertTrue(CcCamLiveness.whyNotLive("wyze_ha").contains("Wyze"))
    assertTrue(CcCamLiveness.whyNotLive(null).startsWith("Home Assistant doesn't offer a stream"))
  }

  @Test fun sourceLinesForEveryCase() {
    assertEquals("Live video from Home Assistant", CcCamLiveness.sourceLine(CcCamSource.HLS, true, true, null))
    assertEquals("Live picture stream from Home Assistant", CcCamLiveness.sourceLine(CcCamSource.MJPEG, true, true, null))
    assertEquals("Pictures are updating every few seconds", CcCamLiveness.sourceLine(CcCamSource.SNAPSHOT, true, false, null))
    assertEquals("Pictures are updating every few seconds", CcCamLiveness.sourceLine(null, true, false, null))
    assertEquals("Home Assistant can stream this camera · connecting", CcCamLiveness.sourceLine(null, false, true, null))
    assertEquals("Wyze camera · Home Assistant only has still pictures for this camera", CcCamLiveness.sourceLine(null, false, false, "wyze_ha"))
    assertEquals("Home Assistant only has still pictures for this camera", CcCamLiveness.sourceLine(null, false, false, null))
  }

  @Test fun captureTimesSkipJunkAndConvertSeconds() {
    assertNull(CcCamLiveness.capturedAt(emptyMap()))
    assertNull(CcCamLiveness.capturedAt(mapOf("timestamp" to "soon", "last_snapshot" to 0, "snapshot_time" to -5)))
    assertEquals(1_700_000_000_000L, CcCamLiveness.capturedAt(mapOf("timestamp" to "1700000000")))
    assertEquals(1_700_000_000_123L, CcCamLiveness.capturedAt(mapOf("timestamp" to null, "last_snapshot" to 1_700_000_000_123L)))
    assertEquals(2_000L, CcCamLiveness.capturedAt(mapOf("snapshot_time" to 2.9)))
  }

  @Test fun durationsInEveryUnit() {
    assertEquals("0 s", CcCamLiveness.duration(-1))
    assertEquals("59 s", CcCamLiveness.duration(59_999))
    assertEquals("59 min", CcCamLiveness.duration(60 * 60_000L - 1))
    assertEquals("23 h", CcCamLiveness.duration(24 * 60 * 60_000L - 1))
    assertEquals("2 d", CcCamLiveness.duration(2 * 24 * 60 * 60_000L))
    assertEquals("just now", CcCamLiveness.ago(9_999))
    assertEquals("10 s ago", CcCamLiveness.ago(10_000))
  }

  @Test fun labelsWithoutAChangeSayCheckingThenUnchanged() {
    val w = CcCamWatch(imageSince = 1_000L, lastFrameAt = 1_000L)
    assertEquals("Snapshot · checking", CcCamLiveness.label(w, 2_000L).text)
    val later = CcCamLiveness.label(w, 1_000L + 2 * 60 * 60_000L)
    assertEquals("Snapshot · unchanged 2 h", later.text)
    assertNull(later.stampMs)
    assertFalse(later.captureKnown)
    assertEquals("snapshot, unchanged 2 h", CcCamLiveness.speech(w, 1_000L + 2 * 60 * 60_000L))
    val zeroCapture = w.copy(captured = 0L, lastChange = 5_000L)
    assertEquals(5_000L, CcCamLiveness.label(zeroCapture, 6_000L).stampMs)
  }

  @Test fun liveNeedsTwoRecentChangesCloseTogether() {
    val now = 100_000L
    val live = CcCamWatch(lastFrameAt = now, lastChange = now - 1_000, previousChange = now - 2_000)
    assertTrue(CcCamLiveness.isLive(live, now))
    assertEquals("live", CcCamLiveness.speech(live, now))
    assertFalse(CcCamLiveness.isLive(live.copy(lastFrameAt = 0), now))
    assertFalse(CcCamLiveness.isLive(live.copy(previousChange = 0), now))
    assertFalse(CcCamLiveness.isLive(live.copy(lastChange = 0), now))
    assertFalse(CcCamLiveness.isLive(live, now + CcCamLiveness.LIVE_WINDOW_MS))
    assertFalse(CcCamLiveness.isLive(live.copy(previousChange = now - 1_000 - CcCamLiveness.LIVE_WINDOW_MS - 1), now))
  }

  @Test fun movingFromAFirstFrameStartsTheChangeClock() {
    val w = CcCamWatch().moving(5_000L, CcCamSource.HLS)
    assertEquals(5_000L, w.previousChange)
    assertEquals(5_000L, w.lastChange)
    assertEquals(CcCamSource.HLS, w.source)
    assertEquals(w, w.remembered(null))
  }

  @Test fun memoryKeepsOnlyRealFramesAndRetriesMjpegAfterAWhile() {
    val id = "camera.coverage_mem_${System.nanoTime()}"
    CcCamMemory.put(id, CcCamWatch(hash = 9L))
    assertNull("no frame, nothing remembered", CcCamMemory.get(id))
    CcCamMemory.put(id, CcCamWatch(hash = 9L, imageSince = 3L, lastFrameAt = 4L))
    assertEquals(CcCamMemory.Seen(9L, 3L), CcCamMemory.get(id))
    assertTrue(CcCamMemory.mjpegWorthTrying(id, 0L))
    CcCamMemory.mjpegFailed(id, 1_000L)
    assertFalse(CcCamMemory.mjpegWorthTrying(id, 1_000L + CcCamMemory.MJPEG_RETRY_MS))
    assertTrue(CcCamMemory.mjpegWorthTrying(id, 1_001L + CcCamMemory.MJPEG_RETRY_MS))
  }

  @Test fun camInfoFromStateAndRegistry() {
    val cam = CcEntity("camera.porch", "unavailable", mapOf("timestamp" to 1_700_000_000))
    val dead = CcEntity("button.porch_snap_a", "unavailable")
    val ok = CcEntity("button.porch_snap_b", "unknown")
    val reg = CcRegistry(cameraPlatform = mapOf(cam.id to "mqtt"),
        snapshotButtons = mapOf(cam.id to listOf(dead.id, "button.missing", ok.id)))
    val info = CcCamInfo.of(CcUiState(entities = listOf(cam, dead, ok).associateBy { it.id }, registry = reg), cam.id)
    assertTrue(info.unavailable)
    assertEquals("mqtt", info.platform)
    assertEquals(ok.id, info.refreshButton)
    assertEquals(1_700_000_000_000L, info.captured)
    assertEquals(CcCall("button", "press", ok.id, label = "Asked the camera for a new picture"), info.refreshCall(cam.id))
    val unknown = CcCamInfo.of(CcUiState(), "camera.nope")
    assertEquals(CcCamInfo(), unknown)
    assertEquals("update_entity", unknown.refreshCall("camera.nope").service)
  }
}
