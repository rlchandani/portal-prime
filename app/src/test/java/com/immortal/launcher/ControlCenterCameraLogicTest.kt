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
import java.util.TimeZone

class ControlCenterCameraLogicTest {
  private fun e(id: String, state: String = "on", vararg attrs: Pair<String, Any?>, changed: Long = 0L) =
      CcEntity(id, state, mapOf(*attrs), changed)

  @Test fun snapshotUrl_asksHaForA640WideJpeg() {
    assertEquals("http://ha.local:8123/api/camera_proxy/camera.front_door?width=640",
        CcCameraLogic.snapshotUrl("http://ha.local:8123/", "camera.front_door"))
    assertEquals("https://x/api/camera_proxy/camera.a?width=320", CcCameraLogic.snapshotUrl("https://x", "camera.a", 320))
  }

  @Test fun sampleSize_neverDecodesWiderThanTheTarget() {
    assertEquals(1, CcCameraLogic.sampleSize(640))
    assertEquals(1, CcCameraLogic.sampleSize(500))
    assertEquals(2, CcCameraLogic.sampleSize(1280))
    assertEquals(4, CcCameraLogic.sampleSize(1920))
    assertEquals(4, CcCameraLogic.sampleSize(2560))
    assertEquals(1, CcCameraLogic.sampleSize(0))
    assertEquals(1, CcCameraLogic.sampleSize(100, 0))
  }

  @Test fun timestamp_isTheCameraStyleClock() {
    assertEquals("2026-11-17 19:42:30", CcCameraLogic.timestamp(1_794_944_550_000L, TimeZone.getTimeZone("UTC")))
  }

  @Test fun gridCameras_putTheFrontDoorFirstAndCapAtFive() {
    val cams = listOf("camera.garage", "camera.back_garden", "camera.front_door", "camera.living", "camera.driveway", "camera.attic")
        .map { e(it, "idle") }
    val grid = CcCameraLogic.gridCameras(cams)
    assertEquals(5, grid.size)
    assertEquals("camera.front_door", grid.first().id)
    assertEquals(listOf("camera.attic", "camera.back_garden", "camera.driveway", "camera.garage"), grid.drop(1).map { it.id })
  }

  @Test fun context_usesThePlanRoom() {
    val plan = CcPlan(listOf(CcFloor("f", "Ground", listOf(CcRoom("porch", "Porch", CcRect(0, 0, 4, 4),
        devices = listOf(CcPlaced("camera.front_door", 0.5f, 0.5f), CcPlaced("light.porch", 0.5f, 0.5f), CcPlaced("lock.front_door", 0.5f, 0.5f),
            CcPlaced("binary_sensor.porch_motion", 0.5f, 0.5f), CcPlaced("sensor.porch_temp", 0.5f, 0.5f)))))))
    val entities = listOf(e("camera.front_door", "idle"), e("light.porch", "off"), e("lock.front_door", "locked"),
        e("binary_sensor.porch_motion", "on", "device_class" to "motion", changed = 5), e("sensor.porch_temp", "20"))
        .associateBy { it.id }
    val ctx = CcCameraLogic.context("camera.front_door", plan, entities)
    assertEquals("Porch", ctx.roomName)
    assertEquals(listOf("binary_sensor.porch_motion"), ctx.motion.map { it.id })
    assertEquals(listOf("light.porch", "lock.front_door"), ctx.shortcuts.map { it.id })
  }

  @Test fun context_withoutPlanMatchesByName() {
    val entities = listOf(e("camera.garage_cam", "idle"), e("cover.garage_door", "open", "device_class" to "garage"),
        e("light.kitchen", "on")).associateBy { it.id }
    val ctx = CcCameraLogic.context("camera.garage_cam", null, entities)
    assertNull(ctx.roomName)
    assertEquals(listOf("cover.garage_door"), ctx.shortcuts.map { it.id })
    assertTrue(CcCameraLogic.tokens("camera.cam_main").isEmpty())
  }

  @Test fun shortcuts_sayAndDoTheOppositeOfNow() {
    val light = e("light.porch", "on", "friendly_name" to "Porch light")
    assertEquals("Porch light off", CcCameraLogic.shortcutLabel(light))
    assertEquals("turn_off", CcCameraLogic.shortcutCall(light)?.service)
    val lock = e("lock.front", "locked", "friendly_name" to "Front door")
    assertEquals("Unlock front door", CcCameraLogic.shortcutLabel(lock))
    assertEquals("unlock", CcCameraLogic.shortcutCall(lock)?.service)
    val garage = e("cover.garage", "open", "device_class" to "garage", "friendly_name" to "Garage")
    assertEquals("Close garage", CcCameraLogic.shortcutLabel(garage))
    assertEquals("close_cover", CcCameraLogic.shortcutCall(garage)?.service)
    assertNull(CcCameraLogic.shortcutCall(e("binary_sensor.garage", "on", "device_class" to "garage_door")))
  }

  @Test fun liveGuard_newestClaimWinsAndReleaseIsOwnerOnly() {
    val g = CcLiveGuard()
    val a = g.claim()
    assertTrue(g.owns(a))
    val b = g.claim()
    assertFalse(g.owns(a))
    assertTrue(g.owns(b))
    g.release(a)
    assertTrue(g.owns(b))
    g.release(b)
    assertEquals(CcLiveGuard.NONE, g.owner.value)
    assertFalse(g.owns(CcLiveGuard.NONE))
  }
}
