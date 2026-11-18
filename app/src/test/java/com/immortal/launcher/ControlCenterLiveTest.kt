/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterLiveTest {
  private val lamp = CcEntity("light.lamp", "off", mapOf("friendly_name" to "Lamp"))

  @Test fun optimistic_showsAtOnceAndClearsOnMatchingEcho() {
    val sent = CcLive().snapshot(listOf(lamp)).send(CcCalls.brightness(lamp, 60), 0L)
    assertEquals("on", sent.view()["light.lamp"]!!.state)
    assertEquals(60, sent.view()["light.lamp"]!!.brightnessPct)
    assertEquals("off", sent.raw["light.lamp"]!!.state)
    val echoed = sent.update(mapOf("light.lamp" to lamp.copy(state = "on", attrs = lamp.attrs + ("brightness" to 153))), 100L)
    assertTrue(echoed.pending.isEmpty())
  }

  @Test fun expire_revertsAndReportsSilentEntities() {
    val sent = CcLive().snapshot(listOf(lamp)).send(CcCalls.lightPower(lamp, true), 0L)
    val (kept, none) = sent.expire(CcLive.ECHO_MS - 1)
    assertTrue(none.isEmpty()); assertEquals(1, kept.pending.size)
    val (gone, silent) = sent.expire(CcLive.ECHO_MS)
    assertTrue(gone.pending.isEmpty())
    assertEquals(listOf("light.lamp"), silent)
    assertEquals("off", gone.view()["light.lamp"]!!.state)
  }

  @Test fun nonMatchingUpdate_isNotSilent() {
    val lock = CcEntity("lock.f", "locked")
    val sent = CcLive().snapshot(listOf(lock)).send(CcCalls.unlock(lock), 0L)
    val moved = sent.update(mapOf("lock.f" to lock.copy(state = "unlocked")), 10L)
    assertEquals(1, moved.pending.size)
    val (_, silent) = moved.expire(CcLive.ECHO_MS)
    assertTrue(silent.isEmpty())
  }

  @Test fun failed_dropsOverride_andAcknowledgedKeepsIt() {
    val sent = CcLive().snapshot(listOf(lamp)).send(CcCalls.lightPower(lamp, true), 0L)
    assertTrue(sent.acknowledged("light.lamp").pending.getValue("light.lamp").acknowledged)
    assertTrue(sent.failed("light.lamp").pending.isEmpty())
    assertEquals(sent, sent.acknowledged("light.none"))
  }

  @Test fun applianceFinishing_isRecordedAndClearedWhenItRunsAgain() {
    val washer = CcEntity("sensor.washer_state", "running")
    val live = CcLive().snapshot(listOf(washer)).update(mapOf(washer.id to washer.copy(state = "complete")), 42L)
    assertEquals(42L, live.done[washer.id])
    assertTrue(live.dismissDone(washer.id).done.isEmpty())
    val again = live.update(mapOf(washer.id to washer.copy(state = "on")), 50L)
    assertFalse(again.done.containsKey(washer.id))
  }

  @Test fun removal_dropsEntityAndOverride() {
    val sent = CcLive().snapshot(listOf(lamp)).send(CcCalls.lightPower(lamp, true), 0L)
    val removed = sent.update(mapOf("light.lamp" to null), 1L)
    assertNull(removed.raw["light.lamp"])
    assertTrue(removed.pending.isEmpty())
  }

  @Test fun compressed_appliesThroughUpdate() {
    val live = CcLive().applyCompressed(JSONObject("""{"a":{"light.lamp":{"s":"on","a":{}}}}"""), 1L)
    assertTrue(live.raw.getValue("light.lamp").isOn)
  }

  @Test fun callWithoutEntityOrOptimistic_isNoop() {
    val l = CcLive()
    assertEquals(l, l.send(CcCalls.undoScene().copy(entityId = null), 0L))
    assertEquals(l, l.send(CcCalls.coverStop(CcEntity("cover.g", "opening")), 0L))
  }

  @Test fun backoff_mockSchedule() {
    assertEquals(1_000L, CcBackoff.delayMs(0))
    assertEquals(8_000L, CcBackoff.delayMs(1))
    assertEquals(16_000L, CcBackoff.delayMs(2))
    assertEquals(32_000L, CcBackoff.delayMs(3))
    assertEquals(60_000L, CcBackoff.delayMs(4))
    assertEquals(60_000L, CcBackoff.delayMs(40))
  }
}
