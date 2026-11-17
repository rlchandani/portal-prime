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

/** Dedupe by id, per-camera cooldown (a press after motion still shows), quiet hours and copy. */
class DoorbellGateTest {
  private val cooldown = 30_000L

  private fun alert(id: String, kind: DoorbellKind = DoorbellKind.RING, camera: String = "camera.front_door") =
      DoorbellAlert(id, kind, if (kind == DoorbellKind.RING) "event.front_doorbell" else "binary_sensor.porch_motion",
          camera, "Kitchen", 0L)

  @Test fun sameIdShowsOnce() {
    val first = DoorbellGate().admit(alert("aaaaaaaa1"), 1_000L, cooldown)
    assertTrue(first.show)
    val again = first.gate.admit(alert("aaaaaaaa1"), 1_200L, cooldown)
    assertFalse(again.show)
    assertEquals(DoorbellGate.Reason.DUPLICATE, again.reason)
  }

  @Test fun cooldownIsPerCamera() {
    val shown = DoorbellGate().admit(alert("aaaaaaaa1"), 0L, cooldown).gate
    val sameCamera = shown.admit(alert("bbbbbbbb2"), 10_000L, cooldown)
    assertFalse(sameCamera.show)
    assertEquals(DoorbellGate.Reason.COOLDOWN, sameCamera.reason)
    assertTrue(shown.admit(alert("cccccccc3", camera = "camera.garden"), 10_000L, cooldown).show)
    assertTrue(sameCamera.gate.admit(alert("dddddddd4"), 30_000L, cooldown).show)
  }

  @Test fun aPressAfterMotionStillShowsButMotionAfterAPressDoesNot() {
    val motion = DoorbellGate().admit(alert("aaaaaaaa1", DoorbellKind.MOTION), 0L, cooldown)
    val press = motion.gate.admit(alert("bbbbbbbb2"), 5_000L, cooldown)
    assertTrue(press.show)
    assertFalse(press.gate.admit(alert("cccccccc3", DoorbellKind.MOTION), 8_000L, cooldown).show)
    assertFalse(press.gate.admit(alert("dddddddd4"), 8_000L, cooldown).show)
  }

  @Test fun noCameraCoolsDownPerEntity() {
    val shown = DoorbellGate().admit(alert("aaaaaaaa1", camera = ""), 0L, cooldown).gate
    assertFalse(shown.admit(alert("bbbbbbbb2", camera = ""), 1_000L, cooldown).show)
    assertTrue(shown.admit(alert("cccccccc3", DoorbellKind.MOTION, camera = ""), 1_000L, cooldown).show)
  }

  @Test fun seenIdsExpireAndStayBounded() {
    var gate = DoorbellGate()
    repeat(DoorbellGate.MAX_SEEN + 20) { i -> gate = gate.admit(alert("id%08d".format(i), camera = "camera.c$i"), i.toLong(), cooldown).gate }
    assertTrue(gate.seen.size <= DoorbellGate.MAX_SEEN + 1)
    val late = gate.admit(alert("id00000099", camera = "camera.c99"), DoorbellGate.SEEN_TTL_MS + 1_000L, cooldown)
    assertEquals(1, late.gate.seen.size)
  }

  @Test fun gateIsImmutable() {
    val gate = DoorbellGate()
    gate.admit(alert("aaaaaaaa1"), 0L, cooldown)
    assertTrue(gate.seen.isEmpty())
  }

  @Test fun quietHoursAreVisualOnlyAndChimeOffIsSilent() {
    assertEquals(DoorbellRing.Decision(sound = true, quiet = false), DoorbellRing.decide(chimeOn = true, quietHours = false))
    assertEquals(DoorbellRing.Decision(sound = false, quiet = true), DoorbellRing.decide(chimeOn = true, quietHours = true))
    assertEquals(DoorbellRing.Decision(sound = false, quiet = false), DoorbellRing.decide(chimeOn = false, quietHours = false))
    assertEquals("Quiet hours, shown without sound", DoorbellRing.statusLine(DoorbellRing.decide(true, true), true))
    assertEquals("Chime is off on this Portal", DoorbellRing.statusLine(DoorbellRing.decide(false, false), false))
  }

  @Test fun quietHoursFollowTheChimeWindow() {
    val zone = java.util.TimeZone.getTimeZone("UTC")
    val chime = ChimeConfig.Settings(quietHoursOn = true, quietStartMin = 22 * 60, quietEndMin = 8 * 60)
    val at = { h: Int -> h * 3_600_000L }
    assertTrue(DinnerQuiet.isQuiet(chime, at(23), zone))
    assertTrue(DinnerQuiet.isQuiet(chime, at(7), zone))
    assertFalse(DinnerQuiet.isQuiet(chime, at(12), zone))
    assertFalse(DinnerQuiet.isQuiet(chime.copy(quietHoursOn = false), at(23), zone))
  }

  @Test fun titles() {
    assertEquals("Someone’s at the door", DoorbellRing.title(alert("aaaaaaaa1")))
    assertEquals("Motion at Front door", DoorbellRing.title(alert("aaaaaaaa1", DoorbellKind.MOTION)))
    assertEquals("Motion at the door", DoorbellRing.title(alert("aaaaaaaa1", DoorbellKind.MOTION, camera = "")))
  }
}
