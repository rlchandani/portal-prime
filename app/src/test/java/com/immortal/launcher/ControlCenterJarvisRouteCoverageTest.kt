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

/** [CcHomeRoute]: voice policy (refuse, confirm, run) and every reply/card wording. */
class ControlCenterJarvisRouteCoverageTest {
  private fun e(id: String, state: String, name: String, vararg attrs: Pair<String, Any?>) =
      CcEntity(id, state, mapOf("friendly_name" to name, *attrs))

  private val lamp = e("light.floor_lamp", "on", "Floor lamp")
  private val front = e("lock.front_door", "locked", "Front door")
  private val garage = e("cover.garage_door", "closed", "Garage door", "device_class" to "garage")
  private val valve = e("valve.main_water", "open", "Main water")
  private val thermo = e("climate.hall", "heat", "Hall", "min_temp" to 7, "max_temp" to 30)
  private val panel = e("alarm_control_panel.home", "disarmed", "Alarm", "supported_features" to 7)
  private val full = ControlCenterConfig.Settings()
  private val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)

  private fun item(entity: CcEntity, call: CcCall, room: String? = null) = CcHomeItem(entity, call, room)

  private fun act(verb: CcHomeVerb, what: CcHomeWhat, items: List<CcHomeItem>, already: Int = 0, place: String? = "the hall",
                  device: String? = null, value: Double? = null) =
      CcHomeIntent.Act(verb, what, items, already, place, device, emptyList(), null, value)

  private val none = CcHomeRoute.Routed(emptyList(), emptyList(), emptyList())

  // --- split ------------------------------------------------------------------------------

  @Test fun kidsModeRefusesHiddenReadOnlyAndGuardedCalls() {
    val lockItem = item(front, CcCalls.lock(front))
    assertTrue(CcHomeRoute.split(listOf(lockItem), kids) { emptySet() }.refused.single().kids)

    val lockedThermostat = kids.copy(kidsLockThermostat = true)
    val heat = item(thermo, CcCalls.temperature(thermo, 21.0))
    assertEquals(1, CcHomeRoute.split(listOf(heat), lockedThermostat) { emptySet() }.refused.size)
    assertEquals(1, CcHomeRoute.split(listOf(heat), kids) { emptySet() }.run.size)

    // A guarded (car) call is never sent in kids mode, even for a kind kids mode shows.
    val shown = kids.copy(kidsHideLocks = false)
    val carStyle = item(front, CcCalls.lock(front).copy(guard = CcGuard.VEHICLE))
    val r = CcHomeRoute.split(listOf(carStyle), shown) { emptySet() }
    assertTrue(r.run.isEmpty() && r.confirm.isEmpty())
    assertTrue(r.refused.single().kids)
    assertEquals(1, CcHomeRoute.split(listOf(item(front, CcCalls.lock(front))), shown) { emptySet() }.run.size)
  }

  @Test fun scenesSeenIsReadOnlyForScenes() {
    var reads = 0
    val seen = { reads++; emptySet<String>() }
    CcHomeRoute.split(listOf(item(lamp, CcCalls.lightPower(lamp, false))), full, seen)
    assertEquals(0, reads)
    val members = (1..CcPolicy.WHOLE_HOUSE).map { "light.l$it" }
    val big = e("scene.evening", "x", "Evening", "entity_id" to members)
    val first = CcHomeRoute.split(listOf(item(big, CcCalls.run(big))), full, seen)
    assertEquals(1, reads)
    assertEquals(1, first.confirm.size)
    val again = CcHomeRoute.split(listOf(item(big, CcCalls.run(big))), full) { setOf(big.id) }
    assertEquals(1, again.run.size)
  }

  @Test fun disarmAndCodedArmingAlwaysConfirm() {
    val disarm = CcHomeRoute.split(listOf(item(panel, CcCalls.alarm(panel, CcAlarmMode.DISARM, null))), full) { emptySet() }
    assertEquals(1, disarm.confirm.size)
    val coded = panel.copy(attrs = panel.attrs + ("code_arm_required" to true) + ("code_format" to "number"))
    val arm = CcHomeRoute.split(listOf(item(coded, CcCalls.alarm(coded, CcAlarmMode.AWAY, null))), full) { emptySet() }
    assertEquals(1, arm.confirm.size)
  }

  @Test fun valveAskFollowsTheSetting() {
    val off = item(valve, CcCalls.valveClose(valve))
    assertEquals(1, CcHomeRoute.split(listOf(off), full) { emptySet() }.confirm.size)
    assertEquals(1, CcHomeRoute.split(listOf(off), full.copy(askValve = false)) { emptySet() }.run.size)
  }

  // --- reply ------------------------------------------------------------------------------

  @Test fun replyWhenEverythingFailedOrSeveralWaitToConfirm() {
    val a = act(CcHomeVerb.OFF, CcHomeWhat.LIGHTS, listOf(item(lamp, CcCalls.lightPower(lamp, false))))
    val r = CcHomeRoute.split(a.items, full) { emptySet() }
    assertEquals("Turned off 1 light in the hall. Home Assistant didn't do it.",
        CcHomeRoute.reply(a, r, CcHomeRoute.Outcome(0, 1, 0), dryRun = false))

    val back = e("lock.back_door", "locked", "Back door")
    val u = act(CcHomeVerb.UNLOCK, CcHomeWhat.LOCKS, listOf(item(front, CcCalls.unlock(front)), item(back, CcCalls.unlock(back))))
    val ur = CcHomeRoute.split(u.items, full) { emptySet() }
    assertEquals("Unlocking the front door needs a tap to confirm. It's on the Portal's screen. Do the other 1 on screen too.",
        CcHomeRoute.reply(u, ur, CcHomeRoute.Outcome(0, 0, 0), dryRun = true))
  }

  @Test fun replyWithNothingToDoOrAlreadyInPlace() {
    val empty = act(CcHomeVerb.OFF, CcHomeWhat.LIGHTS, emptyList())
    assertEquals("Nothing to do.", CcHomeRoute.reply(empty, none, CcHomeRoute.Outcome(0, 0, 0), dryRun = true))
    val cases = mapOf(CcHomeVerb.ON to "on", CcHomeVerb.OFF to "off", CcHomeVerb.BRIGHTNESS to "off", CcHomeVerb.LOCK to "locked",
        CcHomeVerb.UNLOCK to "unlocked", CcHomeVerb.OPEN to "open", CcHomeVerb.CLOSE to "closed", CcHomeVerb.TEMPERATURE to "set")
    cases.forEach { (verb, word) ->
      val a = act(verb, CcHomeWhat.LIGHTS, emptyList(), already = 2, place = "the kitchen")
      assertEquals("The lights in the kitchen are already $word.", CcHomeRoute.reply(a, none, CcHomeRoute.Outcome(0, 0, 0), false))
    }
  }

  @Test fun doneSentencesForEveryVerb() {
    val one = listOf(item(lamp, CcCalls.lightPower(lamp, true)))
    val r = CcHomeRoute.Routed(one, emptyList(), emptyList())
    fun said(verb: CcHomeVerb, device: String? = "Floor lamp", value: Double? = null) =
        CcHomeRoute.reply(act(verb, CcHomeWhat.LIGHTS, one, device = device, value = value), r, CcHomeRoute.Outcome(1, 0, 0), false)
    assertEquals("Turned on the floor lamp.", said(CcHomeVerb.ON))
    assertEquals("Locked the floor lamp.", said(CcHomeVerb.LOCK))
    assertEquals("Unlocked the floor lamp.", said(CcHomeVerb.UNLOCK))
    assertEquals("Opening the floor lamp.", said(CcHomeVerb.OPEN))
    assertEquals("Closing the floor lamp.", said(CcHomeVerb.CLOSE))
    assertEquals("Stopped the floor lamp.", said(CcHomeVerb.STOP))
    assertEquals("Set the floor lamp to 40%.", said(CcHomeVerb.BRIGHTNESS, value = 40.0))
    assertEquals("Set the floor lamp to 0%.", said(CcHomeVerb.BRIGHTNESS))
    assertEquals("Set the floor lamp to 21.5°.", said(CcHomeVerb.TEMPERATURE, value = 21.5))
    assertEquals("Set the floor lamp to 0°.", said(CcHomeVerb.TEMPERATURE))
    assertEquals("Started Floor lamp.", said(CcHomeVerb.RUN))
    assertEquals("Started the scene.", said(CcHomeVerb.RUN, device = null))
    assertEquals("Arming the floor lamp.", said(CcHomeVerb.ARM))
    assertEquals("Disarmed the floor lamp.", said(CcHomeVerb.DISARM))
  }

  // --- card -------------------------------------------------------------------------------

  @Test fun cardStatesForEveryVerb() {
    val r = CcHomeRoute.Routed(listOf(item(lamp, CcCalls.lightPower(lamp, true))), emptyList(), emptyList())
    val states = mapOf(CcHomeVerb.ON to "On", CcHomeVerb.OFF to "Off", CcHomeVerb.LOCK to "Locked", CcHomeVerb.UNLOCK to "Unlocked",
        CcHomeVerb.OPEN to "Opening", CcHomeVerb.CLOSE to "Closing", CcHomeVerb.STOP to "Stopped", CcHomeVerb.RUN to "Started",
        CcHomeVerb.ARM to "Arming", CcHomeVerb.DISARM to "Disarmed", CcHomeVerb.BRIGHTNESS to "0%", CcHomeVerb.TEMPERATURE to "0°")
    states.forEach { (verb, word) ->
      assertEquals(verb.name, word, CcHomeRoute.card(act(verb, CcHomeWhat.LIGHTS, r.run), r)!!.state)
    }
    assertEquals(true, CcHomeRoute.card(act(CcHomeVerb.ON, CcHomeWhat.LIGHTS, r.run), r)!!.on)
    assertNull(CcHomeRoute.card(act(CcHomeVerb.LOCK, CcHomeWhat.LIGHTS, r.run), r)!!.on)
    assertEquals("72°", CcHomeRoute.card(act(CcHomeVerb.TEMPERATURE, CcHomeWhat.CLIMATE, r.run, value = 72.0), r)!!.state)
  }

  @Test fun cardTitlesFromDevicePlaceOrHomeAndCapped() {
    val r = CcHomeRoute.Routed(listOf(item(lamp, CcCalls.lightPower(lamp, true))), emptyList(), emptyList())
    assertEquals("Home lights", CcHomeRoute.card(act(CcHomeVerb.ON, CcHomeWhat.LIGHTS, r.run, place = null), r)!!.title)
    assertEquals("Hall lights", CcHomeRoute.card(act(CcHomeVerb.ON, CcHomeWhat.LIGHTS, r.run), r)!!.title)
    val long = "L".repeat(80)
    assertEquals(60, CcHomeRoute.card(act(CcHomeVerb.ON, CcHomeWhat.LIGHTS, r.run, device = long), r)!!.title.length)
  }

  // --- asking -----------------------------------------------------------------------------

  @Test fun askingWordsForEveryConfirmKind() {
    assertEquals("Unlocking the front door", CcHomeRoute.asking(item(front, CcCalls.unlock(front))))
    assertEquals("Opening the garage door", CcHomeRoute.asking(item(garage, CcCalls.coverOpen(garage))))
    assertEquals("Closing the garage door", CcHomeRoute.asking(item(garage, CcCalls.coverClose(garage))))
    assertEquals("Shutting off the water", CcHomeRoute.asking(item(valve, CcCalls.valveClose(valve))))
    assertEquals("Turning the water back on", CcHomeRoute.asking(item(valve, CcCalls.valveOpen(valve))))
    val sw = e("switch.water_main", "off", "Water main")
    assertEquals("Shutting off the water", CcHomeRoute.asking(item(sw, CcCalls.power(sw, false))))
    assertEquals("Turning the water back on", CcHomeRoute.asking(item(sw, CcCalls.power(sw, true))))
    assertEquals("Disarming the alarm", CcHomeRoute.asking(item(panel, CcCalls.alarm(panel, CcAlarmMode.DISARM, null))))
    assertEquals("Arming the alarm", CcHomeRoute.asking(item(panel, CcCalls.alarm(panel, CcAlarmMode.HOME, null))))
    val script = e("script.leave", "off", "Leave home")
    assertEquals("Running Leave home", CcHomeRoute.asking(item(script, CcCalls.run(script))))
    assertEquals("Floor lamp off", CcHomeRoute.asking(item(lamp, CcCalls.lightPower(lamp, false))))
    assertEquals("Floor lamp", CcHomeRoute.asking(item(lamp, CcCall("light", "toggle", lamp.id))))
    val theGate = e("lock.gate", "locked", "The Gate")
    assertEquals("Unlocking the gate", CcHomeRoute.asking(item(theGate, CcCalls.unlock(theGate))))
  }

  @Test fun refusedOnlyStillSaysGrownUpEvenWithAlready() {
    val a = act(CcHomeVerb.UNLOCK, CcHomeWhat.LOCKS, listOf(item(front, CcCalls.unlock(front))), already = 1)
    val r = CcHomeRoute.split(a.items, kids) { emptySet() }
    assertFalse(r.refused.isEmpty())
    assertEquals("That one needs a grown-up.", CcHomeRoute.reply(a, r, CcHomeRoute.Outcome(0, 0, 0), dryRun = true))
  }
}
