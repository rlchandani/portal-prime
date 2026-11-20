/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taps in 2D and 3D (through the inverse projection), and the lights' fade when they change. */
class ControlCenterPlanHitFadeTest {
  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 10, 8), devices = listOf(
      CcPlaced("light.k1", 0.25f, 0.5f), CcPlaced("light.k2", 0.75f, 0.5f), CcPlaced("lock.back", 0.1f, 0.9f)))
  private val living = CcRoom("living", "Living room", CcRect(10, 0, 12, 8), devices = listOf(CcPlaced("light.lamp", 0.5f, 0.5f)))
  private val hall = CcRoom("hall", "Hall", CcRect(0, 8, 22, 4))
  private val floor = CcFloor("g", "Ground", listOf(kitchen, living, hall))

  private fun light(id: String, on: Boolean, b: Int = 255) = CcEntity(id, if (on) "on" else "off", mapOf("brightness" to b))
  private fun entities(k1: Boolean, k2: Boolean, lamp: Boolean = true) =
      listOf(light("light.k1", k1), light("light.k2", k2), light("light.lamp", lamp), CcEntity("lock.back", "locked")).associateBy { it.id }
  private fun model(e: Map<String, CcEntity>) = CcPlanModel.build(floor, e, ControlCenterConfig.Settings(), 0L, emptyMap())

  private val m = model(entities(k1 = true, k2 = true))
  private val w = 1400f
  private val h = 900f
  private val fit2 = CcPlanGeo.fit(m.bounds, w, h)
  private val view3 = CcIso.blend(fit2, CcIso.fit(m.bounds, w, h), m.bounds.center, 1f)

  private fun screen(view: CcAffine, cx: Float, cy: Float) = view.x(fit2.x(cx), fit2.y(cy)) to view.y(fit2.x(cx), fit2.y(cy))

  @Test fun flatPlanTapsFindTheRoomUnderTheFinger() {
    val (x, y) = screen(CcAffine.IDENTITY, 15f, 3f)
    assertEquals(CcPlanHit.Room("living"), CcPlanHitTest.hit(m, fit2, CcAffine.IDENTITY, x, y, 30f))
  }

  @Test fun isoTapsGoBackThroughTheProjection() {
    // A spot in the living room (away from its lamp), where it is drawn in 3D…
    val (x, y) = screen(view3, 13f, 6f)
    assertEquals(CcPlanHit.Room("living"), CcPlanHitTest.hit(m, fit2, view3, x, y, 30f))
    // …and the hall's, which on screen is nowhere near its 2D spot.
    val (hx, hy) = screen(view3, 18f, 10f)
    assertEquals(CcPlanHit.Room("hall"), CcPlanHitTest.hit(m, fit2, view3, hx, hy, 30f))
  }

  @Test fun isoMarkersAreHitWhereTheyAreDrawnUpright() {
    val lock = m.badges.first { it.entityId == "lock.back" }
    val (x, y) = screen(view3, lock.x, lock.y)
    val hit = CcPlanHitTest.hit(m, fit2, view3, x + 5f, y - 5f, 36f)
    assertEquals(CcPlanHit.Device("kitchen", "lock.back", lock.kind), hit)
  }

  @Test fun aTapOffThePlanHitsNothing() {
    assertNull(CcPlanHitTest.hit(m, fit2, view3, 2f, 2f, 10f))
    assertNull(CcPlanHitTest.hit(m, fit2, CcAffine.IDENTITY, 2f, 2f, 10f))
  }

  @Test fun lightsTurningOffFadeOneAfterAnother() {
    val off = model(entities(k1 = false, k2 = false))
    val fade = CcLightFade.diff(m, off)
    assertNotNull(fade)
    fade!!
    assertEquals(setOf("kitchen"), fade.rooms)
    assertEquals(listOf("light.k1", "light.k2"), fade.lights.map { it.entityId })
    assertTrue(fade.lights.all { it.from > 0f && it.to == 0f })
    assertEquals(listOf(0, 1), fade.lights.map { it.order })
    assertEquals(CcLightFade.FADE_MS + CcLightFade.STAGGER_MS, fade.durationMs)
    // Ends exactly at the new state; the second light hasn't started at the very beginning.
    assertEquals(0f, fade.local(fade.lights[1], 0f), 0.0001f)
    assertEquals(1f, fade.local(fade.lights[1], 1f), 0.0001f)
    assertEquals(1f, fade.local(fade.lights[0], (CcLightFade.FADE_MS.toFloat() / fade.durationMs)), 0.0001f)
    assertEquals(1f, fade.washLocal(fade.washes.single(), 1f), 0.0001f)
  }

  @Test fun nothingChangedOrAnotherFloorIsNoFade() {
    assertNull(CcLightFade.diff(m, model(entities(k1 = true, k2 = true))))
    val other = CcPlanModel.build(floor.copy(id = "u"), entities(k1 = false, k2 = false), ControlCenterConfig.Settings(), 0L, emptyMap())
    assertNull(CcLightFade.diff(m, other))
  }

  @Test fun aWholeHouseChangeStaggersOnlyTheFirstFew() {
    val many = (0 until 10).map { CcPlaced("light.x$it", it / 10f, 0.5f) }
    val big = floor.copy(rooms = listOf(kitchen.copy(devices = many)))
    val on = many.associate { it.entityId to light(it.entityId, true) }
    val offAll = many.associate { it.entityId to light(it.entityId, false) }
    val fade = CcLightFade.diff(CcPlanModel.build(big, on, ControlCenterConfig.Settings(), 0L, emptyMap()),
        CcPlanModel.build(big, offAll, ControlCenterConfig.Settings(), 0L, emptyMap()))!!
    assertEquals(CcLightFade.MAX_STAGGERED - 1, fade.lights.maxOf { it.order })
    assertEquals(CcLightFade.FADE_MS + (CcLightFade.MAX_STAGGERED - 1) * CcLightFade.STAGGER_MS, fade.durationMs)
  }
}
