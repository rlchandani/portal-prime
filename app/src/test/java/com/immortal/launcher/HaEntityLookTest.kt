/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.HaEntityLook.Service
import com.immortal.launcher.HaEntityLook.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaEntityLookTest {

  @Test fun `an open cover reads Open and shows its switch on (was Off)`() {
    val blinds = haDetail("cover.blinds", state = "open")
    assertEquals("Open", HaEntityLook.status(blinds))
    assertTrue(HaEntityLook.isActive(blinds))
    assertEquals(Tone.PLAIN, HaEntityLook.tone(blinds))
    assertEquals("Closed", HaEntityLook.status(blinds.copy(state = "closed")))
    assertEquals("Opening…", HaEntityLook.status(blinds.copy(state = "opening")))
  }

  @Test fun `an unlocked lock reads Unlocked in red (was Off)`() {
    val lock = haDetail("lock.front", state = "unlocked")
    assertEquals("Unlocked", HaEntityLook.status(lock))
    assertTrue(HaEntityLook.isActive(lock))
    assertEquals(Tone.ALERT, HaEntityLook.tone(lock))
    assertEquals("unlock", HaEntityLook.icon(lock))
    val locked = lock.copy(state = "locked")
    assertEquals("Locked", HaEntityLook.status(locked))
    assertEquals(Tone.PLAIN, HaEntityLook.tone(locked))
    assertEquals("lock", HaEntityLook.icon(locked))
    assertEquals("Jammed", HaEntityLook.status(lock.copy(state = "jammed")))
  }

  @Test fun `lights read brightness, plain lights On, off Off`() {
    assertEquals("70%", HaEntityLook.status(haDetail("light.a", bri = 70)))
    assertEquals("On", HaEntityLook.status(haDetail("light.b")))
    assertEquals("Off", HaEntityLook.status(haDetail("light.c", state = "off", bri = 0)))
    assertEquals(Tone.LIT, HaEntityLook.tone(haDetail("light.a", bri = 70)))
    assertEquals(Tone.PLAIN, HaEntityLook.tone(haDetail("light.c", state = "off")))
    assertEquals("Unavailable", HaEntityLook.status(haDetail("light.d", state = "unavailable")))
  }

  @Test fun `switches, fans and booleans glow when on`() {
    listOf("switch.x", "fan.x", "input_boolean.x").forEach { id ->
      assertEquals(Tone.LIT, HaEntityLook.tone(haDetail(id)))
      assertEquals("On", HaEntityLook.status(haDetail(id)))
      assertEquals("Off", HaEntityLook.status(haDetail(id, state = "off")))
    }
  }

  @Test fun `binary sensors speak their class`() {
    assertEquals("Open", HaEntityLook.status(haDetail("binary_sensor.door", cls = "door")))
    assertEquals(Tone.ALERT, HaEntityLook.tone(haDetail("binary_sensor.door", cls = "door")))
    assertEquals("Closed", HaEntityLook.status(haDetail("binary_sensor.door", state = "off", cls = "door")))
    assertEquals("Wet", HaEntityLook.status(haDetail("binary_sensor.leak", cls = "moisture")))
    assertEquals("Clear", HaEntityLook.status(haDetail("binary_sensor.smoke", state = "off", cls = "smoke")))
    assertEquals("window", HaEntityLook.icon(haDetail("binary_sensor.w", cls = "window")))
    assertEquals("alert", HaEntityLook.icon(haDetail("binary_sensor.s", cls = "smoke")))
    assertEquals("door", HaEntityLook.icon(haDetail("binary_sensor.d", cls = "door")))
  }

  @Test fun `every domain has a glyph, never an empty well`() {
    assertEquals("therm", HaEntityLook.icon(haDetail("sensor.t", unit = "°C")))
    assertEquals("drop", HaEntityLook.icon(haDetail("sensor.h", unit = "%")))
    assertEquals("sun", HaEntityLook.icon(haDetail("sensor.l", unit = "lx", cls = "illuminance")))
    assertEquals("motion", HaEntityLook.icon(haDetail("sensor.co2", unit = "ppm")))
    assertEquals("flame", HaEntityLook.icon(haDetail("climate.c")))
    assertEquals("speaker", HaEntityLook.icon(haDetail("media_player.m")))
    assertEquals("plug", HaEntityLook.icon(haDetail("input_boolean.g")))
  }

  @Test fun `taps call the right service per domain`() {
    assertEquals(Service("lock", "unlock"), HaEntityLook.tapService(haDetail("lock.f", state = "locked")))
    assertEquals(Service("lock", "lock"), HaEntityLook.tapService(haDetail("lock.f", state = "unlocked")))
    assertEquals(Service("cover", "open_cover"), HaEntityLook.tapService(haDetail("cover.b", state = "closed")))
    assertEquals(Service("cover", "close_cover"), HaEntityLook.tapService(haDetail("cover.b", state = "open")))
    assertEquals(Service("light", "toggle"), HaEntityLook.tapService(haDetail("light.l")))
  }

  @Test fun `a tap shows the flipped state straight away`() {
    assertEquals("unlocked", HaEntityLook.tapped(haDetail("lock.f", state = "locked")).state)
    assertEquals("closed", HaEntityLook.tapped(haDetail("cover.b", state = "open")).state)
    assertEquals("off", HaEntityLook.tapped(haDetail("switch.s")).state)
    val dimOff = haDetail("light.d", state = "off", bri = 0)
    val on = HaEntityLook.tapped(dimOff)
    assertEquals("on", on.state)
    assertEquals(60, on.brightnessPct)
    assertEquals(45, HaEntityLook.tapped(haDetail("light.d", state = "off", bri = 45)).brightnessPct)
    assertEquals(null, HaEntityLook.tapped(haDetail("light.plain", state = "off")).brightnessPct)
    assertFalse(HaEntityLook.isActive(HaEntityLook.tapped(haDetail("light.l"))))
  }

  @Test fun `dimming to zero turns off`() {
    val l = haDetail("light.d", bri = 50)
    assertEquals("off", HaEntityLook.dimmed(l, 0).state)
    assertEquals(30, HaEntityLook.dimmed(l, 30).brightnessPct)
    assertEquals(100, HaEntityLook.dimmed(l, 140).brightnessPct)
  }

  @Test fun `media players are active while playing`() {
    assertTrue(HaEntityLook.isActive(haDetail("media_player.m", state = "playing")))
    assertFalse(HaEntityLook.isActive(haDetail("media_player.m", state = "paused")))
  }
}
