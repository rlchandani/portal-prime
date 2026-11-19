/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.CallDirection
import com.immortal.launcher.CallEndReason
import com.immortal.launcher.CallHistoryEntry
import com.immortal.launcher.CallKind
import com.immortal.launcher.CallPeer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen_context builders: the right facts, target ids, no photos, and always inside the token budget. */
class ScreenContextTest {
  private fun entity(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 2, 2), devices = listOf(
      CcPlaced("light.kitchen", 0f, 0f), CcPlaced("sensor.kitchen_temp", 0f, 0f), CcPlaced("lock.front", 0f, 0f)))
  private val cc = CcUiState(
      entities = listOf(
          entity("light.kitchen", "on", "friendly_name" to "Kitchen lights", "brightness" to 204),
          entity("sensor.kitchen_temp", "21.5", "device_class" to "temperature"),
          entity("lock.front", "locked", "friendly_name" to "Front door"),
          entity("light.attic", "unavailable"),
      ).associateBy { it.id },
      plan = CcPlan(listOf(CcFloor("ground", "Ground floor", listOf(kitchen)))),
  )

  @Test fun controlCenterListsRoomsAndSwitchableDevicesWithTargets() {
    val out = ScreenContext.controlCenter(cc)
    val room = out.getJSONArray("rooms").getJSONObject(0)
    assertEquals("cc.room.kitchen", room.getString("target"))
    assertEquals(1, room.getInt("lights_on"))
    assertEquals(21.5, room.getDouble("temperature"), 0.0)
    val devices = out.getJSONArray("devices")
    assertEquals(2, devices.length()) // the sensor isn't switchable; nothing unavailable
    val light = devices.getJSONObject(0)
    assertEquals("cc.entity.light.kitchen", light.getString("target"))
    assertEquals(80, light.getInt("brightness_pct"))
    assertEquals("Kitchen", light.getString("room"))
    assertEquals("locked", devices.getJSONObject(1).getString("state"))
  }

  @Test fun aHugeHomeIsFittedIntoTheBudget() {
    val many = (1..400).map { entity("light.l$it", "on", "friendly_name" to "A very long light name number $it") }
    val out = ScreenContext.controlCenter(CcUiState(entities = many.associateBy { it.id }))
    assertTrue(out.toString().length <= ScreenContext.MAX_CHARS)
    assertTrue(out.getJSONArray("devices").length() > 0)
  }

  @Test fun musicSaysWhatPlaysAndWhatIsNext() {
    val tracks = (1..8).map { YtQueueEntry(it.toLong(), YtTrack("vid0000000$it", "Song $it", "Band")) }
    val state = YtEngineState(queue = YtQueue(tracks, currentIndex = 1), status = YtPlayerStatus.PLAYING,
        positionMs = 61_000, durationMs = 200_000)
    val out = ScreenContext.music(state, "Kitchen")
    val now = out.getJSONObject("now_playing")
    assertEquals("Song 2", now.getString("title"))
    assertTrue(now.getBoolean("playing"))
    assertEquals(61, now.getInt("position_s"))
    assertEquals(ScreenContext.MAX_QUEUE, out.getJSONArray("up_next").length())
    assertEquals("Song 3", out.getJSONArray("up_next").getJSONObject(0).getString("title"))
  }

  @Test fun musicHandsOutItsControlsAndQueueTargets() {
    val tracks = (1..3).map { YtQueueEntry(it.toLong(), YtTrack("vid0000000$it", "Song $it", "Band")) }
    val playing = ScreenContext.music(YtEngineState(queue = YtQueue(tracks, currentIndex = 0)), null)
    val targets = (0 until playing.getJSONArray("controls").length())
        .map { playing.getJSONArray("controls").getJSONObject(it).getString("target") }
    assertEquals(listOf(ScreenContext.MUSIC_CONTROLS, ScreenContext.MUSIC_QUEUE), targets)
    targets.forEach { assertEquals(HearthpaneScreens.MUSIC, HearthpaneTools.screenOf(it)) }
  }

  @Test fun idleMusicOffersOnlyTheQueueStrip() {
    val controls = ScreenContext.music(YtEngineState(), null).getJSONArray("controls")
    assertEquals(1, controls.length())
    assertEquals(ScreenContext.MUSIC_QUEUE, controls.getJSONObject(0).getString("target"))
  }

  @Test fun nothingPlayingIsSaid() {
    assertTrue(ScreenContext.music(YtEngineState(), null).isNull("now_playing"))
  }

  @Test fun settingsListsThePageRowsAndValues() {
    val out = ScreenContext.settings("Display", "Settings › Display", listOf(
        ScreenContext.SettingsRow("immortal.settingsTheme", "Appearance", "light"),
        ScreenContext.SettingsRow("immortal.hideStatusBar", "Hide status bar", true),
        ScreenContext.SettingsRow("display.brightness", "Brightness", null)))
    assertEquals("Display", out.getString("page"))
    val rows = out.getJSONArray("rows")
    assertEquals("settings:immortal.settingsTheme", rows.getJSONObject(0).getString("target"))
    assertEquals(true, rows.getJSONObject(1).getBoolean("value"))
    assertFalse(rows.getJSONObject(2).has("value"))
  }

  @Test fun callsListRoomsAndRecentCalls() {
    val peers = listOf(CallPeer("p1", "Living Area", seenMs = 0L, inCall = true))
    val history = listOf(CallHistoryEntry("h1", "p1", "Living Area", CallDirection.INCOMING, CallKind.CALL,
        CallEndReason.MISSED, atMs = 1_000_000L, durationMs = 0L))
    val out = ScreenContext.calls(peers, history, inCall = false, nowMs = 1_000_000L + 5 * 60_000L)
    assertTrue(out.getJSONArray("rooms").getJSONObject(0).getBoolean("in_call"))
    val recent = out.getJSONArray("recent").getJSONObject(0)
    assertTrue(recent.getBoolean("missed"))
    assertEquals(5, recent.getInt("minutes_ago"))
  }

  @Test fun callsRoomsCarryRoomAndVideoCallTargets() {
    val peers = listOf(CallPeer("p1", "Living Area", seenMs = 0L), CallPeer("p2", "Den", seenMs = 0L, acceptsCalls = false))
    val rooms = ScreenContext.calls(peers, emptyList(), inCall = false, nowMs = 0L).getJSONArray("rooms")
    val living = rooms.getJSONObject(0)
    assertEquals("calls.room.p1", living.getString("target"))
    assertEquals("calls.video.p1", living.getString("video_call_target"))
    assertFalse(rooms.getJSONObject(1).has("video_call_target")) // calls off: nothing to point at
    assertEquals(HearthpaneScreens.CALLS, HearthpaneTools.screenOf("calls.video.p1"))
    assertEquals(HearthpaneScreens.CALLS, HearthpaneTools.screenOf("calls.room.p1"))
  }

  @Test fun homeNeverDescribesPhotos() {
    val out = ScreenContext.home("home", null)
    assertEquals(ScreenContext.PHOTO_NOTE, out.getString("note"))
    assertFalse(out.toString().contains("photo_url"))
  }

  @Test fun everyBuilderStaysInsideTheTokenBudget() {
    listOf(ScreenContext.controlCenter(cc), ScreenContext.music(YtEngineState(), "x"), ScreenContext.home("apps", null),
        ScreenContext.none()).forEach { assertTrue(it.toString().length <= ScreenContext.MAX_CHARS) }
  }
}
