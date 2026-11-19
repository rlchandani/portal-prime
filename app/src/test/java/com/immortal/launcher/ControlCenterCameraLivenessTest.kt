/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterCameraLivenessTest {
  private val t0 = 1_795_000_000_000L
  private val s = 1_000L
  private val min = 60 * s
  private val hour = 60 * min

  /** One picture fetched again and again: the owner's front door (same bytes every 5 s). */
  @Test fun theSamePictureOverAndOverIsNeverLive() {
    var w = CcCamWatch()
    for (i in 0..20) w = w.frame(hash = 42L, now = t0 + i * 5 * s, captured = null)
    val now = t0 + 100 * s
    assertFalse(CcCamLiveness.isLive(w, now))
    val label = CcCamLiveness.label(w, now)
    assertFalse(label.live)
    assertEquals("Snapshot · unchanged 1 min", label.text)
    assertNull("no fake clock when HA doesn't say when the picture was taken", label.stampMs)
  }

  @Test fun picturesThatKeepChangingAreLive_andStopBeingLiveWhenTheyStop() {
    var w = CcCamWatch()
    for (i in 0..4) w = w.frame(hash = i.toLong(), now = t0 + i * 5 * s, captured = null, source = CcCamSource.SNAPSHOT)
    val last = t0 + 20 * s
    assertTrue(CcCamLiveness.isLive(w, last))
    val live = CcCamLiveness.label(w, last + 2 * s)
    assertTrue(live.live)
    assertEquals("LIVE", live.text)
    assertEquals("the stamp is the newest frame, not the clock", last, live.stampMs)
    // Bytes stop changing: 16 s after the last change it is a snapshot again, aged from that change.
    val stale = CcCamLiveness.label(w, last + 16 * s)
    assertFalse(stale.live)
    assertEquals("Snapshot · 16 s ago", stale.text)
    assertEquals(last, stale.stampMs)
  }

  @Test fun aThirtySecondSnapshotIntervalIsNotVideo() {
    var w = CcCamWatch()
    for (i in 0..3) w = w.frame(hash = i.toLong(), now = t0 + i * 30 * s, captured = null)
    assertFalse("one change per 30 s is a snapshot", CcCamLiveness.isLive(w, t0 + 90 * s + 1))
    assertEquals("Snapshot · just now", CcCamLiveness.label(w, t0 + 90 * s + 1).text)
  }

  @Test fun theFirstFrameAloneProvesNothing() {
    val w = CcCamWatch().frame(7L, t0, null)
    assertFalse(CcCamLiveness.isLive(w, t0))
    assertEquals("Snapshot · checking", CcCamLiveness.label(w, t0 + 3 * s).text)
    assertTrue(w.hasFrame)
  }

  @Test fun haCaptureTimeWins_andTheStampStandsStill() {
    // ring-mqtt's front door: same picture, `timestamp` 267 h ago.
    val captured = t0 - 267 * hour
    var w = CcCamWatch()
    for (i in 0..5) w = w.frame(9L, t0 + i * 5 * s, captured)
    val a = CcCamLiveness.label(w, t0 + 30 * s)
    val b = CcCamLiveness.label(w, t0 + 90 * s)
    assertEquals("Snapshot · 11 d ago", a.text)
    assertTrue(a.captureKnown)
    assertEquals(captured, a.stampMs)
    assertEquals("the stamp does not tick with the clock", a.stampMs, b.stampMs)
  }

  @Test fun aNewPictureDropsTheOldCaptureTimeUnlessHaSendsANewOne() {
    val w = CcCamWatch().frame(1L, t0, t0 - hour).frame(2L, t0 + 5 * s, null)
    assertNull(w.captured)
    assertEquals(t0 + 5 * s, w.lastChange)
    val sameWithTime = CcCamWatch().frame(1L, t0, t0 - hour).frame(1L, t0 + 5 * s, null)
    assertEquals(t0 - hour, sameWithTime.captured)
  }

  @Test fun videoPlaybackMovingIsLive() {
    val w = CcCamWatch().moving(t0, CcCamSource.HLS)
    assertTrue(CcCamLiveness.isLive(w, t0 + 2 * s))
    assertEquals(CcCamSource.HLS, w.source)
    assertFalse(CcCamLiveness.isLive(w, t0 + 20 * s))
  }

  @Test fun rememberedPictureKeepsItsAgeAcrossVisits() {
    var first = CcCamWatch()
    first = first.frame(5L, t0, null)
    val seen = CcCamMemory.Seen(first.hash, first.imageSince)
    val again = CcCamWatch().remembered(seen).frame(5L, t0 + 3 * hour, null)
    assertEquals("same bytes on a later visit: still unchanged since the first sighting", t0, again.imageSince)
    assertEquals("Snapshot · unchanged 3 h", CcCamLiveness.label(again, t0 + 3 * hour).text)
  }

  @Test fun agesAndDurations() {
    assertEquals("just now", CcCamLiveness.ago(9 * s))
    assertEquals("40 s ago", CcCamLiveness.ago(40 * s))
    assertEquals("12 min ago", CcCamLiveness.ago(12 * min + 5 * s))
    assertEquals("3 h ago", CcCamLiveness.ago(3 * hour + 59 * min))
    assertEquals("2 d ago", CcCamLiveness.ago(50 * hour))
    assertEquals("0 s", CcCamLiveness.duration(-5))
  }

  @Test fun captureTimeFromAttributes() {
    assertEquals(1_794_091_354_000L, CcCamLiveness.capturedAt(mapOf("timestamp" to 1_794_091_354)))
    assertEquals(1_794_091_354_000L, CcCamLiveness.capturedAt(mapOf("timestamp" to 1_794_091_354_000L)))
    assertEquals(1_794_091_354_000L, CcCamLiveness.capturedAt(mapOf("timestamp" to "1794091354")))
    assertNull(CcCamLiveness.capturedAt(mapOf("friendly_name" to "Wyze")))
    assertNull(CcCamLiveness.capturedAt(mapOf("timestamp" to 0)))
  }

  @Test fun hashSeesEveryByte() {
    val a = byteArrayOf(1, 2, 3)
    assertEquals(CcCamLiveness.hash(a), CcCamLiveness.hash(byteArrayOf(1, 2, 3)))
    assertNotEquals(CcCamLiveness.hash(a), CcCamLiveness.hash(byteArrayOf(1, 2, 4)))
  }

  @Test fun snapshotUrlsAreNeverTheSameTwice_andStreamsStayOnTheHaHost() {
    val a = CcCameraLogic.freshSnapshotUrl("http://ha:8123/", "camera.front", 1L)
    val b = CcCameraLogic.freshSnapshotUrl("http://ha:8123/", "camera.front", 2L)
    assertEquals("http://ha:8123/api/camera_proxy/camera.front?width=640&_=1", a)
    assertNotEquals(a, b)
    assertEquals("http://ha:8123/api/hls/abc/master_playlist.m3u8", CcCameraLogic.streamUrl("http://ha:8123/", "/api/hls/abc/master_playlist.m3u8"))
    assertEquals("http://ha:8123/api/hls/x.m3u8", CcCameraLogic.streamUrl("http://ha:8123", "http://ha:8123/api/hls/x.m3u8"))
    assertNull(CcCameraLogic.streamUrl("http://ha:8123", "http://elsewhere/api/hls/x.m3u8"))
    assertNull(CcCameraLogic.streamUrl("http://ha:8123", "/api/camera_proxy/camera.x"))
    assertNull(CcCameraLogic.streamUrl("", "/api/hls/x"))
  }

  @Test fun streamSupportComesFromSupportedFeatures() {
    assertTrue(CcCameraLogic.canStream(CcEntity("camera.a", "idle", mapOf("supported_features" to 2))))
    assertTrue(CcCameraLogic.canStream(CcEntity("camera.a", "idle", mapOf("supported_features" to 3))))
    assertFalse(CcCameraLogic.canStream(CcEntity("camera.a", "idle", mapOf("supported_features" to 0))))
    assertFalse("an unavailable printer cam with the bit set can't stream",
        CcCameraLogic.canStream(CcEntity("camera.p", "unavailable", mapOf("supported_features" to 2))))
  }

  @Test fun gridPutsWorkingAndStreamableCamerasFirst() {
    val cams = listOf(
        CcEntity("camera.front_door_snapshot", "unavailable", mapOf("friendly_name" to "Front Door")),
        CcEntity("camera.garden", "idle", mapOf("friendly_name" to "Garden", "supported_features" to 2)),
        CcEntity("camera.front_door_cam_snapshot", "idle", mapOf("friendly_name" to "Front Door Cam Snapshot")))
    assertEquals(listOf("camera.garden", "camera.front_door_cam_snapshot", "camera.front_door_snapshot"),
        CcCameraLogic.gridCameras(cams).map { it.id })
  }

  @Test fun refreshUsesRingMqttsButtonWhenThereIsOne() {
    val reg = CcRegistry(cameraPlatform = mapOf("camera.front_door_cam_snapshot" to "mqtt"),
        snapshotButtons = mapOf("camera.front_door_cam_snapshot" to listOf("button.front_door_cam_take_snapshot", "button.front_door_cam_take_snapshot_2")))
    val cam = CcEntity("camera.front_door_cam_snapshot", "idle", mapOf("timestamp" to 1_794_091_354))
    val st = CcUiState(entities = listOf(cam, CcEntity("button.front_door_cam_take_snapshot", "unavailable"),
        CcEntity("button.front_door_cam_take_snapshot_2", "unknown")).associateBy { it.id }, registry = reg)
    val info = CcCamInfo.of(st, cam.id)
    assertEquals("button.front_door_cam_take_snapshot_2", info.refreshButton)
    assertEquals(1_794_091_354_000L, info.captured)
    assertEquals("mqtt", info.platform)
    assertFalse(info.canStream)
    val call = info.refreshCall(cam.id)
    assertEquals(listOf("button", "press", "button.front_door_cam_take_snapshot_2"), listOf(call.domain, call.service, call.entityId))
    val wyze = CcCamInfo.of(CcUiState(entities = mapOf("camera.w" to CcEntity("camera.w", "idle"))), "camera.w").refreshCall("camera.w")
    assertEquals(listOf("homeassistant", "update_entity", "camera.w"), listOf(wyze.domain, wyze.service, wyze.entityId))
  }

  @Test fun plainWordsForWhatTheCameraCanDo() {
    assertEquals("Live video from Home Assistant", CcCamLiveness.sourceLine(CcCamSource.HLS, live = true, canStream = true, platform = null))
    assertEquals("Wyze camera · Home Assistant only has still pictures for this camera",
        CcCamLiveness.sourceLine(CcCamSource.SNAPSHOT, live = false, canStream = false, platform = "wyze_ha"))
    assertTrue(CcCamLiveness.whyNotLive("mqtt").contains("RTSP"))
  }

  @Test fun mjpegThatGaveNothingIsNotRetriedForAWhile() {
    val id = "camera.test_mjpeg_${System.nanoTime()}"
    assertTrue(CcCamMemory.mjpegWorthTrying(id, t0))
    CcCamMemory.mjpegFailed(id, t0)
    assertFalse(CcCamMemory.mjpegWorthTrying(id, t0 + min))
    assertTrue(CcCamMemory.mjpegWorthTrying(id, t0 + CcCamMemory.MJPEG_RETRY_MS + 1))
  }

  @Test fun screenReaderWordsChangeByTheMinuteNotTheSecond() {
    var w = CcCamWatch()
    for (i in 0..4) w = w.frame(i.toLong(), t0 + i * 5 * s, null)
    assertEquals("live", CcCamLiveness.speech(w, t0 + 21 * s))
    assertEquals("snapshot, under a minute ago", CcCamLiveness.speech(w, t0 + 40 * s))
    assertEquals(CcCamLiveness.speech(w, t0 + 40 * s), CcCamLiveness.speech(w, t0 + 50 * s))
    val ring = CcCamWatch().frame(9L, t0, t0 - 267 * hour)
    assertEquals("snapshot, 11 d ago", CcCamLiveness.speech(ring, t0 + 20 * s))
  }
}
