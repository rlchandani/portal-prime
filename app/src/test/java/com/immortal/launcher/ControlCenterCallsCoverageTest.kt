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

/** [CcPolicy] (ask / refusal / kids hiding), [CcCalls.undo] and [CcOptimistic] edge cases. */
class ControlCenterCallsCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))
  private val full = ControlCenterConfig.Settings()
  private val kids = full.copy(portalMode = ControlCenterConfig.MODE_KIDS)
  private val never = { throw AssertionError("scenesSeen read for a non-scene call") }

  // --- the one send gate --------------------------------------------------------------------

  @Test fun unknownEntitiesOnlyGetSafeOrConfirmedCalls() {
    val light = CcCall("light", "turn_on", "light.unknown")
    assertNull(CcPolicy.refusal(null, light, full, confirmed = false, never))
    for (domain in listOf("lock", "cover", "valve", "alarm_control_panel", "scene", "script")) {
      val c = CcCall(domain, "x", "$domain.unknown")
      assertEquals(domain, "unknown $domain entity", CcPolicy.refusal(null, c, full, false, never))
      assertNull(domain, CcPolicy.refusal(null, c, full, true, never))
    }
    val carConfirm = CcCall("button", "press", "button.y", guard = CcGuard.CONFIRM)
    assertEquals("unknown button entity", CcPolicy.refusal(null, carConfirm, full, false, never))
    assertEquals("kids mode · car is look only", CcPolicy.refusal(null, carConfirm, kids, true, never))
  }

  @Test fun hiddenAndReadOnlyNeverSendEvenConfirmed() {
    val lock = e("lock.front", "locked")
    assertEquals("hidden on this Portal", CcPolicy.refusal(lock, CcCalls.unlock(lock), kids, true, never))
    val thermo = e("climate.hall", "heat")
    val locked = kids.copy(kidsLockThermostat = true)
    assertEquals("read-only on this Portal", CcPolicy.refusal(thermo, CcCalls.hvacMode(thermo, "off"), locked, true, never))
    assertNull(CcPolicy.refusal(thermo, CcCalls.hvacMode(thermo, "off"), kids, false, never))
    assertFalse(CcPolicy.readOnly(thermo, full.copy(kidsLockThermostat = true)))
  }

  @Test fun scenesSeenIsReadForSceneCallsOnly() {
    var reads = 0
    val members = (1..CcPolicy.WHOLE_HOUSE).map { "light.l$it" }
    val scene = e("scene.evening", "x", "entity_id" to members)
    assertEquals("needs confirm first", CcPolicy.refusal(scene, CcCalls.run(scene), full, false) { reads++; emptySet() })
    assertEquals(1, reads)
    assertNull(CcPolicy.refusal(scene, CcCalls.run(scene), full.copy(askScenes = false), false) { emptySet() })
    val small = e("scene.small", "x", "entity_id" to members.take(CcPolicy.WHOLE_HOUSE - 1))
    assertNull(CcPolicy.refusal(small, CcCalls.run(small), full, false) { emptySet() })
    // A script with many members is not a "whole-house scene" preview.
    val script = e("script.evening", "off", "entity_id" to members)
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(script, CcCalls.run(script), full))
  }

  // --- asks ---------------------------------------------------------------------------------

  @Test fun asksFollowEachHouseholdSetting() {
    val lock = e("lock.front", "unlocked")
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(lock, CcCalls.lock(lock), full))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(lock, CcCalls.unlock(lock), full.copy(askUnlock = false)))
    val garage = e("cover.garage", "closed", "device_class" to "garage")
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(garage, CcCalls.coverStop(garage), full))
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(garage, CcCalls.coverOpen(garage), full.copy(askGarage = false)))
    val blind = e("cover.blind", "closed")
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(blind, CcCalls.coverOpen(blind), full))
    val valve = e("valve.main", "open")
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(valve, CcCalls.valveClose(valve), full.copy(askValve = false)))
  }

  @Test fun armingNeedsAPinOnlyWhenACodeIsRequiredAndHasAFormat() {
    val p = e("alarm_control_panel.a", "disarmed", "supported_features" to 7)
    val arm = { x: CcEntity -> CcPolicy.ask(x, CcCalls.alarm(x, CcAlarmMode.AWAY, null), full) }
    assertEquals(CcPolicy.Ask.NONE, arm(p))
    assertEquals(CcPolicy.Ask.NONE, arm(p.copy(attrs = p.attrs + ("code_arm_required" to true))))
    assertEquals(CcPolicy.Ask.NONE, arm(p.copy(attrs = p.attrs + ("code_format" to "number"))))
    assertEquals(CcPolicy.Ask.NONE, arm(p.copy(attrs = p.attrs + ("code_arm_required" to "true") + ("code_format" to "number"))))
    assertEquals(CcPolicy.Ask.PIN, arm(p.copy(attrs = p.attrs + ("code_arm_required" to true) + ("code_format" to "number"))))
  }

  @Test fun sensitiveScenesByWordOrMember() {
    assertTrue(CcPolicy.sensitive(e("scene.x", "x", "friendly_name" to "Garage time")))
    assertTrue(CcPolicy.sensitive(e("script.away_mode", "off")))
    assertTrue(CcPolicy.sensitive(e("scene.x", "x", "entity_id" to listOf("cover.garage_door"))))
    assertTrue(CcPolicy.sensitive(e("scene.x", "x", "entity_id" to listOf("valve.main"))))
    assertFalse(CcPolicy.sensitive(e("scene.x", "x", "entity_id" to listOf("cover.blinds"))))
    assertFalse("words, not substrings", CcPolicy.sensitive(e("scene.farm_charm", "x", "friendly_name" to "Charming")))
  }

  // --- kids hiding --------------------------------------------------------------------------

  @Test fun kidsHidingPerKindAndOnlyInKidsMode() {
    assertTrue(CcPolicy.hiddenKind(CcKind.CAMERA, null, kids))
    assertFalse(CcPolicy.hiddenKind(CcKind.CAMERA, null, kids.copy(kidsHideCameras = false)))
    assertTrue(CcPolicy.hiddenKind(CcKind.VALVE, null, kids))
    assertTrue(CcPolicy.hiddenKind(CcKind.SWITCH, e("switch.water_main", "on"), kids))
    assertFalse(CcPolicy.hiddenKind(CcKind.SWITCH, e("switch.water_main", "on"), kids.copy(kidsHideValve = false)))
    assertFalse(CcPolicy.hiddenKind(CcKind.SWITCH, e("input_boolean.valve_mode", "on"), kids))
    assertFalse(CcPolicy.hiddenKind(CcKind.SWITCH, null, kids))
    assertFalse(CcPolicy.hidden(e("lock.a", "locked"), full))
  }

  @Test fun screensKidsModeRemoves() {
    assertTrue(CcPolicy.screenHidden(CcScreen.CAMERAS, kids))
    assertTrue(CcPolicy.screenHidden(CcScreen.SECURITY, kids))
    assertFalse(CcPolicy.screenHidden(CcScreen.SECURITY, kids.copy(kidsHideSecurity = false)))
    assertTrue(CcPolicy.screenHidden(CcScreen.EDIT, kids))
    assertTrue(CcPolicy.screenHidden(CcScreen.SETTINGS, kids))
    assertFalse(CcPolicy.screenHidden(CcScreen.MAP, kids))
    assertFalse(CcPolicy.screenHidden(CcScreen.SETTINGS, full))
  }

  // --- undo ---------------------------------------------------------------------------------

  @Test fun undoIsNullWhereItIsntSafe() {
    val garage = e("cover.garage", "closed", "device_class" to "garage", "current_position" to 0)
    assertNull(CcCalls.undo(garage, CcCalls.coverOpen(garage)))
    val car = e("switch.y_charge", "off")
    assertNull(CcCalls.undo(car, CcTeslaCalls.charge(car, true)))
    assertNull(CcCalls.undo(e("lock.a", "locked"), CcCalls.unlock(e("lock.a", "locked"))))
    val tv = e("media_player.tv", "playing")
    assertNull(CcCalls.undo(tv, CcCalls.mediaNext(tv)))
    assertNull("no volume known", CcCalls.undo(tv, CcCalls.mediaVolume(tv, 0.3f)))
    val blindNoPos = e("cover.b", "opening")
    assertNull(CcCalls.undo(blindNoPos, CcCalls.coverPosition(blindNoPos, 10)))
    val fanNoPct = e("fan.f", "on")
    assertNull(CcCalls.undo(fanNoPct, CcCalls.fanSpeed(fanNoPct, 30)))
    val thermoNoTarget = e("climate.c", "heat")
    assertNull(CcCalls.undo(thermoNoTarget, CcCalls.temperature(thermoNoTarget, 20.0)))
  }

  @Test fun undoPutsBackWhatWasThere() {
    val lamp = e("light.l", "on")
    assertEquals(CcCalls.lightPower(lamp, true).copy(label = "Undone"), CcCalls.undo(lamp, CcCalls.lightPower(lamp, false)))
    val off = e("light.l", "off")
    assertEquals("turn_off", CcCalls.undo(off, CcCalls.lightPower(off, true))!!.service)
    val tv = e("media_player.tv", "on", "volume_level" to 0.4)
    assertEquals(CcCalls.mediaVolume(tv, 0.4f).copy(label = "Undone"), CcCalls.undo(tv, CcCalls.mediaVolume(tv, 0.9f)))
    val thermo = e("climate.c", "cool")
    assertEquals(CcCalls.hvacMode(thermo, "cool").copy(label = "Undone"), CcCalls.undo(thermo, CcCalls.hvacMode(thermo, "off")))
    val sw = e("switch.s", "on")
    assertEquals("turn_on", CcCalls.undo(sw, CcCalls.power(sw, false))!!.service)
  }

  // --- builders -----------------------------------------------------------------------------

  @Test fun buildersClampAndSnap() {
    val l = e("light.l", "on", "min_color_temp_kelvin" to 2700, "max_color_temp_kelvin" to 5000)
    assertEquals(5000, CcCalls.kelvin(l, 9000).data["color_temp_kelvin"])
    assertEquals(2700, CcCalls.kelvin(l, 1000).data["color_temp_kelvin"])
    assertEquals("turn_off", CcCalls.brightness(l, -5).service)
    assertEquals(100, CcCalls.brightness(l, 500).data["brightness_pct"])
    assertEquals(CcCalls.lightPower(l, true), CcCalls.power(l, true))
    val c = e("climate.c", "heat", "target_temp_step" to 0.5, "min_temp" to 7, "max_temp" to 30)
    assertEquals(21.5, CcCalls.temperature(c, 21.4).data["temperature"])
    assertEquals(30.0, CcCalls.temperature(c, 99.0).data["temperature"])
    val zeroStep = e("climate.c", "heat", "target_temp_step" to 0, "min_temp" to 7, "max_temp" to 30)
    assertEquals(21.0, CcCalls.temperature(zeroStep, 21.4).data["temperature"])
    assertEquals("Climate 21.5°", CcCalls.temperature(c.copy(attrs = c.attrs + ("friendly_name" to "Climate")), 21.5).label)
    val tv = e("media_player.tv", "paused")
    assertEquals("playing", CcCalls.mediaPlayPause(tv).optimistic?.state)
    assertEquals("media_previous_track", CcCalls.mediaPrevious(tv).service)
    assertEquals(1.0, CcCalls.mediaVolume(tv, 3f).data["volume_level"])
    assertNull(CcCalls.snapshot(emptyList()))
    assertEquals("scene.${CcCalls.UNDO_SCENE}", CcCalls.undoScene().entityId)
    assertEquals(mapOf("code" to "12"), CcCalls.alarm(e("alarm_control_panel.a", "x"), CcAlarmMode.HOME, "12").data)
  }

  @Test fun messagesCarryListsAsArraysAndOmitTargetWithoutEntity() {
    val m = CcCalls.snapshot(listOf("light.a"))!!.toMessage(7)
    assertEquals(7, m.getInt("id"))
    assertFalse(m.has("target"))
    assertEquals("light.a", m.getJSONObject("service_data").getJSONArray("snapshot_entities").getString(0))
    assertEquals("light.b", CcCalls.lightPower(e("light.b", "on"), false).toMessage(1).getJSONObject("target").getString("entity_id"))
  }

  // --- optimistic ---------------------------------------------------------------------------

  @Test fun optimisticOverridesApplyAndMatchWithinTolerance() {
    val base = e("light.l", "off")
    val o = CcOptimistic(state = "on", brightnessPct = 50, rgb = 0x102030, kelvin = 3000, position = 40, targetTemp = 20.0,
        percentage = 30, volume = 0.5f)
    val shown = o.applyTo(base)
    assertEquals("on", shown.state)
    assertEquals(50, shown.brightnessPct)
    assertEquals(0x102030, shown.rgb)
    assertEquals(3000, shown.colorTempK)
    assertEquals(40, shown.position)
    assertEquals(20.0, shown.targetTemp!!, 0.0)
    assertEquals(30, shown.percentage)
    assertEquals(0.5f, shown.volume!!, 1e-6f)
    assertTrue(o.matches(shown))
    assertFalse(o.matches(base))
    assertFalse(CcOptimistic(position = 40).matches(e("cover.b", "stopped")))
    assertFalse(CcOptimistic(volume = 0.5f).matches(e("media_player.m", "on", "volume_level" to 0.6)))
    assertFalse(CcOptimistic(targetTemp = 20.0).matches(e("climate.c", "heat", "temperature" to 21)))
    assertFalse(CcOptimistic(percentage = 30).matches(e("fan.f", "on")))
    assertFalse(CcOptimistic(rgb = 1).matches(e("light.l", "on")))
    assertFalse(CcOptimistic(kelvin = 3000).matches(e("light.l", "on")))
    assertFalse(CcOptimistic(brightnessPct = 50).matches(e("light.l", "on", "brightness" to 200)))
    assertTrue(CcOptimistic().matches(base))
    assertEquals("cool", CcOptimistic(hvacMode = "cool", state = "on").applyTo(e("climate.c", "off")).state)
    assertFalse(CcOptimistic(hvacMode = "cool").matches(e("climate.c", "off")))
  }

  @Test fun alarmModesOfferedFromFeatureBits() {
    assertEquals(listOf(CcAlarmMode.DISARM), CcAlarmMode.offered(e("alarm_control_panel.a", "x")))
    assertEquals(listOf(CcAlarmMode.DISARM, CcAlarmMode.NIGHT), CcAlarmMode.offered(e("alarm_control_panel.a", "x", "supported_features" to 4)))
    assertFalse("vacation is never offered", CcAlarmMode.VACATION in CcAlarmMode.offered(e("alarm_control_panel.a", "x", "supported_features" to 63)))
  }
}
