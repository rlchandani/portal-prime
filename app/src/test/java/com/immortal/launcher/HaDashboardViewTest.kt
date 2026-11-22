/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaDashboardViewTest {
  private fun at(hour: Int, minute: Int) = Calendar.getInstance().apply {
    set(2026, Calendar.NOVEMBER, 22, hour, minute, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  private val house = haHouse(
      haArea("living_room", haDetail("light.a", bri = 70), haDetail("sensor.t", state = "20.5", unit = "°C", cls = "temperature")),
      haArea("hall", haDetail("lock.front", state = "locked", name = "Front door"), haDetail("switch.x")),
      haArea("garage"),
      scenes = listOf(HaDashboardApi.Scene("scene.m", "Morning"), HaDashboardApi.Scene("scene.n", "Night")))

  @Test fun `ready view - greeting, subline, room counts, Now scene`() {
    val now = at(18, 48)
    val v = haDashboardView(HaDashboardState.Ready(house, now), HaDashboardSession(), now, use24h = false)
    assertEquals("Good evening", v.greeting)
    assertEquals("6:48 · 2 lights on · Front door locked · 20.5° inside", v.subline)
    assertEquals(listOf(1, 1, 0), v.rooms.map { it.lit })
    assertEquals(listOf(true, true, false), v.rooms.map { it.hasEntities })
    assertEquals(listOf("sofa", "hall", "car"), v.rooms.map { it.icon })
    assertEquals("living_room", v.room?.id)
    assertNull(v.offlineAge)
  }

  @Test fun `night with lights on suggests Night, optimistic state counts`() {
    val now = at(22, 5)
    val v = haDashboardView(HaDashboardState.Ready(house, now), HaDashboardSession(), now, use24h = true)
    assertEquals("Good night", v.greeting)
    assertEquals("Night", v.scenes.first().name)
    assertEquals("22:05 · 2 lights on · Front door locked · 20.5° inside · tap Night to wind down", v.subline)
    val off = HaDashboardSession().begin(HaDashboardSession.Action.Tap(haDetail("switch.x")))
    assertEquals(0, haDashboardView(HaDashboardState.Ready(house, now), off, now, true).rooms[1].lit)
  }

  @Test fun `offline shows its age, states replace the subline`() {
    val now = at(9, 0)
    val v = haDashboardView(HaDashboardState.Offline(house, now - 240_000), HaDashboardSession(), now, false)
    assertEquals("Last updated 4 min ago", v.offlineAge)
    assertEquals("9:00 · 2 lights on · Last updated 4 min ago", v.subline)
    assertEquals("Home Assistant needs you to sign in again",
        haDashboardView(HaDashboardState.Unauthorized, HaDashboardSession(), now, false).subline)
    assertEquals("Connecting to Home Assistant…", haDashboardView(HaDashboardState.Loading, HaDashboardSession(), now, false).subline)
    assertEquals(emptyList<HaRoomEntry>(), haDashboardView(HaDashboardState.NoRooms, HaDashboardSession(), now, false).rooms)
  }

  @Test fun `connection pill per state`() {
    assertNull(connectionOf(HaDashboardState.NotConfigured))
    assertEquals(HaConnection.CONNECTING, connectionOf(HaDashboardState.Loading))
    assertEquals(HaConnection.SIGN_IN, connectionOf(HaDashboardState.Unauthorized))
    assertEquals(HaConnection.OFFLINE, connectionOf(HaDashboardState.Unreachable(null)))
    assertEquals(HaConnection.LIVE, connectionOf(HaDashboardState.NoRooms))
  }
}
