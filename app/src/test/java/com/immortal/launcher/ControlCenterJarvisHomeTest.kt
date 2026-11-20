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

/** `hearthpane.home_control`: words → the Portal's own calls (resolver), then the Portal's own policy (routing). */
class ControlCenterJarvisHomeTest {
  private fun e(id: String, state: String, name: String, vararg attrs: Pair<String, Any?>) =
      CcEntity(id, state, mapOf("friendly_name" to name, *attrs))

  private val k1 = e("light.kitchen_ceiling", "on", "Kitchen ceiling", "brightness" to 200)
  private val k2 = e("light.kitchen_pendant", "on", "Pendant", "brightness" to 120)
  private val k3 = e("light.kitchen_strip", "off", "Under-cabinet")
  private val lamp = e("light.floor_lamp", "on", "Floor lamp")
  private val mia = e("light.mia", "on", "Mia nightlight")
  private val front = e("lock.front_door", "locked", "Front door")
  private val garage = e("cover.garage_door", "open", "Garage door", "device_class" to "garage")
  private val blinds = e("cover.living_blinds", "open", "Living blinds")
  private val valve = e("valve.main_water", "open", "Main water")
  private val thermo = e("climate.downstairs", "heat", "Downstairs", "min_temp" to 45, "max_temp" to 90, "temperature" to 68)
  private val night = e("scene.good_night", "scening", "Good night", "entity_id" to listOf("light.floor_lamp", "lock.front_door"))
  private val movie = e("scene.movie", "scening", "Movie time", "entity_id" to listOf("light.floor_lamp"))
  private val panel = e("alarm_control_panel.home", "disarmed", "Alarm", "supported_features" to 7)
  private val carLock = e("lock.model_y", "locked", "Model Y")

  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 10, 8), devices = listOf(
      CcPlaced(k1.id, 0.3f, 0.5f), CcPlaced(k2.id, 0.7f, 0.5f), CcPlaced(k3.id, 0.5f, 0.2f)))
  private val livingRoom = CcRoom("living_room", "Living room", CcRect(10, 0, 12, 8), devices = listOf(
      CcPlaced(lamp.id, 0.5f, 0.5f), CcPlaced(blinds.id, 0.5f, 0.1f), CcPlaced(thermo.id, 0.2f, 0.8f)))
  private val hall = CcRoom("hall", "Hall", CcRect(0, 8, 22, 4), devices = listOf(CcPlaced(front.id, 0.5f, 0.9f)))
  private val garageRoom = CcRoom("garage", "Garage", CcRect(22, 0, 8, 12), devices = listOf(CcPlaced(garage.id, 0.5f, 0.5f)))
  private val miaRoom = CcRoom("mia", "Mia's room", CcRect(0, 0, 10, 10), devices = listOf(CcPlaced(mia.id, 0.5f, 0.5f)))
  private val plan = CcPlan(listOf(CcFloor("ground", "Ground floor", listOf(kitchen, livingRoom, hall, garageRoom)),
      CcFloor("up", "Upstairs", listOf(miaRoom))))
  private val all = listOf(k1, k2, k3, lamp, mia, front, garage, blinds, valve, thermo, night, movie, panel, carLock)
  private val registry = CcRegistry(areas = listOf(CcHaArea("utility", "Utility", null)), entityArea = mapOf(valve.id to "utility"),
      tesla = CcTeslaIds(name = "Y", car = mapOf(CcCarRole.LOCK to carLock.id)))

  private fun state(settings: ControlCenterConfig.Settings = ControlCenterConfig.Settings(), vararg over: CcEntity) =
      CcUiState(entities = (all + over).associateBy { it.id }, registry = registry, plan = plan, settings = settings)

  private fun resolve(text: String, s: CcUiState = state()) = CcHomeCommand.resolve(s, CcHomeRequest(text))
  private fun act(text: String, s: CcUiState = state()): CcHomeIntent.Act = when (val r = resolve(text, s)) {
    is CcHomeIntent.Act -> r
    is CcHomeIntent.Unclear -> throw AssertionError("\"$text\" was unclear: ${r.reply}")
  }
  private fun unclear(text: String, s: CcUiState = state()) = (resolve(text, s) as CcHomeIntent.Unclear).reply

  // --- resolver ---------------------------------------------------------------------------

  @Test fun kitchenLightsOffTurnsOffOnlyTheKitchensLitLights() {
    val a = act("Turn off the kitchen lights")
    assertEquals(CcHomeVerb.OFF, a.verb)
    assertEquals(CcHomeWhat.LIGHTS, a.what)
    assertEquals(listOf(CcCalls.lightPower(k1, false), CcCalls.lightPower(k2, false)), a.items.map { it.call })
    assertEquals(1, a.already)
    assertEquals(listOf("kitchen"), a.roomIds)
    assertEquals("ground", a.floorId)
    assertEquals("the kitchen", a.place)
  }

  @Test fun theRoomAloneMeansItsLightsAndSpokenNamesMatch() {
    assertEquals(listOf(lamp.id), act("switch the living room off").items.map { it.entity.id })
    assertEquals(listOf(lamp.id), act("turn off the living").items.map { it.entity.id })
    assertEquals(listOf(mia.id), act("turn off the lights in Mia's room").items.map { it.entity.id })
  }

  @Test fun aNamedDeviceWinsOverItsRoom() {
    val a = act("turn on the floor lamp", state(ControlCenterConfig.Settings(), lamp.copy(state = "off")))
    assertEquals(listOf(CcCalls.lightPower(lamp.copy(state = "off"), true)), a.items.map { it.call })
    assertEquals("Floor lamp", a.device)
    assertEquals(listOf("living_room"), a.roomIds)
  }

  @Test fun floorsAndTheWholeHouse() {
    assertEquals(listOf(mia.id), act("turn off the lights upstairs").items.map { it.entity.id })
    assertEquals(setOf(k1.id, k2.id, lamp.id, mia.id), act("turn off all the lights").items.map { it.entity.id }.toSet())
    assertEquals("the house", act("turn off all the lights").place)
  }

  @Test fun locksGarageValveThermostatScenesAndAlarm() {
    assertEquals(listOf(CcCalls.unlock(front)), act("unlock the front door").items.map { it.call })
    assertEquals(1, act("lock the front door").already)
    assertEquals(listOf(CcCalls.coverClose(garage)), act("close the garage").items.map { it.call })
    assertEquals(listOf(CcCalls.coverClose(blinds)), act("close the living room blinds").items.map { it.call })
    assertEquals(listOf(CcCalls.valveClose(valve)), act("shut off the water").items.map { it.call })
    assertEquals(listOf(CcCalls.temperature(thermo, 70.0)), act("set the downstairs thermostat to 70").items.map { it.call })
    assertEquals(listOf(CcCalls.temperature(thermo, 72.0)), act("set the heating in the living room to 72 degrees").items.map { it.call })
    assertEquals(listOf(CcCalls.run(night)), act("good night").items.map { it.call })
    assertEquals(listOf(CcCalls.run(movie)), act("run movie time").items.map { it.call })
    assertEquals(listOf(CcCalls.alarm(panel, CcAlarmMode.NIGHT, null)), act("arm the alarm for the night").items.map { it.call })
    assertEquals(listOf(CcCalls.alarm(panel, CcAlarmMode.DISARM, null)), act("disarm the alarm").items.map { it.call })
  }

  @Test fun shortDeviceNamesLikeTvStillMatch() {
    val tv = e("media_player.tv", "off", "TV")
    val a = act("turn on the tv", state(ControlCenterConfig.Settings(), tv))
    assertEquals(listOf(CcCalls.mediaPower(tv, true)), a.items.map { it.call })
  }

  @Test fun dimmingIsBrightnessAndZeroIsOff() {
    assertEquals(listOf(CcCalls.brightness(k1, 30), CcCalls.brightness(k2, 30), CcCalls.brightness(k3, 30)),
        act("dim the kitchen to 30%").items.map { it.call })
    assertEquals(listOf(CcCalls.brightness(lamp, 30)), act("dim the floor lamp").items.map { it.call })
    assertEquals(listOf(CcCalls.lightPower(lamp, false)), act("set the floor lamp to 0 percent").items.map { it.call })
  }

  @Test fun unclearRequestsSayWhatIsMissingAndTouchNothing() {
    assertTrue(unclear("turn off the lights").contains("Which room"))
    assertTrue(unclear("turn off the lights").contains("Kitchen"))
    assertTrue(unclear("make me a sandwich").contains("I can"))
    assertTrue(unclear("set the downstairs thermostat to 120").contains("45° to 90°"))
    assertTrue(unclear("turn off the fans in the kitchen").contains("don't see any fans"))
    assertTrue(unclear("").contains("for example"))
  }

  @Test fun theCarIsNeverReachedAndIdsFromTheModelAreJustWords() {
    // The car's lock isn't a candidate, and an unknown name never widens to every lock in the house.
    assertTrue(unclear("unlock the model y").contains("Which room"))
    // An entity id is only words: it reaches the light by its name, never as an id.
    assertEquals(listOf(k1.id), act("turn off light.kitchen_ceiling").items.map { it.entity.id })
  }

  @Test fun normalizeKeepsWordsDigitsAndPercent() {
    assertEquals("turn off mias lights to 30%", CcHomeCommand.normalize("Turn OFF Mia’s lights, to 30%!"))
    assertEquals(listOf("mias room", "mias"), CcHomeCommand.aliases("Mia's room"))
    assertEquals(listOf("living room", "living"), CcHomeCommand.aliases("Living room"))
  }

  // --- policy routing ---------------------------------------------------------------------

  private fun route(text: String, settings: ControlCenterConfig.Settings = ControlCenterConfig.Settings(), seen: Set<String> = emptySet()) =
      CcHomeRoute.split(act(text, state(settings)).items, settings) { seen }

  @Test fun lightsRunConfirmFirstActionsNeverDo() {
    assertEquals(2, route("turn off the kitchen lights").run.size)
    for (text in listOf("unlock the front door", "close the garage", "shut off the water", "disarm the alarm")) {
      val r = route(text)
      assertTrue("$text must not run", r.run.isEmpty())
      assertEquals(text, 1, r.confirm.size)
    }
    // Locking never asks, and arming without a code is one tap on the Portal too.
    val unlocked = state(ControlCenterConfig.Settings(), front.copy(state = "unlocked"))
    assertEquals(1, CcHomeRoute.split(act("lock the front door", unlocked).items, ControlCenterConfig.Settings()) { emptySet() }.run.size)
    assertEquals(1, route("arm the alarm").run.size)
  }

  @Test fun theHouseholdsAsksDecideAsOnTheScreen() {
    assertEquals(1, route("unlock the front door", ControlCenterConfig.Settings(askUnlock = false)).run.size)
    assertEquals(1, route("close the garage", ControlCenterConfig.Settings(askGarage = false)).run.size)
    // A scene that locks things is security in disguise: it always asks.
    assertEquals(1, route("good night", ControlCenterConfig.Settings(askScenes = false)).confirm.size)
    assertEquals(1, route("run movie time").run.size)
  }

  @Test fun kidsModeRefusesWhatItHides() {
    val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)
    val r = route("close the garage", kids)
    assertTrue(r.run.isEmpty() && r.confirm.isEmpty())
    assertTrue(r.refused.single().kids)
    val a = act("close the garage", state(kids))
    assertEquals("That one needs a grown-up.", CcHomeRoute.reply(a, r, CcHomeRoute.Outcome(0, 0, 0), dryRun = false))
  }

  @Test fun repliesAreShortAndHonest() {
    val a = act("turn off the kitchen lights")
    val r = CcHomeRoute.split(a.items, ControlCenterConfig.Settings()) { emptySet() }
    assertEquals("Turned off 2 lights in the kitchen.", CcHomeRoute.reply(a, r, CcHomeRoute.Outcome(2, 0, 0), dryRun = false))
    assertEquals("Turned off 2 lights in the kitchen. (Dry run: nothing was sent to Home Assistant.)",
        CcHomeRoute.reply(a, r, CcHomeRoute.Outcome(2, 0, 0), dryRun = true))
    assertEquals("Turned off 2 lights in the kitchen. 1 didn't respond.", CcHomeRoute.reply(a, r, CcHomeRoute.Outcome(1, 1, 0), dryRun = false))
    val u = act("unlock the front door")
    val ur = CcHomeRoute.split(u.items, ControlCenterConfig.Settings()) { emptySet() }
    assertEquals("Unlocking the front door needs a tap to confirm. It's on the Portal's screen.",
        CcHomeRoute.reply(u, ur, CcHomeRoute.Outcome(0, 0, 0), dryRun = true))
    val l = act("lock the front door")
    assertEquals("The front door is already locked.",
        CcHomeRoute.reply(l, CcHomeRoute.split(l.items, ControlCenterConfig.Settings()) { emptySet() }, CcHomeRoute.Outcome(0, 0, 0), false))
  }

  @Test fun theCardShowsWhatChangedOnlyWhenSomethingRan() {
    val a = act("turn off the kitchen lights")
    val r = CcHomeRoute.split(a.items, ControlCenterConfig.Settings()) { emptySet() }
    assertEquals(CcHomeRoute.Card("Kitchen lights", "Off", false), CcHomeRoute.card(a, r))
    val u = act("unlock the front door")
    assertNull(CcHomeRoute.card(u, CcHomeRoute.split(u.items, ControlCenterConfig.Settings()) { emptySet() }))
    assertNull(CcHomeRoute.card(act("dim the floor lamp"), route("dim the floor lamp"))!!.on)
    assertFalse(CcHomeRoute.card(act("dim the floor lamp"), route("dim the floor lamp"))!!.state.isEmpty())
  }
}
