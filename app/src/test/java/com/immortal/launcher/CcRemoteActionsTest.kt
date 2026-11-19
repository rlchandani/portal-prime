/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CcRemoteActionsTest {
  private val lamp = CcEntity("light.lamp", "on", mapOf("friendly_name" to "Lamp", "brightness" to 128,
      "supported_color_modes" to listOf("color_temp"), "min_color_temp_kelvin" to 2200, "max_color_temp_kelvin" to 6500))
  private val lock = CcEntity("lock.front", "locked", mapOf("friendly_name" to "Front door"))
  private val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"))
  private val thermo = CcEntity("climate.hall", "heat", mapOf("min_temp" to 7, "max_temp" to 30, "hvac_modes" to listOf("off", "heat")))
  private val panel = CcEntity("alarm_control_panel.home", "armed_away", mapOf("supported_features" to 3, "code_format" to "number"))
  private val carLock = CcEntity("lock.model_y_lock", "locked")
  private val state = CcUiState(entities = listOf(lamp, lock, garage, thermo, panel, carLock).associateBy { it.id },
      registry = CcRegistry(tesla = CcTeslaIds(name = "Y", car = mapOf(CcCarRole.LOCK to carLock.id, CcCarRole.BATTERY to "sensor.y_battery"))))

  private fun build(vararg kv: Pair<String, Any>) = CcRemoteActions.build(state, JSONObject(kv.toMap()))
  private fun call(vararg kv: Pair<String, Any>) = (build(*kv) as CcRemoteIntent.Call).call
  private fun invalid(vararg kv: Pair<String, Any>) = (build(*kv) as CcRemoteIntent.Invalid)

  @Test fun buildsThePortalsOwnCalls() {
    assertEquals(CcCalls.brightness(lamp, 40), call("entity" to lamp.id, "action" to "brightness", "value" to 40))
    assertEquals(CcCalls.lightPower(lamp, false), call("entity" to lamp.id, "action" to "power", "on" to false))
    assertEquals(CcCalls.unlock(lock), call("entity" to lock.id, "action" to "unlock"))
    assertEquals(CcCalls.coverClose(garage), call("entity" to garage.id, "action" to "close"))
    assertEquals(CcCalls.temperature(thermo, 21.0), call("entity" to thermo.id, "action" to "temperature", "value" to 21.0))
    assertEquals(CcCalls.alarm(panel, CcAlarmMode.DISARM, "1234"), call("entity" to panel.id, "action" to "alarm", "mode" to "disarm", "code" to "1234"))
  }

  @Test fun refusesOutOfRangeInsteadOfClamping() {
    assertEquals("bad_action", invalid("entity" to lamp.id, "action" to "brightness", "value" to 140).code)
    assertEquals("bad_action", invalid("entity" to lamp.id, "action" to "brightness", "value" to 40.5).code)
    assertEquals("bad_action", invalid("entity" to lamp.id, "action" to "kelvin", "value" to 9000).code)
    assertEquals("bad_action", invalid("entity" to thermo.id, "action" to "temperature", "value" to 45.0).code)
    assertEquals("bad_action", invalid("entity" to thermo.id, "action" to "hvac", "value" to "cool").code)
    assertEquals("bad_action", invalid("entity" to lamp.id, "action" to "power", "on" to "yes").code)
  }

  @Test fun actionMustSuitTheEntity() {
    assertEquals("bad_action", invalid("entity" to lamp.id, "action" to "unlock").code)
    assertEquals("bad_action", invalid("entity" to garage.id, "action" to "position", "value" to 50).code)
    assertEquals("bad_action", invalid("entity" to lock.id, "action" to "shell_command").code)
    assertEquals("bad_action", invalid("entity" to panel.id, "action" to "alarm", "mode" to "night").code) // not offered
    assertEquals("bad_action", invalid("entity" to panel.id, "action" to "alarm", "mode" to "disarm", "code" to "12;rm").code)
  }

  @Test fun onlyWatchedEntitiesAndNoCarBypass() {
    assertEquals(404, invalid("entity" to "lock.back", "action" to "unlock").status)
    assertEquals("bad_entity", invalid("entity" to "../api", "action" to "power", "on" to true).code)
    assertEquals("use_car_action", invalid("entity" to carLock.id, "action" to "unlock").code)
  }

  @Test fun carTilesComeFromTheTeslaScreen() {
    val c = build("action" to "car", "key" to "lock") as CcRemoteIntent.Call
    assertEquals(CcTeslaCalls.unlock(carLock), c.call)
    assertEquals(CcGuard.CONFIRM, c.call.guard)
    assertEquals("bad_action", invalid("action" to "car", "key" to "self_destruct").code)
  }

  @Test fun confirmKeyBindsEntityActionAndValue() {
    val a = build("entity" to lamp.id, "action" to "brightness", "value" to 40) as CcRemoteIntent.Call
    val b = build("entity" to lamp.id, "action" to "brightness", "value" to 41) as CcRemoteIntent.Call
    val code = build("entity" to panel.id, "action" to "alarm", "mode" to "disarm", "code" to "9999") as CcRemoteIntent.Call
    assertNotEquals(a.key, b.key)
    assertTrue("the alarm code is never part of the key", "9999" !in code.key)
  }
}
