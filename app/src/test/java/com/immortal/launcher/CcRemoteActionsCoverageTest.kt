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
import org.junit.Test

/** [CcRemoteActions]: every phone action, the entity it suits, and its value checks. */
class CcRemoteActionsCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  private val plainLight = e("light.hall", "on", "friendly_name" to "Hall")
  private val ctLight = e("light.desk", "on", "supported_color_modes" to listOf("color_temp"),
      "min_color_temp_kelvin" to 2200, "max_color_temp_kelvin" to 6500)
  private val rgbLight = e("light.strip", "on", "supported_color_modes" to listOf("rgb"))
  private val sw = e("switch.kettle", "off")
  private val bool = e("input_boolean.guest", "off")
  private val fan = e("fan.bed", "off")
  private val lock = e("lock.front", "locked")
  private val blind = e("cover.blind", "open")
  private val garage = e("cover.garage", "open", "device_class" to "garage")
  private val valve = e("valve.main", "open")
  private val thermo = e("climate.hall", "heat", "min_temp" to 7, "max_temp" to 30, "hvac_modes" to listOf("off", "heat"))
  private val tv = e("media_player.tv", "playing")
  private val scene = e("scene.night", "x")
  private val script = e("script.leave", "off")
  private val panel = e("alarm_control_panel.home", "disarmed", "supported_features" to 3)
  private val cam = e("camera.porch", "idle")
  private val ringCam = e("camera.ring", "idle")
  private val ringButton = e("button.ring_snapshot", "unknown")
  private val sensor = e("sensor.temp", "21")

  private val limit = e("number.y_charge_limit", "80", "min" to 50, "max" to 100)
  private val carLock = e("lock.y", "locked")

  private val house = listOf(plainLight, ctLight, rgbLight, sw, bool, fan, lock, blind, garage, valve, thermo, tv, scene, script,
      panel, cam, ringCam, ringButton, sensor)

  private fun state(reg: CcRegistry? = CcRegistry(snapshotButtons = mapOf(ringCam.id to listOf(ringButton.id))),
                    vararg extra: CcEntity) =
      CcUiState(entities = (house + extra).associateBy { it.id }, registry = reg)

  private fun build(s: CcUiState, vararg kv: Pair<String, Any>) = CcRemoteActions.build(s, JSONObject(kv.toMap()))
  private fun build(vararg kv: Pair<String, Any>) = build(state(), *kv)
  private fun call(vararg kv: Pair<String, Any>) = (build(*kv) as CcRemoteIntent.Call).call
  private fun code(vararg kv: Pair<String, Any>) = (build(*kv) as CcRemoteIntent.Invalid).code

  @Test fun powerPicksTheBuilderByDomainAndRefusesOthers() {
    assertEquals(CcCalls.mediaPower(tv, false), call("entity" to tv.id, "action" to "power", "on" to false))
    assertEquals(CcCalls.power(sw, true), call("entity" to sw.id, "action" to "power", "on" to true))
    assertEquals(CcCalls.power(bool, true), call("entity" to bool.id, "action" to "power", "on" to true))
    assertEquals(CcCalls.power(fan, true), call("entity" to fan.id, "action" to "power", "on" to true))
    assertEquals("bad_action", code("entity" to lock.id, "action" to "power", "on" to false))
    assertEquals("bad_action", code("entity" to sw.id, "action" to "power"))
  }

  @Test fun lightValuesNeedALightThatCanShowThem() {
    assertEquals("bad_action", code("entity" to sw.id, "action" to "brightness", "value" to 40))
    assertEquals(CcCalls.kelvin(ctLight, 3000), call("entity" to ctLight.id, "action" to "kelvin", "value" to 3000))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "kelvin", "value" to 3000))
    assertEquals("bad_action", code("entity" to ctLight.id, "action" to "kelvin", "value" to 2000))
    assertEquals(CcCalls.color(rgbLight, 0xFF8800), call("entity" to rgbLight.id, "action" to "color", "value" to 0xFF8800))
    assertEquals("bad_action", code("entity" to rgbLight.id, "action" to "color", "value" to 0x1000000))
    assertEquals("bad_action", code("entity" to rgbLight.id, "action" to "color", "value" to -1))
    assertEquals("bad_action", code("entity" to ctLight.id, "action" to "color", "value" to 0xFF8800))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "brightness", "value" to "40"))
  }

  @Test fun fanSpeedOnlyForFans() {
    assertEquals(CcCalls.fanSpeed(fan, 60), call("entity" to fan.id, "action" to "fan", "value" to 60))
    assertEquals(CcCalls.power(fan, false), call("entity" to fan.id, "action" to "fan", "value" to 0))
    assertEquals("bad_action", code("entity" to fan.id, "action" to "fan", "value" to 101))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "fan", "value" to 50))
  }

  @Test fun locksCoversAndValves() {
    assertEquals(CcCalls.lock(lock), call("entity" to lock.id, "action" to "lock"))
    assertEquals("bad_action", code("entity" to blind.id, "action" to "lock"))
    assertEquals(CcCalls.coverOpen(blind), call("entity" to blind.id, "action" to "open"))
    assertEquals(CcCalls.valveOpen(valve), call("entity" to valve.id, "action" to "open"))
    assertEquals("bad_action", code("entity" to lock.id, "action" to "open"))
    assertEquals(CcCalls.valveClose(valve), call("entity" to valve.id, "action" to "close"))
    assertEquals("bad_action", code("entity" to lock.id, "action" to "close"))
    assertEquals(CcCalls.coverStop(garage), call("entity" to garage.id, "action" to "stop"))
    assertEquals("bad_action", code("entity" to valve.id, "action" to "stop"))
    assertEquals(CcCalls.coverPosition(blind, 50), call("entity" to blind.id, "action" to "position", "value" to 50))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "position", "value" to 50))
  }

  @Test fun climateTemperatureAndModes() {
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "temperature", "value" to 21.0))
    assertEquals(CcCalls.hvacMode(thermo, "off"), call("entity" to thermo.id, "action" to "hvac", "value" to "off"))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "hvac", "value" to "off"))
    assertEquals("bad_action", code("entity" to thermo.id, "action" to "temperature", "value" to 6.9))
    assertEquals(CcCalls.temperature(thermo, 30.0), call("entity" to thermo.id, "action" to "temperature", "value" to 30))
  }

  @Test fun mediaControlsOnlyForMediaPlayers() {
    assertEquals(CcCalls.mediaPlayPause(tv), call("entity" to tv.id, "action" to "play_pause"))
    assertEquals(CcCalls.mediaNext(tv), call("entity" to tv.id, "action" to "next"))
    assertEquals(CcCalls.mediaPrevious(tv), call("entity" to tv.id, "action" to "previous"))
    assertEquals(CcCalls.mediaVolume(tv, 0.5f), call("entity" to tv.id, "action" to "volume", "value" to 0.5))
    for (a in listOf("play_pause", "next", "previous")) assertEquals(a, "bad_action", code("entity" to plainLight.id, "action" to a))
    assertEquals("bad_action", code("entity" to tv.id, "action" to "volume", "value" to 1.5))
    assertEquals("bad_action", code("entity" to tv.id, "action" to "volume", "value" to -0.1))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "volume", "value" to 0.5))
  }

  @Test fun scenesAndScriptsRunNothingElseDoes() {
    assertEquals(CcCalls.run(scene), call("entity" to scene.id, "action" to "run"))
    assertEquals(CcCalls.run(script), call("entity" to script.id, "action" to "run"))
    assertEquals("bad_action", code("entity" to plainLight.id, "action" to "run"))
  }

  @Test fun alarmModesAndCodes() {
    assertEquals(CcCalls.alarm(panel, CcAlarmMode.AWAY, null), call("entity" to panel.id, "action" to "alarm", "mode" to "away"))
    assertEquals(CcCalls.alarm(panel, CcAlarmMode.HOME, "*12#"),
        call("entity" to panel.id, "action" to "alarm", "mode" to "home", "code" to "*12#"))
    assertEquals(CcCalls.alarm(panel, CcAlarmMode.DISARM, null),
        call("entity" to panel.id, "action" to "alarm", "mode" to "disarm", "code" to ""))
    assertEquals("bad_action", code("entity" to panel.id, "action" to "alarm", "mode" to "DISARM"))
    assertEquals("bad_action", code("entity" to panel.id, "action" to "alarm", "mode" to "vacation"))
    assertEquals("bad_action", code("entity" to panel.id, "action" to "alarm"))
    assertEquals("bad_action", code("entity" to panel.id, "action" to "alarm", "mode" to "disarm", "code" to "1".repeat(17)))
    assertEquals("bad_action", code("entity" to lock.id, "action" to "alarm", "mode" to "disarm"))
  }

  @Test fun cameraRefreshUsesTheSnapshotButtonElseUpdateEntity() {
    assertEquals(CcCall("homeassistant", "update_entity", cam.id, label = "Asked Home Assistant for a new picture"),
        call("entity" to cam.id, "action" to "camera_refresh"))
    assertEquals("button", call("entity" to ringCam.id, "action" to "camera_refresh").domain)
    assertEquals(ringButton.id, call("entity" to ringCam.id, "action" to "camera_refresh").entityId)
    assertEquals("bad_action", code("entity" to sensor.id, "action" to "camera_refresh"))
  }

  @Test fun entityIdsMustLookLikeHomeAssistantIds() {
    assertEquals("bad_entity", code("entity" to "Light.Hall", "action" to "power", "on" to true))
    assertEquals("bad_entity", code("action" to "power", "on" to true))
    assertEquals("bad_entity", code("entity" to "light.", "action" to "power", "on" to true))
    assertEquals("bad_entity", code("entity" to "a.b", "action" to "power", "on" to true))
    assertEquals("bad_action", code("entity" to sensor.id))
  }

  @Test fun noRegistryStillBuildsHouseCalls() {
    val s = state(reg = null)
    assertEquals(CcCalls.lock(lock), (build(s, "entity" to lock.id, "action" to "lock") as CcRemoteIntent.Call).call)
  }

  @Test fun theConfirmKeyBindsModeAndOnToo() {
    val away = build("entity" to panel.id, "action" to "alarm", "mode" to "away") as CcRemoteIntent.Call
    val home = build("entity" to panel.id, "action" to "alarm", "mode" to "home") as CcRemoteIntent.Call
    assertNotEquals(away.key, home.key)
    val on = build("entity" to sw.id, "action" to "power", "on" to true) as CcRemoteIntent.Call
    val off = build("entity" to sw.id, "action" to "power", "on" to false) as CcRemoteIntent.Call
    assertNotEquals(on.key, off.key)
    assertEquals("switch.kettle|power|||true", on.key)
  }

  // --- the car ----------------------------------------------------------------------------

  private fun carState(vararg roles: Pair<CcCarRole, CcEntity>) = CcUiState(
      entities = (house + roles.map { it.second }).associateBy { it.id },
      registry = CcRegistry(tesla = CcTeslaIds(name = "Y", car = roles.associate { it.first to it.second.id })))

  private fun invalid(s: CcUiState, vararg kv: Pair<String, Any>) = build(s, *kv) as CcRemoteIntent.Invalid

  @Test fun noCarMeansNoCarActions() {
    assertEquals(CcRemoteIntent.Invalid(404, "no_car"), invalid(state(reg = null), "action" to "car", "key" to "lock"))
    assertEquals(CcRemoteIntent.Invalid(404, "no_car"), invalid(state(), "action" to "car_limit", "value" to 80))
  }

  @Test fun chargeLimitWithinTheCarsOwnRange() {
    val s = carState(CcCarRole.LOCK to carLock, CcCarRole.CHARGE_LIMIT to limit)
    val ok = build(s, "action" to "car_limit", "value" to 90) as CcRemoteIntent.Call
    assertEquals(CcTeslaCalls.limit(limit, 90), ok.call)
    assertEquals("car_limit|90", ok.key)
    assertEquals(limit, ok.entity)
    assertEquals("bad_value", invalid(s, "action" to "car_limit", "value" to 40).code)
    assertEquals("bad_value", invalid(s, "action" to "car_limit", "value" to 101).code)
    assertEquals("bad_value", invalid(s, "action" to "car_limit", "value" to 80.5).code)
    assertEquals("bad_value", invalid(s, "action" to "car_limit").code)
  }

  @Test fun chargeLimitNeedsALiveLimitEntity() {
    assertEquals("no_limit", invalid(carState(CcCarRole.LOCK to carLock), "action" to "car_limit", "value" to 80).code)
    val gone = carState(CcCarRole.LOCK to carLock, CcCarRole.CHARGE_LIMIT to limit.copy(state = "unavailable"))
    assertEquals("no_limit", invalid(gone, "action" to "car_limit", "value" to 80).code)
  }

  @Test fun carTileKeysAreBoundIntoTheConfirmKey() {
    val s = carState(CcCarRole.LOCK to carLock)
    val c = build(s, "action" to "car", "key" to "lock") as CcRemoteIntent.Call
    assertEquals("car|lock|${c.call.service}", c.key)
    assertEquals(carLock, c.entity)
    assertEquals("bad_action", invalid(s, "action" to "car").code)
  }
}
