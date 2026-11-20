/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CcPhonePolicy]: the phone's asks ignore kids mode but keep the household's, and its confirm wording. */
class CcPhonePolicyCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  private val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS, kidsLockThermostat = true)
  private val lock = e("lock.front", "locked", "friendly_name" to "Front door")
  private val garage = e("cover.garage", "open", "friendly_name" to "Garage", "device_class" to "garage")
  private val valve = e("valve.main", "open", "friendly_name" to "Main water")
  private val panel = e("alarm_control_panel.home", "armed_away", "supported_features" to 3)
  private val codedPanel = panel.copy(attrs = panel.attrs + ("code_format" to "number"))
  private val thermo = e("climate.hall", "heat", "min_temp" to 7, "max_temp" to 30)

  @Test fun thePhoneNeverRunsInKidsModeButKeepsTheAsks() {
    val phone = CcPhonePolicy.settings(kids.copy(askUnlock = false))
    assertEquals(ControlCenterConfig.MODE_FULL, phone.portalMode)
    assertEquals(false, phone.askUnlock)
    assertEquals(true, phone.kidsLockThermostat)
  }

  @Test fun askAndRefusalUseTheFullModeSettings() {
    var reads = 0
    val seen = { reads++; emptySet<String>() }
    assertEquals(CcPolicy.Ask.CONFIRM, CcPhonePolicy.ask(lock, CcCalls.unlock(lock), kids, seen))
    assertEquals(0, reads)
    // Kids mode would hide the lock and lock the thermostat; neither applies to the phone.
    assertEquals("needs confirm first", CcPhonePolicy.refusal(lock, CcCalls.unlock(lock), kids, false, seen))
    assertNull(CcPhonePolicy.refusal(lock, CcCalls.unlock(lock), kids, true, seen))
    assertNull(CcPhonePolicy.refusal(thermo, CcCalls.temperature(thermo, 20.0), kids, false, seen))
    assertEquals("needs camera_confirm first", CcPhonePolicy.refusal(garage, CcCalls.coverClose(garage), kids, false, seen))
    assertEquals("needs pin first", CcPhonePolicy.refusal(panel, CcCalls.alarm(panel, CcAlarmMode.DISARM, null), kids, false, seen))
    assertEquals(0, reads)

    val members = (1..CcPolicy.WHOLE_HOUSE).map { "light.l$it" }
    val evening = e("scene.evening", "x", "entity_id" to members)
    assertEquals(CcPolicy.Ask.CONFIRM, CcPhonePolicy.ask(evening, CcCalls.run(evening), kids, seen))
    assertEquals(1, reads)
    assertEquals(CcPolicy.Ask.NONE, CcPhonePolicy.ask(evening, CcCalls.run(evening), kids) { setOf(evening.id) })
  }

  @Test fun carConfirmsUseTheCarsWords() {
    val carLock = e("lock.y", "locked")
    val call = CcTeslaCalls.unlock(carLock)
    assertEquals(CcTeslaCalls.confirm("Y", call), CcPhonePolicy.prompt(carLock, call, CcPolicy.Ask.CONFIRM, "Y"))
  }

  @Test fun alarmPinPrompts() {
    assertEquals(Triple("Disarmed?", "Enter the alarm code.", "Confirm"),
        CcPhonePolicy.prompt(codedPanel, CcCalls.alarm(codedPanel, CcAlarmMode.DISARM, null), CcPolicy.Ask.PIN, "Y"))
    assertEquals(Triple("Armed · Away?", "Confirm it's you.", "Confirm"),
        CcPhonePolicy.prompt(panel, CcCalls.alarm(panel, CcAlarmMode.AWAY, null), CcPolicy.Ask.PIN, "Y"))
    assertEquals(Triple("Change the alarm?", "Confirm it's you.", "Confirm"),
        CcPhonePolicy.prompt(panel, CcCall("alarm_control_panel", "alarm_trigger", panel.id), CcPolicy.Ask.PIN, "Y"))
  }

  @Test fun householdPrompts() {
    assertEquals(Triple("Unlock Front door?", "Anyone at the door can come in once it's unlocked.", "Unlock"),
        CcPhonePolicy.prompt(lock, CcCalls.unlock(lock), CcPolicy.Ask.CONFIRM, "Y"))
    assertEquals(Triple("Close Garage?", "Check the doorway is clear.", "Close"),
        CcPhonePolicy.prompt(garage, CcCalls.coverClose(garage), CcPolicy.Ask.CAMERA_CONFIRM, "Y"))
    assertEquals(Triple("Open Garage?", "Check the doorway is clear.", "Open"),
        CcPhonePolicy.prompt(garage, CcCalls.coverOpen(garage), CcPolicy.Ask.CAMERA_CONFIRM, "Y"))
    assertEquals(Triple("Shut off Main water?", "Stops all taps until it's turned back on.", "Shut off"),
        CcPhonePolicy.prompt(valve, CcCalls.valveClose(valve), CcPolicy.Ask.CONFIRM, "Y"))
    assertEquals(Triple("Turn Main water back on?", "Water flows again.", "Turn on"),
        CcPhonePolicy.prompt(valve, CcCalls.valveOpen(valve), CcPolicy.Ask.CONFIRM, "Y"))
  }

  @Test fun scenePromptsSayWhyTheyAsk() {
    val secure = e("script.leave", "off", "friendly_name" to "Lock up")
    assertEquals(Triple("Run Lock up?", "It changes locks, doors or the alarm.", "Run"),
        CcPhonePolicy.prompt(secure, CcCalls.run(secure), CcPolicy.Ask.CONFIRM, "Y"))
    val big = e("scene.evening", "x", "friendly_name" to "Evening", "entity_id" to listOf("light.a", "light.b"))
    assertEquals(Triple("Run Evening?", "It changes 2 things around the house.", "Run"),
        CcPhonePolicy.prompt(big, CcCalls.run(big), CcPolicy.Ask.CONFIRM, "Y"))
  }

  @Test fun anythingElseFallsBackToTheLabelOrName() {
    val sw = e("switch.pump", "on", "friendly_name" to "Pump")
    assertEquals(Triple("Pump off?", "Confirm it's you.", "Confirm"),
        CcPhonePolicy.prompt(sw, CcCalls.power(sw, false), CcPolicy.Ask.CONFIRM, "Y"))
    assertEquals(Triple("Pump?", "Confirm it's you.", "Confirm"),
        CcPhonePolicy.prompt(sw, CcCall("switch", "toggle", sw.id), CcPolicy.Ask.CONFIRM, "Y"))
  }
}
