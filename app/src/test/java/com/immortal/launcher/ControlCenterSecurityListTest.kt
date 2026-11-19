/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterSecurityListTest {
  private val settings = ControlCenterConfig.Settings()
  private val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)
  private fun bs(id: String, state: String, cls: String, name: String = id, changed: Long = 0L) =
      CcEntity("binary_sensor.$id", state, mapOf("device_class" to cls, "friendly_name" to name), changed)

  /** A slice of the owner's house: 13 door sensors in HA, not 4. */
  private val house = listOf(
      bs("pantry_door_sensor", "on", "door", "Pantry door", 100L),
      bs("tilt_open", "on", "door", "Tilt sensor open"),
      bs("tilt_window_door", "on", "door", "Tilt window/door"),
      bs("tilt_window_door_2", "on", "door", "Tilt window/door 2"),
      bs("main_door_sensor", "off", "door", "Main door"),
      bs("deck_door_sensor", "off", "door", "Deck door"),
      bs("garage_door_sensor", "off", "door", "Garage door sensor"),
      bs("tesla_doors_open", "unknown", "door", "Tesla doors"),
      bs("upstair_smoky_smoke", "off", "smoke", "Upstairs smoke"),
      bs("upstair_smoky_co", "off", "gas", "Upstairs CO/gas"),
      bs("bedroom_motion", "on", "motion", "Bedroom motion"),
      bs("garden_motion", "off", "motion", "Garden motion"),
      bs("front_door_tamper", "off", "tamper", "Front door tamper"),
      bs("ble_connected", "on", "connectivity", "Not security"),
      CcEntity("lock.front_door", "unlocked", mapOf("friendly_name" to "Front door")),
      CcEntity("lock.stargate_lock", "locked", mapOf("friendly_name" to "Car")),
      CcEntity("cover.garage_door_cam_garage_door", "open", mapOf("device_class" to "garage", "friendly_name" to "Garage door"), 50L),
      CcEntity("cover.stargate_trunk", "closed", mapOf("device_class" to "door", "friendly_name" to "Trunk")),
      CcEntity("alarm_control_panel.everett_home_alarm", "disarmed", mapOf("friendly_name" to "Alarm")),
      CcEntity("light.porch", "on"),
  )
  private val areas = mapOf("binary_sensor.pantry_door_sensor" to "Kitchen", "cover.garage_door_cam_garage_door" to "Garage")
  private fun sections(s: ControlCenterConfig.Settings = settings) = CcSecurityList.sections(house, s) { areas[it] }

  @Test fun everySecurityEntityIsListed_nothingElse() {
    val all = sections().flatMap { it.items }.map { it.id }.toSet()
    // 13 sensors (8 doors, smoke, gas, 2 motion, tamper) + 2 locks + the garage cover + the alarm panel.
    assertEquals(17, all.size)
    assertFalse("binary_sensor.ble_connected" in all)
    assertFalse("cover.stargate_trunk" in all)
    assertFalse("light.porch" in all)
    assertTrue("binary_sensor.front_door_tamper" in all)
    assertTrue("alarm_control_panel.everett_home_alarm" in all)
  }

  @Test fun groupsCountsAndTags() {
    val by = sections().associateBy { it.group }
    assertEquals(8, by.getValue(CcSecGroup.DOORS).items.size)
    assertEquals("4 open", by.getValue(CcSecGroup.DOORS).tag)
    assertEquals(false, by.getValue(CcSecGroup.DOORS).ok)
    assertEquals("1 unlocked", by.getValue(CcSecGroup.LOCKS).tag)
    assertEquals("1 open", by.getValue(CcSecGroup.GARAGE).tag)
    assertEquals("OK", by.getValue(CcSecGroup.HAZARDS).tag)
    assertEquals(true, by.getValue(CcSecGroup.HAZARDS).ok)
    assertEquals("1 active", by.getValue(CcSecGroup.MOTION).tag)
    assertNull(by.getValue(CcSecGroup.MOTION).ok)
    assertEquals("OK", by.getValue(CcSecGroup.TAMPER).tag)
    assertEquals("Disarmed", by.getValue(CcSecGroup.ALARM).tag)
  }

  @Test fun theTagSaysExactlyWhich() {
    val doors = sections().first { it.group == CcSecGroup.DOORS }
    assertEquals("Pantry door, Tilt sensor open, Tilt window/door, Tilt window/door 2 · 1 unavailable", doors.summary)
    assertEquals(listOf("Pantry door", "Tilt sensor open", "Tilt window/door", "Tilt window/door 2"), doors.attention.map { it.name })
  }

  @Test fun openFirst_unavailableLast_thenByName() {
    val doors = sections().first { it.group == CcSecGroup.DOORS }.items
    assertTrue(doors.take(4).all { it.attention })
    assertEquals(listOf("Deck door", "Garage door sensor", "Main door"), doors.subList(4, 7).map { it.name })
    assertEquals("Tesla doors", doors.last().name)
    assertTrue(doors.last().unavailable)
    assertEquals("Unknown", doors.last().state)
  }

  @Test fun sectionsNeedingAttentionComeFirst() {
    val order = sections().map { it.group }
    assertEquals(listOf(CcSecGroup.LOCKS, CcSecGroup.GARAGE, CcSecGroup.DOORS), order.take(3))
    assertEquals(listOf(CcSecGroup.ALARM, CcSecGroup.HAZARDS, CcSecGroup.MOTION, CcSecGroup.TAMPER), order.drop(3))
  }

  @Test fun inlineActions_closeAnOpenGarageAndLockAnUnlockedLock() {
    val items = sections().flatMap { it.items }.associateBy { it.id }
    assertEquals(CcSecAction.CLOSE, items.getValue("cover.garage_door_cam_garage_door").action)
    assertEquals(CcSecAction.LOCK, items.getValue("lock.front_door").action)
    assertNull(items.getValue("lock.stargate_lock").action)
    assertNull("a door sensor can't be closed from here", items.getValue("binary_sensor.pantry_door_sensor").action)
    assertEquals("Garage", items.getValue("cover.garage_door_cam_garage_door").area)
    assertEquals(50L, items.getValue("cover.garage_door_cam_garage_door").since)
    val closed = CcSecurityList.item(CcEntity("cover.g", "closed", mapOf("device_class" to "garage")), CcSecGroup.GARAGE, null)
    assertNull(closed.action)
    val gone = CcSecurityList.item(CcEntity("lock.x", "unavailable"), CcSecGroup.LOCKS, null)
    assertNull(gone.action)
    assertFalse(gone.attention)
  }

  @Test fun kidsModeHidesLocksGarageAndAlarm() {
    val groups = sections(kids).map { it.group }.toSet()
    assertFalse(CcSecGroup.LOCKS in groups)
    assertFalse(CcSecGroup.GARAGE in groups)
    assertFalse(CcSecGroup.ALARM in groups)
    assertTrue(CcSecGroup.DOORS in groups)
    assertTrue(CcSecGroup.HAZARDS in groups)
  }

  @Test fun statesInWords() {
    assertEquals("Open", CcSecurityList.stateWord(bs("d", "on", "door"), CcSecGroup.DOORS))
    assertEquals("Closed", CcSecurityList.stateWord(bs("d", "off", "door"), CcSecGroup.DOORS))
    assertEquals("Smoke", CcSecurityList.stateWord(bs("s", "on", "smoke"), CcSecGroup.HAZARDS))
    assertEquals("CO detected", CcSecurityList.stateWord(bs("c", "on", "carbon_monoxide"), CcSecGroup.HAZARDS))
    assertEquals("Wet", CcSecurityList.stateWord(bs("w", "on", "moisture"), CcSecGroup.HAZARDS))
    assertEquals("Occupied", CcSecurityList.stateWord(bs("o", "on", "occupancy"), CcSecGroup.MOTION))
    assertEquals("Tampered", CcSecurityList.stateWord(bs("t", "on", "tamper"), CcSecGroup.TAMPER))
    assertEquals("Unavailable", CcSecurityList.stateWord(bs("t", "unavailable", "tamper"), CcSecGroup.TAMPER))
    assertEquals("Jammed", CcSecurityList.stateWord(CcEntity("lock.x", "jammed"), CcSecGroup.LOCKS))
    assertTrue(CcSecurityList.item(CcEntity("lock.x", "jammed"), CcSecGroup.LOCKS, null).attention)
    assertTrue(CcSecurityList.item(bs("s", "on", "smoke"), CcSecGroup.HAZARDS, null).attention)
    assertFalse("motion is information, never a problem", CcSecurityList.item(bs("m", "on", "motion"), CcSecGroup.MOTION, null).attention)
  }

  @Test fun sinceLines() {
    val day = 24 * 3_600_000L
    val now = 10 * day + 1_000L
    val time: (Long) -> String = { "t$it" }
    val dayOf: (Long) -> String = { "d${it / day}" }
    assertEquals("", CcSecurityList.sinceLine(0L, now, time, dayOf))
    assertEquals("since t${now - 500}", CcSecurityList.sinceLine(now - 500, now, time, dayOf))
    assertEquals("since d8 t${8 * day}", CcSecurityList.sinceLine(8 * day, now, time, dayOf))
  }

  @Test fun everythingClosedReadsCalmly() {
    val calm = CcSecurityList.sections(listOf(bs("a", "off", "door", "A"), bs("b", "off", "door", "B"),
        CcEntity("lock.l", "locked", mapOf("friendly_name" to "L"))), settings) { null }
    assertEquals(listOf("Locked", "All 2 closed"), calm.map { it.summary })
    assertTrue(calm.all { it.ok == true })
  }

  // --- why only four showed -----------------------------------------------------------------

  /** HA's `config/entity_registry/list` has no device_class: the Portal never knew these were doors. */
  @Test fun registryListHasNoClasses_soGetEntriesSuppliesThem() {
    val list = JSONArray("""[
      {"entity_id":"binary_sensor.pantry_door_sensor","platform":"zwave_js","device_id":"d1","entity_category":null},
      {"entity_id":"binary_sensor.front_door_tamper","platform":"zwave_js","device_id":"d1","entity_category":"diagnostic"},
      {"entity_id":"binary_sensor.ble_connected","platform":"x","entity_category":null},
      {"entity_id":"camera.front_door_cam_snapshot","platform":"mqtt","device_id":"ring1"},
      {"entity_id":"button.front_door_cam_take_snapshot_2","platform":"mqtt","device_id":"ring1"},
      {"entity_id":"lock.front_door","platform":"zwave_js"}]""")
    val bare = CcRegistryParser.registry(emptyList(), emptyList(), emptyMap(), list)
    assertTrue(bare.entityClass.isEmpty())
    assertEquals(listOf("binary_sensor.ble_connected", "binary_sensor.front_door_tamper", "binary_sensor.pantry_door_sensor"), bare.classless)
    // Before: without classes, only locks/covers (and whatever was on the plan) were subscribed.
    val before = CcInterest.foreground(bare, null)!!
    assertFalse("binary_sensor.pantry_door_sensor" in before)
    val entries = JSONObject("""{
      "binary_sensor.pantry_door_sensor": {"device_class": null, "original_device_class": "door"},
      "binary_sensor.front_door_tamper": {"original_device_class": "tamper"},
      "binary_sensor.ble_connected": {"original_device_class": "connectivity"},
      "binary_sensor.gone": null}""")
    val reg = bare.copy(fetchedClass = CcRegistryParser.classes(entries))
    assertEquals("door", reg.classOf("binary_sensor.pantry_door_sensor"))
    val after = CcInterest.foreground(reg, null)!!
    assertTrue("binary_sensor.pantry_door_sensor" in after)
    assertTrue("diagnostic tamper sensors are on the Security screen", "binary_sensor.front_door_tamper" in after)
    assertFalse("binary_sensor.ble_connected" in after)
    assertTrue("ring-mqtt's take-snapshot button feeds Refresh", "button.front_door_cam_take_snapshot_2" in after)
    assertEquals(mapOf("camera.front_door_cam_snapshot" to listOf("button.front_door_cam_take_snapshot_2")), reg.snapshotButtons)
    assertEquals("mqtt", reg.cameraPlatform["camera.front_door_cam_snapshot"])
    // The background (banner) set is deliberately unchanged by the fetched classes.
    assertEquals(CcInterest.background(bare), CcInterest.background(reg))
  }

  @Test fun protocolAsksForEntriesAndStreams() {
    val p = CcProtocol("secret-token")
    val classes = JSONObject(p.classes(listOf("binary_sensor.a", "binary_sensor.b")).text)
    assertEquals("config/entity_registry/get_entries", classes.getString("type"))
    assertEquals(2, classes.getJSONArray("entity_ids").length())
    val stream = JSONObject(p.cameraStream("camera.front", "s1").text)
    assertEquals("camera/stream", stream.getString("type"))
    assertEquals("camera.front", stream.getString("entity_id"))
    val out = p.onMessage("""{"id":${stream.getInt("id")},"type":"result","success":true,"result":{"url":"/api/hls/t/master_playlist.m3u8"}}""")
    val r = out.single() as CcProtocol.Out.Result
    assertEquals(CcProtocol.Kind.CAMERA_STREAM, r.req.kind)
    assertEquals("s1", r.req.key)
    assertFalse(classes.toString().contains("secret-token"))
  }
}
