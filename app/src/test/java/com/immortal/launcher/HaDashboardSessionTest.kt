/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.HaDashboardSession.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaDashboardSessionTest {
  private val lamp = haDetail("switch.lamp", state = "off", name = "Lamp")
  private val house = haHouse(haArea("living", lamp), haArea("kitchen", haDetail("light.k")))

  @Test fun `the first room is chosen until one is picked, a vanished room falls back`() {
    val s = HaDashboardSession()
    assertEquals("living", s.room(house)?.id)
    assertEquals("kitchen", s.select("kitchen").room(house)?.id)
    assertEquals("living", s.select("gone").room(house)?.id)
    assertNull(s.room(haHouse()))
  }

  @Test fun `a tap shows at once and survives a fetch while in flight`() {
    val s = HaDashboardSession().begin(Action.Tap(lamp))
    assertEquals("on", s.effective(listOf(lamp)).single().state)
    assertEquals("on", s.fetched().effective(listOf(lamp)).single().state)
  }

  @Test fun `after success the next fetch answers for the tile`() {
    val s = HaDashboardSession().begin(Action.Tap(lamp)).succeeded(Action.Tap(lamp), serial = 1)
    assertEquals("on", s.effective(listOf(lamp)).single().state)
    assertNull(s.toast)
    assertEquals(emptyMap<String, HaDashboardApi.Detail>(), s.fetched().overrides)
  }

  @Test fun `a refused call reverts, rings the tile and offers Try again`() {
    val action = Action.Tap(lamp)
    val s = HaDashboardSession().begin(action).failed(action, serial = 7)
    assertEquals("off", s.effective(listOf(lamp)).single().state)
    assertEquals("switch.lamp", s.failedId)
    assertEquals("Couldn't change Lamp · Home Assistant didn't answer", s.toast?.text)
    assertEquals(action, s.toast?.retry)
    val dismissed = s.dismissToast(7)
    assertNull(dismissed.toast)
    assertNull(dismissed.failedId)
    assertEquals(s, s.dismissToast(6)) // an older toast's timer doesn't clear a newer one
  }

  @Test fun `scenes and all-off confirm with a toast`() {
    val scene = Action.Scene("scene.night", "Night")
    val s = HaDashboardSession().begin(scene)
    assertEquals("scene.night", s.firedScene)
    assertEquals("Night scene on", s.succeeded(scene, 2).toast?.text)
    assertNull(s.failed(scene, 3).firedScene)
    val lit = listOf(haDetail("light.a"), haDetail("switch.b"))
    val off = Action.AllOff("Kitchen", lit)
    val after = HaDashboardSession().begin(off)
    assertTrue(after.effective(lit).all { it.state == "off" })
    assertEquals("Everything in Kitchen is off", after.succeeded(off, 4).toast?.text)
  }

  @Test fun `offline refuses with a reason and changes nothing`() {
    val s = HaDashboardSession()
    assertEquals("Offline · can't change Lamp right now", s.refusedOffline(Action.Tap(lamp), 1).toast?.text)
    assertEquals("Offline · scenes run when Home Assistant is back", s.refusedOffline(Action.Scene("scene.x", "X"), 1).toast?.text)
    assertEquals("Offline · thermostat paused", s.refusedOffline(Action.Temp(haDetail("climate.c"), 21f), 1).toast?.text)
    assertEquals("Offline · dimmer paused", s.refusedOffline(Action.Dim(haDetail("light.d", bri = 4), 50), 1).toast?.text)
    assertEquals(emptyMap<String, HaDashboardApi.Detail>(), s.refusedOffline(Action.Tap(lamp), 1).overrides)
  }

  @Test fun `dimmer and thermostat show their new values`() {
    val light = haDetail("light.d", bri = 20)
    assertEquals(80, HaDashboardSession().begin(Action.Dim(light, 80)).effective(listOf(light)).single().brightnessPct)
    val c = haDetail("climate.c", state = "heat", target = 21f)
    assertEquals(21.5f, HaDashboardSession().begin(Action.Temp(c, 21.5f)).effective(listOf(c)).single().targetTemp)
  }

  @Test fun `history is remembered per entity`() {
    assertEquals(listOf(1f, 2f), HaDashboardSession().withHistory("sensor.t", listOf(1f, 2f)).history["sensor.t"])
  }
}
