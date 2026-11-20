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

/** [CcRemoteSlices] and [CcRemoteState.plan]: what the phone's derived slices carry. */
class CcRemoteSlicesCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L) =
      CcEntity(id, state, mapOf(*attrs), lastChanged = changed)

  private val hour = 60 * 60_000L
  private val live = CcConnection(CcPhase.LIVE)

  @Test fun roomNamesComeFromThePlanThenTheRegistry() {
    val lamp = e("light.lamp", "on")
    val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(CcRoom("k", "Kitchen", CcRect(0, 0, 4, 4), devices = listOf(CcPlaced(lamp.id, 0.5f, 0.5f)))))))
    val reg = CcRegistry(areas = listOf(CcHaArea("u", "Utility", null)), entityArea = mapOf("sensor.x" to "u"))
    val name = CcRemoteSlices.roomName(CcUiState(plan = plan, registry = reg))
    assertEquals("Kitchen", name(lamp.id))
    assertEquals("Utility", name("sensor.x"))
    assertNull(name("sensor.y"))
    assertNull(CcRemoteSlices.roomName(CcUiState())("sensor.x"))
  }

  @Test fun theCarsOwnSensorsAreNotHouseAlerts() {
    val leak = e("binary_sensor.car_leak", "on", "device_class" to "moisture", changed = 1)
    val s = CcUiState(entities = mapOf(leak.id to leak), connection = live,
        registry = CcRegistry(tesla = CcTeslaIds(car = mapOf(CcCarRole.LOCK to leak.id))))
    assertTrue(CcRemoteSlices.alerts(s, hour).isEmpty())
    assertEquals(1, CcRemoteSlices.alerts(s.copy(registry = null), hour).size)
  }

  @Test fun aLeakAlertOffersShuttingTheWaterWithThePhonesAction() {
    val leak = e("binary_sensor.sink_leak", "on", "device_class" to "moisture", "friendly_name" to "Sink", changed = 1)
    val valve = e("valve.main", "open", "friendly_name" to "Main water")
    val a = CcRemoteSlices.alertsJson(CcUiState(entities = listOf(leak, valve).associateBy { it.id }, connection = live), hour)
        .getJSONObject(0)
    assertEquals("critical", a.getString("sev"))
    assertEquals("close", a.getString("act"))
    assertEquals(valve.id, a.getString("actEntity"))
    assertFalse(a.has("room"))

    val sw = e("switch.water_main", "on", "friendly_name" to "Water main")
    val b = CcRemoteSlices.alertsJson(CcUiState(entities = listOf(leak, sw).associateBy { it.id }, connection = live), hour)
        .getJSONObject(0)
    assertEquals("power_off", b.getString("act"))
    assertEquals(sw.id, b.getString("actEntity"))
  }

  @Test fun anOpenGarageAlertClosesItAndNamesItsRoom() {
    val garage = e("cover.garage", "open", "device_class" to "garage", "friendly_name" to "Garage", changed = 1)
    val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(CcRoom("gar", "Garage", CcRect(0, 0, 4, 4),
        devices = listOf(CcPlaced(garage.id, 0.5f, 0.5f)))))))
    val arr = CcRemoteSlices.alertsJson(CcUiState(entities = mapOf(garage.id to garage), plan = plan, connection = live), hour)
    val a = arr.getJSONObject(0)
    assertEquals("close", a.getString("act"))
    assertEquals("gar", a.getString("room"))
  }

  @Test fun securityCarriesTheAlarmPanelPeopleAndSections() {
    val panel = e("alarm_control_panel.home", "arming", "supported_features" to 3, "next_state" to "armed_away",
        "code_format" to "number", changed = 1_000L)
    val other = e("alarm_control_panel.zz", "disarmed")
    val sam = e("person.sam", "home", "friendly_name" to "Sam")
    val door = e("binary_sensor.front", "on", "device_class" to "door", "friendly_name" to "Front", changed = 1)
    val s = CcUiState(entities = listOf(panel, other, sam, door).associateBy { it.id }, connection = live,
        settings = ControlCenterConfig.Settings(exitDelaySec = 30))
    val j = CcRemoteSlices.securityJson(s, 11_000L)
    val alarm = j.getJSONObject("alarm")
    assertEquals(panel.id, alarm.getString("id"))
    assertEquals("arming", alarm.getString("state"))
    assertEquals(CcSecurity.title(panel), alarm.getString("title"))
    assertEquals(CcSecurity.subtitle(panel, 20), alarm.getString("subtitle"))
    assertTrue(alarm.getBoolean("code"))
    assertEquals(listOf("disarm", "home", "away"), (0 until alarm.getJSONArray("modes").length()).map { alarm.getJSONArray("modes").getString(it) })
    assertEquals("away", alarm.getString("current"))
    val people = j.getJSONArray("people")
    assertEquals(1, people.length())
    assertEquals("Sam", people.getJSONObject(0).getString("name"))
    assertTrue(j.getJSONArray("sections").length() > 0)
    assertTrue(j.getJSONObject("readiness").has("title"))
    assertTrue(j.has("checks"))
  }

  @Test fun securityWithoutAPanelHasNoAlarmAndADisarmedOneHasNoCountdown() {
    assertFalse(CcRemoteSlices.securityJson(CcUiState(connection = live), 0L).has("alarm"))
    val off = e("alarm_control_panel.home", "disarmed")
    val alarm = CcRemoteSlices.securityJson(CcUiState(entities = mapOf(off.id to off), connection = live), 0L).getJSONObject("alarm")
    assertFalse(alarm.getBoolean("code"))
    assertEquals("disarm", alarm.getString("current"))
    assertEquals(CcSecurity.subtitle(off, 0), alarm.getString("subtitle"))
    val triggered = off.copy(state = "triggered")
    val t = CcRemoteSlices.securityJson(CcUiState(entities = mapOf(off.id to triggered), connection = live), 0L).getJSONObject("alarm")
    assertFalse(t.has("current"))
  }

  @Test fun camerasCarryWhatTheTileNeedsAndTheirRoom() {
    val porch = e("camera.porch", "idle", "friendly_name" to "Porch")
    val dead = e("camera.attic", "unavailable", "friendly_name" to "Attic")
    val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(CcRoom("out", "Outside", CcRect(0, 0, 4, 4),
        devices = listOf(CcPlaced(porch.id, 0.5f, 0.5f)))))))
    val s = CcUiState(entities = listOf(porch, dead).associateBy { it.id }, plan = plan,
        registry = CcRegistry(cameraPlatform = mapOf(porch.id to "wyze_ha")))
    val arr = CcRemoteSlices.camerasJson(s)
    assertEquals(2, arr.length())
    val p = arr.getJSONObject(0)
    assertEquals(porch.id, p.getString("id"))
    assertEquals("Wyze", p.getString("platform"))
    assertEquals("out", p.getString("room"))
    assertTrue(p.getBoolean("refresh"))
    assertFalse(p.getBoolean("unavailable"))
    val d = arr.getJSONObject(1)
    assertTrue(d.getBoolean("unavailable"))
    assertFalse("an unavailable camera without a snapshot button can't refresh", d.getBoolean("refresh"))
    assertFalse(d.has("room"))
    assertFalse(d.has("platform"))
  }

  @Test fun theGarageConfirmCameraIsInTheSameRoomElseOneNamedGarage() {
    val door = e("cover.garage", "open", "device_class" to "garage")
    val inRoom = e("camera.bay", "idle")
    val named = e("camera.garage_b", "idle")
    val named2 = e("camera.garage_a", "idle")
    val placedMissing = "camera.ghost"
    val room = CcRoom("gar", "Garage", CcRect(0, 0, 4, 4), devices = listOf(CcPlaced(door.id, 0.5f, 0.5f),
        CcPlaced(placedMissing, 0.1f, 0.1f), CcPlaced("light.x", 0.2f, 0.2f), CcPlaced(inRoom.id, 0.3f, 0.3f)))
    val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(room))))
    val all = listOf(door, inRoom, named, named2).associateBy { it.id }
    assertEquals(inRoom.id, CcRemoteSlices.cameraFor(CcUiState(entities = all, plan = plan), door))
    assertEquals(named2.id, CcRemoteSlices.cameraFor(CcUiState(entities = all), door))
    assertNull(CcRemoteSlices.cameraFor(CcUiState(entities = mapOf(door.id to door, inRoom.id to inRoom)), door))
  }

  @Test fun scenesPutRecognisableOnesFirstFlagSensitiveAndCap() {
    val night = e("scene.good_night", "x", "friendly_name" to "Good night")
    val lockUp = e("script.lock_up", "off", "friendly_name" to "Lock up")
    val extras = (1..30).map { e("scene.s%02d".format(it), "x", "friendly_name" to "Zed %02d".format(it)) }
    val all = (listOf(night, lockUp) + extras).associateBy { it.id }
    val arr = CcRemoteSlices.scenesJson(CcUiState(entities = all))
    assertEquals(CcRemoteSlices.MAX_SCENES, arr.length())
    val first = arr.getJSONObject(0)
    assertEquals(night.id, first.getString("id"))
    assertEquals("night", first.getString("tone"))
    assertEquals("moon", first.getString("icon"))
    val ids = (0 until arr.length()).map { arr.getJSONObject(it) }
    assertTrue(ids.first { it.getString("id") == lockUp.id }.getBoolean("sensitive"))
    assertFalse(first.getBoolean("sensitive"))
    // Beyond the picked tiles the rest follow by name.
    val names = ids.drop(6).map { it.getString("name") }
    assertEquals(names.sortedBy { it.lowercase() }, names)
  }

  @Test fun roomsCarryGlowsForLitLights() {
    val lamp = e("light.lamp", "on", "brightness" to 255)
    val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(CcRoom("k", "Kitchen", CcRect(0, 0, 4, 4),
        devices = listOf(CcPlaced(lamp.id, 0.25f, 0.75f)))))))
    val r = CcRemoteSlices.roomsJson(CcUiState(entities = mapOf(lamp.id to lamp), plan = plan)).getJSONObject(0)
    assertEquals("k", r.getString("id"))
    assertEquals("g", r.getString("floor"))
    val glow = r.getJSONArray("glows").getJSONObject(0)
    assertEquals(lamp.id, glow.getString("id"))
    assertEquals(0.25, glow.getDouble("fx"), 1e-6)
    assertEquals(0.75, glow.getDouble("fy"), 1e-6)
    assertFalse(r.has("occ"))
    assertEquals(0, CcRemoteSlices.roomsJson(CcUiState()).length())
  }

  @Test fun planAnswerWithAndWithoutAPlan() {
    val none = CcRemoteState.plan(CcUiState())
    assertTrue(none.getBoolean("ok"))
    assertEquals(-1L, none.getLong("planAt"))
    assertEquals(JSONObject.NULL, none.get("plan"))
    assertEquals(0, none.getJSONArray("floors").length())
    val plan = CcPlan(listOf(CcFloor("g", "Ground"), CcFloor("u", "Up")), updatedAt = 77L)
    val some = CcRemoteState.plan(CcUiState(plan = plan))
    assertEquals(77L, some.getLong("planAt"))
    assertTrue(some.get("plan") is JSONObject)
    assertEquals("Up", some.getJSONArray("floors").getJSONObject(1).getString("name"))
  }

  @Test fun stateAnswerReportsPlanPresenceAndRemovals() {
    val lamp = e("light.lamp", "on")
    val j = CcRemoteJournal()
    val s1 = CcUiState(entities = mapOf(lamp.id to lamp), entitiesVersion = 1, plan = CcPlan.EMPTY)
    val first = CcRemoteState.json(s1, j, 0, -1, 0L, "Hall")
    assertFalse(first.getBoolean("hasPlan"))
    assertEquals(0L, first.getLong("planAt"))
    val s2 = CcUiState(entities = emptyMap(), entitiesVersion = 2)
    val second = CcRemoteState.json(s2, j, first.getLong("rev"), 0, 0L, "Hall")
    assertEquals(lamp.id, second.getJSONArray("removed").getString(0))
    assertEquals(-1L, second.getLong("planAt"))
    assertFalse(second.getBoolean("hasPlan"))
    assertTrue(second.getJSONObject("asks").getBoolean("unlock"))
  }
}
