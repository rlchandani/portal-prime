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

/** Car commands through [CcPolicy]: confirm-first for unlock/frunk/trunk/port, kids mode look-only, no undo. */
class ControlCenterTeslaPolicyTest {
  private val s = ControlCenterConfig.Settings()
  private val kids = s.copy(portalMode = ControlCenterConfig.MODE_KIDS)
  private val lock = CcEntity("lock.stargate_lock", "locked")
  private val frunk = CcEntity("cover.stargate_frunk", "closed")
  private val trunk = CcEntity("cover.stargate_trunk", "open")
  private val port = CcEntity("cover.stargate_charge_port_door", "closed")
  private val charge = CcEntity("switch.stargate_charge", "off")
  private val limit = CcEntity("number.stargate_charge_limit", "80")
  private val horn = CcEntity("button.stargate_honk_horn", "unknown")
  private val none: () -> Set<String> = { emptySet() }

  @Test fun confirmFirst_evenWhenTheHouseDoesntAskForUnlock() {
    val relaxed = s.copy(askUnlock = false)
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(lock, CcTeslaCalls.unlock(lock), relaxed))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(frunk, CcTeslaCalls.cover(frunk, true, "Frunk"), s))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(trunk, CcTeslaCalls.cover(trunk, true, "Trunk"), s))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(port, CcTeslaCalls.cover(port, true, "Charge port"), s))
    assertEquals("needs confirm first", CcPolicy.refusal(lock, CcTeslaCalls.unlock(lock), relaxed, false, none))
    assertNull(CcPolicy.refusal(lock, CcTeslaCalls.unlock(lock), s, true, none))
    // An unknown entity can't sneak a confirm-first call through either.
    assertNotNull(CcPolicy.refusal(null, CcTeslaCalls.press(horn, "x").copy(guard = CcGuard.CONFIRM), s, false, none))
  }

  @Test fun everydayCommands_goStraightThrough() {
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(lock, CcTeslaCalls.lock(lock), s))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(trunk, CcTeslaCalls.cover(trunk, false, "Trunk"), s))
    assertNull(CcPolicy.refusal(charge, CcTeslaCalls.charge(charge, true), s, false, none))
    assertNull(CcPolicy.refusal(limit, CcTeslaCalls.limit(limit, 85), s, false, none))
    assertNull(CcPolicy.refusal(horn, CcTeslaCalls.press(horn, "Horn honked"), s, false, none))
    assertNull(CcPolicy.refusal(null, CcTeslaCalls.press(horn, "Horn honked"), s, false, none))
  }

  @Test fun kidsMode_neverSendsCarCommands_evenConfirmed() {
    val unlocked = s.copy(portalMode = ControlCenterConfig.MODE_KIDS, kidsHideLocks = false)
    listOf(CcTeslaCalls.charge(charge, true), CcTeslaCalls.limit(limit, 90), CcTeslaCalls.press(horn, "Horn"),
        CcTeslaCalls.lock(lock), CcTeslaCalls.unlock(lock)).forEach { call ->
      assertEquals("kids mode · car is look only", CcPolicy.refusal(call.entityId?.let { CcEntity(it, "x") }, call, unlocked, true, none))
    }
    assertTrue(CcPolicy.refusal(charge, CcTeslaCalls.charge(charge, true), kids, false, none) != null)
    // The house's own switches are unaffected.
    assertNull(CcPolicy.refusal(CcEntity("switch.fan", "off"), CcCalls.power(CcEntity("switch.fan", "off"), true), kids, false, none))
  }

  @Test fun carCommands_haveNoUndo() {
    assertNull(CcCalls.undo(charge, CcTeslaCalls.charge(charge, true)))
    assertNull(CcCalls.undo(trunk, CcTeslaCalls.cover(trunk, false, "Trunk")))
    assertNotNull(CcCalls.undo(CcEntity("switch.fan", "off"), CcCalls.power(CcEntity("switch.fan", "off"), true)))
  }

  @Test fun builders_andConfirmText() {
    val l = CcTeslaCalls.limit(limit, 85)
    assertEquals("set_value", l.service); assertEquals(85, l.data["value"]); assertEquals("85", l.optimistic?.state)
    assertEquals("turn_on", CcTeslaCalls.climate(CcEntity("climate.c", "off"), true).service)
    assertEquals("heat_cool", CcTeslaCalls.climate(CcEntity("climate.c", "off"), true).optimistic?.hvacMode)
    assertEquals("Unlock Stargate?", CcTeslaCalls.confirm("Stargate", CcTeslaCalls.unlock(lock)).first)
    assertEquals("Open frunk", CcTeslaCalls.confirm("Stargate", CcTeslaCalls.cover(frunk, true, "Frunk")).third)
    assertEquals("Open trunk", CcTeslaCalls.confirm("Stargate", CcTeslaCalls.cover(trunk, true, "Trunk")).third)
    assertEquals("Open port", CcTeslaCalls.confirm("Stargate", CcTeslaCalls.cover(port, true, "Charge port")).third)
    assertEquals(85, CcTeslaCalls.stepLimit(80, true, 50, 100))
    assertEquals(100, CcTeslaCalls.stepLimit(98, true, 50, 100))
    assertEquals(50, CcTeslaCalls.stepLimit(52, false, 50, 100))
  }

  @Test fun actions_offerOnlyWhatTheCarHas_inTheRightDirection() {
    val ids = CcTeslaIds(car = mapOf(CcCarRole.CHARGE_SWITCH to charge.id, CcCarRole.LOCK to lock.id, CcCarRole.FRUNK to frunk.id,
        CcCarRole.TRUNK to trunk.id, CcCarRole.CHARGE_PORT to port.id, CcCarRole.HORN to horn.id, CcCarRole.BATTERY to "sensor.b"))
    val ents = listOf(charge, lock, frunk, trunk, port, horn, CcEntity("sensor.b", "70")).associateBy { it.id }
    val v = CcTeslaCar.view(ids, ents, CcPhase.LIVE)!!
    val actions = CcTeslaActions.list(ids, ents, v)
    assertEquals(listOf("charge", "lock", "horn", "frunk", "trunk", "port"), actions.map { it.key })
    assertEquals("Unlock", actions.first { it.key == "lock" }.label)
    assertEquals(CcGuard.CONFIRM, actions.first { it.key == "lock" }.call.guard)
    assertEquals("Close trunk", actions.first { it.key == "trunk" }.label)
    assertEquals(CcGuard.VEHICLE, actions.first { it.key == "trunk" }.call.guard)
    // Unplugged: no charge button; frunk open: no "open frunk".
    val unplugged = ids.copy(car = ids.car + (CcCarRole.CABLE to "binary_sensor.cable"))
    val ents2 = ents + ("binary_sensor.cable" to CcEntity("binary_sensor.cable", "off")) + (frunk.id to frunk.copy(state = "open"))
    val keys = CcTeslaActions.list(unplugged, ents2, CcTeslaCar.view(unplugged, ents2, CcPhase.LIVE)!!).map { it.key }
    assertTrue("charge" !in keys && "frunk" !in keys)
  }
}
