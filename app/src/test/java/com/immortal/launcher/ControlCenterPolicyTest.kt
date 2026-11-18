/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterPolicyTest {
  private val s = ControlCenterConfig.Settings()
  private val kids = s.copy(portalMode = ControlCenterConfig.MODE_KIDS)
  private val lock = CcEntity("lock.front", "locked", mapOf("friendly_name" to "Front door"))
  private val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"))
  private val panel = CcEntity("alarm_control_panel.home", "armed_night", mapOf("supported_features" to 7, "code_format" to "number"))

  @Test fun ask_followsDecisions() {
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(lock, CcCalls.unlock(lock), s))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(lock, CcCalls.lock(lock), s))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(lock, CcCalls.unlock(lock), s.copy(askUnlock = false)))
    assertEquals(CcPolicy.Ask.CAMERA_CONFIRM, CcPolicy.ask(garage, CcCalls.coverClose(garage), s))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(garage, CcCalls.coverStop(garage), s))
    assertEquals(CcPolicy.Ask.PIN, CcPolicy.ask(panel, CcCalls.alarm(panel, CcAlarmMode.DISARM, null), s))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(panel, CcCalls.alarm(panel, CcAlarmMode.AWAY, null), s))
    val valve = CcEntity("valve.main", "open")
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(valve, CcCalls.valveClose(valve), s))
    val house = CcEntity("scene.good_night", "x", mapOf("entity_id" to (1..8).map { "light.l$it" }))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(house, CcCalls.run(house), s))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(house, CcCalls.run(house), s, setOf(house.id)))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(CcEntity("light.a", "on"), CcCalls.brightness(CcEntity("light.a", "on"), 5), s))
  }

  @Test fun securityScripts_alwaysAsk() {
    val arm = CcEntity("script.ring_arm_away", "off", mapOf("friendly_name" to "[Ring]: Arm Away"))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(arm, CcCalls.run(arm), s.copy(askScenes = false)))
    val warm = CcEntity("scene.warm_white", "x", mapOf("friendly_name" to "Warm farm lights"))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(warm, CcCalls.run(warm), s))
    val unlocking = CcEntity("scene.welcome", "x", mapOf("entity_id" to listOf("light.a", "lock.front")))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(unlocking, CcCalls.run(unlocking), s))
    assertFalse(CcPolicy.sensitive(CcEntity("script.all_lights_off", "off", mapOf("friendly_name" to "[All Light]: Turn Off"))))
  }

  @Test fun kids_hidesSecurityThingsOnly() {
    assertTrue(CcPolicy.hidden(lock, kids))
    assertTrue(CcPolicy.hidden(garage, kids))
    assertTrue(CcPolicy.hidden(panel, kids))
    assertTrue(CcPolicy.hidden(CcEntity("camera.door", "idle"), kids))
    assertTrue(CcPolicy.hidden(CcEntity("switch.main_water_valve", "on"), kids))
    assertFalse(CcPolicy.hidden(CcEntity("light.a", "on"), kids))
    assertFalse(CcPolicy.hidden(lock, s))
    assertFalse(CcPolicy.readOnly(CcEntity("climate.c", "heat"), kids))
    assertTrue(CcPolicy.readOnly(CcEntity("climate.c", "heat"), kids.copy(kidsLockThermostat = true)))
    assertTrue(CcPolicy.screenHidden(CcScreen.CAMERAS, kids))
    assertTrue(CcPolicy.screenHidden(CcScreen.EDIT, kids))
    assertFalse(CcPolicy.screenHidden(CcScreen.ENERGY, kids))
    assertFalse(CcPolicy.screenHidden(CcScreen.SECURITY, s))
    assertFalse(CcPolicy.hidden(lock, kids.copy(kidsHideLocks = false)))
  }

  @Test fun alarmModes_fromSupportedFeatures() {
    assertEquals(listOf(CcAlarmMode.DISARM, CcAlarmMode.HOME, CcAlarmMode.AWAY, CcAlarmMode.NIGHT), CcAlarmMode.offered(panel))
    assertEquals(listOf(CcAlarmMode.DISARM, CcAlarmMode.AWAY), CcAlarmMode.offered(panel.copy(attrs = mapOf("supported_features" to 2))))
    assertEquals("1234", CcCalls.alarm(panel, CcAlarmMode.DISARM, "1234").data["code"])
    assertTrue(CcCalls.alarm(panel, CcAlarmMode.HOME, "").data.isEmpty())
  }

  @Test fun calls_buildServiceData() {
    val light = CcEntity("light.k", "off", mapOf("min_color_temp_kelvin" to 2200, "max_color_temp_kelvin" to 6500))
    assertEquals("turn_off", CcCalls.brightness(light, 0).service)
    assertEquals(listOf(255, 0, 16), CcCalls.color(light, 0xFF0010).data["rgb_color"])
    assertEquals(2200, CcCalls.kelvin(light, 1000).data["color_temp_kelvin"])
    val climate = CcEntity("climate.d", "heat", mapOf("target_temp_step" to 0.5, "min_temp" to 10, "max_temp" to 30))
    assertEquals(21.5, CcCalls.temperature(climate, 21.4).data["temperature"])
    assertEquals(30.0, CcCalls.temperature(climate, 99.0).data["temperature"])
    val fahrenheit = CcEntity("climate.f", "cool", mapOf("temperature" to 86))
    assertEquals(87.0, CcCalls.temperature(fahrenheit, 87.0).data["temperature"])
    assertEquals(95.0, CcCalls.temperature(fahrenheit, 120.0).data["temperature"])
    assertEquals("set_hvac_mode", CcCalls.hvacMode(climate, "off").service)
    assertEquals(0.42, CcCalls.mediaVolume(CcEntity("media_player.tv", "playing"), 0.421f).data["volume_level"])
    assertEquals("paused", CcCalls.mediaPlayPause(CcEntity("media_player.tv", "playing")).optimistic!!.state)
    assertEquals("turn_off", CcCalls.fanSpeed(CcEntity("fan.f", "on"), 0).service)
    assertEquals("set_percentage", CcCalls.fanSpeed(CcEntity("fan.f", "on"), 40).service)
    assertEquals("switch", CcCalls.power(CcEntity("switch.s", "off"), true).domain)
    assertEquals("light", CcCalls.power(CcEntity("light.s", "off"), true).domain)
    assertEquals(55, CcCalls.coverPosition(CcEntity("cover.b", "open"), 55).data["position"])
    assertNull(CcCalls.snapshot(emptyList()))
    assertEquals(listOf("light.a"), CcCalls.snapshot(listOf("light.a"))!!.data["snapshot_entities"])
    assertEquals("scene.${CcCalls.UNDO_SCENE}", CcCalls.undoScene().entityId)
    assertEquals("script", CcCalls.run(CcEntity("script.x", "off")).domain)
  }

  @Test fun undo_restoresPreviousValues() {
    val dim = CcEntity("light.k", "on", mapOf("brightness" to 128))
    assertEquals(50, CcCalls.undo(dim, CcCalls.brightness(dim, 90))!!.data["brightness_pct"])
    assertEquals("turn_off", CcCalls.undo(dim.copy(state = "off"), CcCalls.lightPower(dim, true))!!.service)
    assertNull(CcCalls.undo(CcEntity("lock.f", "locked"), CcCalls.unlock(CcEntity("lock.f", "locked"))))
    assertNull(CcCalls.undo(CcEntity("cover.g", "closed", mapOf("device_class" to "garage")), CcCalls.coverOpen(CcEntity("cover.g", "closed"))))
    val blind = CcEntity("cover.b", "open", mapOf("current_position" to 30))
    assertEquals(30, CcCalls.undo(blind, CcCalls.coverPosition(blind, 80))!!.data["position"])
    val c = CcEntity("climate.c", "heat", mapOf("temperature" to 20.0))
    assertEquals(20.0, CcCalls.undo(c, CcCalls.temperature(c, 22.0))!!.data["temperature"])
    assertEquals("heat", CcCalls.undo(c, CcCalls.hvacMode(c, "off"))!!.data["hvac_mode"])
    val tv = CcEntity("media_player.tv", "on", mapOf("volume_level" to 0.3))
    assertNotNull(CcCalls.undo(tv, CcCalls.mediaVolume(tv, 0.6f)))
    assertNull(CcCalls.undo(tv, CcCalls.mediaNext(tv)))
    assertEquals("Undone", CcCalls.undo(dim, CcCalls.brightness(dim, 90))!!.label)
    val fan = CcEntity("fan.f", "on", mapOf("percentage" to 33))
    assertEquals(33, CcCalls.undo(fan, CcCalls.fanSpeed(fan, 66))!!.data["percentage"])
  }

  @Test fun optimistic_appliesAndMatches() {
    val e = CcEntity("light.k", "off")
    val o = CcOptimistic(state = "on", brightnessPct = 40, rgb = 0x00FF00)
    val shown = o.applyTo(e)
    assertEquals(40, shown.brightnessPct); assertEquals(0x00FF00, shown.rgb)
    assertTrue(o.matches(shown))
    assertFalse(o.matches(e))
    assertEquals("cool", CcOptimistic(hvacMode = "cool").applyTo(CcEntity("climate.c", "heat")).state)
  }

  @Test fun pin_rules() {
    assertTrue(CcPin.valid("2580")); assertTrue(CcPin.valid("12345678"))
    assertFalse(CcPin.valid("123")); assertFalse(CcPin.valid("12a4")); assertFalse(CcPin.valid("123456789"))
    assertEquals(CcPin.hash("s", "1234"), CcPin.hash("s", "1234"))
    assertFalse(CcPin.hash("s", "1234") == CcPin.hash("t", "1234"))
  }

  @Test fun settings_modes() {
    assertTrue(kids.kids); assertFalse(s.kids)
    assertTrue(s.copy(portalMode = ControlCenterConfig.MODE_BANNERS).bannersOnly)
  }
}
