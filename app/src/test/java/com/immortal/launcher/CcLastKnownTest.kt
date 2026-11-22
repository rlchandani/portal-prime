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

/** Last-known Home Assistant states for Control Center's open ([CcLastKnown]), pure. */
class CcLastKnownTest {
  private fun e(id: String, state: String = "on", cls: String? = null) =
      CcEntity(id, state, buildMap { put("friendly_name", id.substringAfter('.')); cls?.let { put("device_class", it) } })

  @Test fun safetyCriticalIsLocksAlarmGarageGateAndHazardSensors() {
    listOf(e("lock.front"), e("alarm_control_panel.home"), e("cover.garage", cls = "garage"), e("cover.drive", cls = "gate"),
        e("binary_sensor.leak", cls = "moisture"), e("binary_sensor.smoke", cls = "smoke"),
        e("binary_sensor.co", cls = "carbon_monoxide"), e("binary_sensor.gas", cls = "gas"))
        .forEach { assertTrue(it.id, CcLastKnown.critical(it)) }
    listOf(e("light.kitchen"), e("cover.blind", cls = "shade"), e("binary_sensor.door", cls = "door"), e("switch.fan"))
        .forEach { assertFalse(it.id, CcLastKnown.critical(it)) }
  }

  @Test fun goingToTheBackgroundKeepsTheWatchedSetLiveAndRetainsTheRest() {
    val raw = listOf(e("light.kitchen"), e("lock.front", "locked"), e("binary_sensor.leak", "off", "moisture")).associateBy { it.id }
    val split = CcLastKnown.split(raw, keep = setOf("binary_sensor.leak"), previous = emptyMap())
    assertEquals(listOf("binary_sensor.leak"), split.kept.map { it.id })
    assertEquals(setOf("light.kitchen", "lock.front"), split.retained.keys)
    assertEquals("locked", split.retained.getValue("lock.front").state)
  }

  @Test fun noBackgroundFilterKeepsEverythingLive() {
    val raw = mapOf("light.a" to e("light.a"))
    val split = CcLastKnown.split(raw, keep = null, previous = mapOf("light.b" to e("light.b")))
    assertEquals(listOf("light.a"), split.kept.map { it.id })
    assertTrue(split.retained.isEmpty())
  }

  @Test fun aNewerDropReplacesTheOlderLastKnownAndAWatchedIdLeavesIt() {
    val previous = mapOf("light.a" to e("light.a", "off"), "binary_sensor.leak" to e("binary_sensor.leak", "on", "moisture"))
    val split = CcLastKnown.split(mapOf("light.a" to e("light.a", "on")), keep = setOf("binary_sensor.leak"), previous = previous)
    assertEquals(mapOf("light.a" to e("light.a", "on")), split.retained)
  }

  @Test fun theRetainedSetIsCappedKeepingTheNewest() {
    val raw = (0 until CcLastKnown.MAX_RETAINED + 5).associate { "light.l$it" to e("light.l$it") }
    val split = CcLastKnown.split(raw, keep = emptySet(), previous = emptyMap())
    assertEquals(CcLastKnown.MAX_RETAINED, split.retained.size)
    assertTrue("light.l${CcLastKnown.MAX_RETAINED + 4}" in split.retained)
    assertFalse("light.l0" in split.retained)
  }

  @Test fun anOpenRestoresOnlyWhatHaHasNotAlreadyGivenBack() {
    val restored = CcLastKnown.restorable(mapOf("light.a" to e("light.a", "off")),
        mapOf("light.a" to e("light.a", "on"), "lock.front" to e("lock.front", "locked")))
    assertEquals(setOf("lock.front"), restored.keys)
  }

  @Test fun onlyAFullEntityConfirms() {
    val event = JSONObject().put("a", JSONObject().put("lock.front", JSONObject().put("s", "locked")))
    assertEquals(listOf("lock.front"), CcLastKnown.additions(event))
    assertTrue("a diff confirms nothing", CcLastKnown.additions(CcEngineRig.diff("lock.front", "unlocked")).isEmpty())
    assertEquals(setOf("light.a"), CcLastKnown.confirm(setOf("light.a", "lock.front"), listOf("lock.front")))
    assertEquals(setOf("light.a"), CcLastKnown.confirm(setOf("light.a"), emptyList()))
  }

  @Test fun unconfirmedSafetyEntitiesLookInFlightAndOthersDont() {
    val entities = listOf(e("light.a"), e("lock.front", "unlocked")).associateBy { it.id }
    assertEquals(setOf("x", "lock.front"), CcLastKnown.busy(setOf("x"), setOf("light.a", "lock.front"), entities))
    assertEquals(setOf("x"), CcLastKnown.busy(setOf("x"), emptySet(), entities))
  }

  @Test fun actionsOnAnUnconfirmedSafetyEntityAreRefused() {
    val lock = e("lock.front", "locked")
    assertEquals("Checking front — try again in a moment", CcLastKnown.refusal(lock, setOf("lock.front")))
    assertNull("confirmed", CcLastKnown.refusal(lock, emptySet()))
    assertNull("not safety-critical", CcLastKnown.refusal(e("light.a"), setOf("light.a")))
    assertNull(CcLastKnown.refusal(null, setOf("lock.front")))
  }

  @Test fun anUnconfirmedEntityNeverRaisesAnAlert() {
    val leak = e("binary_sensor.leak", "on", "moisture")
    val live = CcUiState(entities = mapOf(leak.id to leak), now = CcEngineRig.T0)
    assertTrue(live.alerts().any { it.entityId == leak.id })
    assertTrue(live.copy(unconfirmed = setOf(leak.id)).alerts().none { it.entityId == leak.id })
  }
}

class CcLiveUnchangedTest {
  @Test fun anUnchangedEntityKeepsTheSameLiveState() {
    val lamp = CcEntity("light.a", "on", mapOf("friendly_name" to "A"))
    val live = CcLive().snapshot(listOf(lamp))
    assertTrue(live.update(mapOf("light.a" to lamp.copy()), 0L) === live)
    val changed = live.update(mapOf("light.a" to lamp.copy(state = "off")), 0L)
    assertEquals("off", changed.raw.getValue("light.a").state)
  }
}
