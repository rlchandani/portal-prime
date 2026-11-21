/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.CcEntity
import com.immortal.launcher.CcFloor
import com.immortal.launcher.CcPlaced
import com.immortal.launcher.CcPlan
import com.immortal.launcher.CcRect
import com.immortal.launcher.CcRoom
import com.immortal.launcher.CcUiState
import com.immortal.launcher.YtEngineState
import com.immortal.launcher.YtPlayerStatus
import com.immortal.launcher.YtQueue
import com.immortal.launcher.YtQueueEntry
import com.immortal.launcher.YtTrack
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branches of the screen_context builders the main suite doesn't reach: climate, room facts, values, fitting. */
class ScreenContextCoverageTest {
  private fun entity(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  private fun room(id: String, vararg ids: String) =
      CcRoom(id, id.replaceFirstChar { it.uppercase() }, CcRect(0, 0, 1, 1), devices = ids.map { CcPlaced(it, 0f, 0f) })

  private fun state(rooms: List<CcRoom>, vararg entities: CcEntity) = CcUiState(
      entities = entities.associateBy { it.id },
      plan = CcPlan(listOf(CcFloor("f", "Ground", rooms))),
  )

  private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

  @Test fun climateReportsCurrentAndTargetTemperatureAndAction() {
    val out = ScreenContext.controlCenter(state(listOf(room("hall", "climate.hall")),
        entity("climate.hall", "heat", "current_temperature" to 19.5, "temperature" to 21, "hvac_action" to "heating")))
    val device = out.getJSONArray("devices").getJSONObject(0)
    assertEquals(19.5, device.getDouble("current_temperature"), 0.0)
    assertEquals(21.0, device.getDouble("target_temperature"), 0.0)
    assertEquals("heating", device.getString("action"))
  }

  @Test fun climateWithoutNumbersOrActionOmitsThoseFields() {
    val out = ScreenContext.controlCenter(state(listOf(room("hall", "climate.hall")),
        entity("climate.hall", "off", "current_temperature" to "warm", "hvac_action" to 3)))
    val device = out.getJSONArray("devices").getJSONObject(0)
    assertFalse(device.has("current_temperature"))
    assertFalse(device.has("target_temperature"))
    assertFalse(device.has("action"))
  }

  @Test fun lightBrightnessIsOnlyReportedWhileOnAndNumeric() {
    val out = ScreenContext.controlCenter(state(listOf(room("den", "light.off", "light.text")),
        entity("light.off", "off", "brightness" to 255),
        entity("light.text", "on", "brightness" to "bright")))
    val devices = out.getJSONArray("devices").objects()
    assertEquals(2, devices.size)
    devices.forEach { assertFalse(it.toString(), it.has("brightness_pct")) }
  }

  @Test fun roomReportsHumidityMotionAndOpenings() {
    val out = ScreenContext.controlCenter(state(listOf(room("den",
        "sensor.hum", "binary_sensor.motion", "binary_sensor.window", "lock.back")),
        entity("sensor.hum", "48.6", "device_class" to "humidity"),
        entity("binary_sensor.motion", "on", "device_class" to "motion"),
        entity("binary_sensor.window", "on", "device_class" to "window"),
        entity("lock.back", "unlocked")))
    val den = out.getJSONArray("rooms").getJSONObject(0)
    assertEquals(49, den.getInt("humidity"))
    assertTrue(den.getBoolean("motion"))
    assertEquals(2, den.getInt("open"))
    assertEquals(0, den.getInt("lights"))
  }

  @Test fun quietRoomOmitsMotionOpenAndNonNumericSensors() {
    val out = ScreenContext.controlCenter(state(listOf(room("den",
        "sensor.t", "sensor.h", "binary_sensor.motion", "lock.back")),
        entity("sensor.t", "unknown", "device_class" to "temperature"),
        entity("sensor.h", "n/a", "device_class" to "humidity"),
        entity("binary_sensor.motion", "off", "device_class" to "motion"),
        entity("lock.back", "locked")))
    val den = out.getJSONArray("rooms").getJSONObject(0)
    listOf("temperature", "humidity", "motion", "open").forEach { assertFalse(it, den.has(it)) }
  }

  @Test fun roomsAreCappedAndDevicesNotPlacedAreSkipped() {
    val rooms = (1..ScreenContext.MAX_ROOMS + 4).map { room("r$it", "light.r$it") }
    val entities = rooms.map { entity("light.${it.id}", "on") } + entity("light.unplaced", "on")
    val out = ScreenContext.controlCenter(state(rooms, *entities.toTypedArray()))
    assertEquals(ScreenContext.MAX_ROOMS, out.getJSONArray("rooms").length())
    val targets = out.getJSONArray("devices").objects().map { it.getString("target") }
    assertFalse(targets.contains("cc.entity.light.unplaced"))
    // Rooms past the cap still give their devices a room name.
    val last = out.getJSONArray("devices").objects().first { it.getString("target") == "cc.entity.light.r20" }
    assertEquals("R20", last.getString("room"))
  }

  @Test fun withoutAPlanEverySwitchableEntityIsListedWithoutARoom() {
    val out = ScreenContext.controlCenter(CcUiState(entities = listOf(
        entity("sensor.t", "20"), entity("fan.b", "on"), entity("valve.a", "closed"), entity("lock.c", "unknown"),
    ).associateBy { it.id }))
    val devices = out.getJSONArray("devices").objects()
    assertEquals(listOf("cc.entity.fan.b", "cc.entity.valve.a"), devices.map { it.getString("target") })
    devices.forEach { assertFalse(it.has("room")) }
    assertEquals(0, out.getJSONArray("rooms").length())
  }

  @Test fun musicWithNothingLoadedHasNullNowPlayingAndOnlyTheQueueControl() {
    val out = ScreenContext.music(YtEngineState(), null)
    assertTrue(out.isNull("now_playing"))
    assertFalse(out.has("room"))
    assertFalse(out.has("source"))
    val controls = out.getJSONArray("controls").objects().map { it.getString("target") }
    assertEquals(listOf(ScreenContext.MUSIC_QUEUE), controls)
  }

  @Test fun musicNamesItsSourceTrimmedAndOffersTransportControls() {
    val tracks = listOf(YtQueueEntry(1, YtTrack("vid00000001", "  Song  ", "Band")))
    val out = ScreenContext.music(YtEngineState(queue = YtQueue(tracks, currentIndex = 0, sourceLabel = "  Liked songs ")), " Den ")
    assertEquals("Liked songs", out.getString("source"))
    assertEquals("Den", out.getString("room"))
    assertEquals("Song", out.getJSONObject("now_playing").getString("title"))
    assertFalse(out.getJSONObject("now_playing").getBoolean("playing"))
    assertEquals(0, out.getJSONArray("up_next").length())
    val controls = out.getJSONArray("controls").objects().map { it.getString("target") }
    assertEquals(listOf(ScreenContext.MUSIC_CONTROLS, ScreenContext.MUSIC_QUEUE), controls)
  }

  @Test fun settingsRowsKeepBooleansAndNumbersAndStringifyOtherValues() {
    val out = ScreenContext.settings("Display", "Settings > Display", listOf(
        ScreenContext.SettingsRow("a", "Bool", true),
        ScreenContext.SettingsRow("b", "Number", 42),
        ScreenContext.SettingsRow("c", "Enum", Thread.State.NEW),
        ScreenContext.SettingsRow("d", "None", null),
        ScreenContext.SettingsRow("e", "Long text", "x".repeat(100)),
    ))
    val rows = out.getJSONArray("rows").objects()
    assertEquals(true, rows[0].get("value"))
    assertEquals(42, rows[1].get("value"))
    assertEquals("NEW", rows[2].get("value"))
    assertFalse(rows[3].has("value"))
    assertEquals(60, rows[4].getString("value").length)
    assertEquals("settings:a", rows[0].getString("target"))
  }

  @Test fun settingsRowsAreCapped() {
    val rows = (1..ScreenContext.MAX_ROWS + 5).map { ScreenContext.SettingsRow("r$it", "Row $it", it) }
    assertEquals(ScreenContext.MAX_ROWS, ScreenContext.settings("p", "p", rows).getJSONArray("rows").length())
  }

  @Test fun homeTellsWhatPlaysButNotWhenNothingIsLoaded() {
    val tracks = listOf(YtQueueEntry(1, YtTrack("vid00000001", "Song", "Band")))
    val playing = ScreenContext.home("home", YtEngineState(queue = YtQueue(tracks, 0), status = YtPlayerStatus.PLAYING))
    val now = playing.getJSONObject("now_playing")
    assertEquals("Song", now.getString("title"))
    assertEquals("Band", now.getString("artist"))
    assertTrue(now.getBoolean("playing"))
    assertFalse(ScreenContext.home("tools", YtEngineState()).has("now_playing"))
  }

  @Test fun fitSkipsMissingListsAndStopsWhenAListIsEmpty() {
    val out = JSONObject().put("blob", "x".repeat(ScreenContext.MAX_CHARS)).put("items", JSONArray().put(1).put(2))
    ScreenContext.fit(out, "absent", "items")
    assertEquals(0, out.getJSONArray("items").length())
    assertTrue(out.toString().length > ScreenContext.MAX_CHARS) // nothing more to drop
  }

  @Test fun fitLeavesSmallResultsAlone() {
    val out = JSONObject().put("items", JSONArray().put(1).put(2))
    assertEquals(2, ScreenContext.fit(out, "items").getJSONArray("items").length())
  }

  @Test fun withMatchesCapsMatchesAndTrimsTheQuery() {
    val matches = (1..ScreenContext.MAX_MATCHES + 3).map { ScreenContext.Match("c$it", "Title $it", "Settings > $it") }
    val out = ScreenContext.withMatches(ScreenContext.none(), "  how do I dim  ", matches)
    assertEquals(ScreenContext.MAX_MATCHES, out.getJSONArray("settings_matches").length())
    assertEquals("how do I dim", out.getString("query"))
    val first = out.getJSONArray("settings_matches").getJSONObject(0)
    assertEquals("settings:c1", first.getString("target"))
    assertEquals("Settings > 1", first.getString("where"))
  }
}
