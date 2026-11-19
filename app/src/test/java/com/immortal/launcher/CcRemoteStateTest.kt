/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CcRemoteStateTest {
  private val lamp = CcEntity("light.lamp", "on", mapOf("friendly_name" to "Lamp", "brightness" to 255))
  private val cam = CcEntity("camera.door", "idle", mapOf("access_token" to "SECRET", "entity_picture" to "/api/camera_proxy/camera.door?token=SECRET"))
  private val person = CcEntity("person.sam", "home", mapOf("latitude" to 51.5, "longitude" to -0.1, "source" to "device_tracker.sam_phone"))

  @Test fun journalSendsOnlyWhatMoved() {
    val j = CcRemoteJournal()
    val first = j.read(mapOf(lamp.id to lamp), 1, 0)
    assertTrue(first.full)
    val same = j.read(mapOf(lamp.id to lamp), 1, first.rev)
    assertFalse(same.full); assertTrue(same.changed.isEmpty())
    val off = lamp.copy(state = "off")
    val moved = j.read(mapOf(lamp.id to off, cam.id to cam), 2, first.rev)
    assertEquals(setOf(lamp.id, cam.id), moved.changed.map { it.id }.toSet())
    val gone = j.read(mapOf(lamp.id to off), 3, moved.rev)
    assertEquals(listOf(cam.id), gone.removed)
    assertTrue("a phone ahead of a restarted Portal gets everything", j.read(mapOf(lamp.id to off), 3, gone.rev + 5).full)
  }

  @Test fun entitiesCarryOnlyAllowedAttributes() {
    val camJson = CcRemoteEntities.encode(cam).toString()
    assertFalse("camera access tokens never leave the Portal", "SECRET" in camJson)
    val personJson = CcRemoteEntities.encode(person).getJSONObject("a")
    assertFalse(personJson.has("latitude")); assertFalse(personJson.has("source"))
    assertEquals(255, CcRemoteEntities.encode(lamp).getJSONObject("a").getInt("brightness"))
  }

  @Test fun unchangedPollIsSmall() {
    val s = CcUiState(entities = mapOf(lamp.id to lamp), entitiesVersion = 1, connection = CcConnection(CcPhase.LIVE))
    val j = CcRemoteJournal()
    val now = 10 * 60_000L
    val full = CcRemoteState.json(s, j, 0, -1, now, "Kitchen")
    assertTrue(full.getBoolean("full"))
    assertEquals("live", full.getJSONObject("conn").getString("phase"))
    val again = CcRemoteState.json(s, j, full.getLong("rev"), full.getLong("minute"), now + 1_000, "Kitchen")
    assertTrue(again.optBoolean("unchanged"))
    assertFalse(again.has("entities"))
    val nextMinute = CcRemoteState.json(s, j, full.getLong("rev"), full.getLong("minute"), now + 60_000, "Kitchen")
    assertFalse("time-based wording refreshes each minute", nextMinute.optBoolean("unchanged"))
  }

  @Test fun alertsIgnoreKidsModeForThePhone() {
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage", "friendly_name" to "Garage"), lastChanged = 1)
    val s = CcUiState(entities = mapOf(garage.id to garage), settings = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS),
        connection = CcConnection(CcPhase.LIVE))
    val alerts = CcRemoteSlices.alertsJson(s, 60 * 60_000L)
    assertEquals(1, alerts.length())
    assertEquals("close", alerts.getJSONObject(0).getString("act"))
    assertEquals(garage.id, alerts.getJSONObject(0).getString("actEntity"))
    val sec = CcRemoteSlices.securityJson(s, 60 * 60_000L).getJSONArray("sections")
    assertEquals("garage", sec.getJSONObject(0).getString("group"))
  }

  @Test fun cameraBytesMustBeAPicture() {
    assertTrue(CcRemoteCamera.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)))
    assertFalse(CcRemoteCamera.looksLikeImage("<html>401</html>".toByteArray()))
  }
}
