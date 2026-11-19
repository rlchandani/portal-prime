/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.CallHistoryEntry
import com.immortal.launcher.CallPeer
import com.immortal.launcher.CcEntity
import com.immortal.launcher.CcUiState
import com.immortal.launcher.YtEngineState
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Builds `hearthpane.screen_context`: what the screen in front shows, as structured text. **No pixels, no photos**
 * (D7). Each builder takes plain state snapshots, so all of them are JVM-tested, and every result is fitted into
 * [MAX_CHARS] (≈1.5k tokens; ~4 chars a token) by dropping list items from the end. Each actionable thing carries a
 * `target` id that `hearthpane.highlight` accepts.
 */
internal object ScreenContext {
  /** ~1.4k tokens: under the 1.5k budget with room for the wrapper. */
  const val MAX_CHARS = 5_600
  const val MAX_ROOMS = 16
  const val MAX_DEVICES = 40
  const val MAX_ROWS = 24
  const val MAX_QUEUE = 5
  const val MAX_PEERS = 12
  const val MAX_HISTORY = 6
  private const val MAX_TEXT = 60
  private const val MS_PER_MINUTE = 60_000L
  private const val MS_PER_SECOND = 1_000L
  private const val HA_BRIGHTNESS_MAX = 255.0
  private const val PERCENT = 100

  const val PHOTO_NOTE = "Photos and the photo frame are never described or sent."
  const val ROOM_TARGET = "cc.room."
  const val ENTITY_TARGET = "cc.entity."
  const val SETTINGS_TARGET = "settings:"

  private val DEVICE_DOMAINS = setOf("light", "switch", "fan", "lock", "cover", "climate", "media_player", "valve")

  /** Control Center: rooms (lights on, temperature, motion, open doors) and the devices that can be switched. */
  fun controlCenter(state: CcUiState): JSONObject {
    val plan = state.plan
    val roomOf = HashMap<String, String>()
    val rooms = JSONArray()
    plan?.floors.orEmpty().forEach { floor ->
      floor.rooms.forEach { room ->
        room.devices.forEach { roomOf[it.entityId] = room.name }
        if (rooms.length() < MAX_ROOMS) rooms.put(roomJson(state, floor.name, room.id, room.name, room.devices.map { it.entityId }))
      }
    }
    val placed = plan?.floors.orEmpty().flatMap { f -> f.rooms.flatMap { r -> r.devices.map { it.entityId } } }
    val candidates = placed.ifEmpty { state.entities.keys.sorted() }
    val devices = JSONArray()
    candidates.distinct().mapNotNull { state.entities[it] }.filter { it.domain in DEVICE_DOMAINS && !it.unavailable }
        .take(MAX_DEVICES).forEach { devices.put(deviceJson(it, roomOf[it.id])) }
    val out = JSONObject().put("screen", HearthpaneScreens.CONTROL_CENTER).put("title", "Control Center")
        .put("connection", state.connection.phase.name.lowercase())
        .put("rooms", rooms).put("devices", devices)
    return fit(out, "devices", "rooms")
  }

  private fun roomJson(state: CcUiState, floor: String, id: String, name: String, ids: List<String>): JSONObject {
    val here = ids.mapNotNull { state.entities[it] }
    val lights = here.filter { it.domain == "light" }
    val out = JSONObject().put("target", ROOM_TARGET + id).put("name", text(name)).put("floor", text(floor))
        .put("lights_on", lights.count { it.isOn }).put("lights", lights.size)
    here.firstOrNull { it.deviceClass == "temperature" }?.state?.toDoubleOrNull()?.let { out.put("temperature", it) }
    here.firstOrNull { it.deviceClass == "humidity" }?.state?.toDoubleOrNull()?.let { out.put("humidity", it.roundToInt()) }
    if (here.any { it.deviceClass == "motion" && it.state == "on" }) out.put("motion", true)
    here.count { it.isOpen }.takeIf { it > 0 }?.let { out.put("open", it) }
    return out
  }

  private fun deviceJson(entity: CcEntity, room: String?): JSONObject {
    val out = JSONObject().put("target", ENTITY_TARGET + entity.id).put("name", text(entity.name))
        .put("kind", entity.domain).put("state", entity.state)
    room?.let { out.put("room", text(it)) }
    if (entity.domain == "light" && entity.isOn) {
      (entity.attrs["brightness"] as? Number)?.let { out.put("brightness_pct", (it.toDouble() / HA_BRIGHTNESS_MAX * PERCENT).roundToInt()) }
    }
    if (entity.domain == "climate") {
      (entity.attrs["current_temperature"] as? Number)?.let { out.put("current_temperature", it.toDouble()) }
      (entity.attrs["temperature"] as? Number)?.let { out.put("target_temperature", it.toDouble()) }
      (entity.attrs["hvac_action"] as? String)?.let { out.put("action", it) }
    }
    return out
  }

  /** Music: what's playing, whether it plays, the next few in the queue, and the room it plays in. */
  fun music(state: YtEngineState, room: String?): JSONObject {
    val queue = state.queue
    val out = JSONObject().put("screen", HearthpaneScreens.MUSIC).put("title", "Music")
    room?.let { out.put("room", text(it)) }
    queue.current?.track?.let { track ->
      out.put("now_playing", JSONObject().put("title", text(track.title)).put("artist", text(track.artist))
          .put("playing", state.isPlaying)
          .put("position_s", state.positionMs / MS_PER_SECOND).put("duration_s", state.durationMs / MS_PER_SECOND))
    } ?: out.put("now_playing", JSONObject.NULL)
    val next = JSONArray()
    queue.entries.drop(queue.currentIndex + 1).take(MAX_QUEUE).forEach {
      next.put(JSONObject().put("title", text(it.track.title)).put("artist", text(it.track.artist)))
    }
    out.put("up_next", next)
    if (queue.sourceLabel.isNotBlank()) out.put("source", text(queue.sourceLabel))
    return fit(out, "up_next")
  }

  /** One Settings row as the page shows it: its control id, title and current value (when it has one). */
  data class SettingsRow(val control: String, val title: String, val value: Any?)

  /** Settings: the page, its path, and its rows with their values. */
  fun settings(pageTitle: String, path: String, rows: List<SettingsRow>): JSONObject {
    val array = JSONArray()
    rows.take(MAX_ROWS).forEach { row ->
      val item = JSONObject().put("target", SETTINGS_TARGET + row.control).put("title", text(row.title))
      when (val v = row.value) {
        null -> Unit
        is Boolean, is Number -> item.put("value", v)
        else -> item.put("value", text(v.toString()))
      }
      array.put(item)
    }
    val out = JSONObject().put("screen", HearthpaneScreens.SETTINGS).put("page", text(pageTitle))
        .put("path", text(path)).put("rows", array)
    return fit(out, "rows")
  }

  /** Calls: the household rooms you can call (and whether each is free), and the latest calls. */
  fun calls(peers: List<CallPeer>, history: List<CallHistoryEntry>, inCall: Boolean, nowMs: Long): JSONObject {
    val rooms = JSONArray()
    peers.take(MAX_PEERS).forEach {
      rooms.put(JSONObject().put("name", text(it.name)).put("accepts_calls", it.acceptsCalls)
          .put("in_call", it.inCall).put("quiet", it.quiet))
    }
    val recent = JSONArray()
    history.sortedByDescending { it.atMs }.take(MAX_HISTORY).forEach {
      recent.put(JSONObject().put("with", text(it.peerName)).put("direction", it.direction.name.lowercase())
          .put("missed", it.missed).put("minutes_ago", ((nowMs - it.atMs) / MS_PER_MINUTE).coerceAtLeast(0)))
    }
    val out = JSONObject().put("screen", HearthpaneScreens.CALLS).put("title", "Calls").put("on_a_call", inCall)
        .put("rooms", rooms).put("recent", recent)
    return fit(out, "recent", "rooms")
  }

  /** Home (and its Apps and Tools tabs): the tab, and what plays; the photos are left out on purpose (D7). */
  fun home(tab: String, nowPlaying: YtEngineState?): JSONObject {
    val out = JSONObject().put("screen", HearthpaneScreens.HOME).put("tab", tab).put("note", PHOTO_NOTE)
    nowPlaying?.queue?.current?.track?.let {
      out.put("now_playing", JSONObject().put("title", text(it.title)).put("artist", text(it.artist))
          .put("playing", nowPlaying.isPlaying))
    }
    return out
  }

  /** Nothing of Hearthpane's is in front (another app): say so, so the model doesn't guess. */
  fun none(): JSONObject = JSONObject().put("screen", JSONObject.NULL)
      .put("note", "No Hearthpane screen is in front. Other apps' screens can't be read.")

  /** A Settings row that matches the user's words, wherever it lives: its target, title and where to find it. */
  data class Match(val control: String, val title: String, val path: String)

  const val MAX_MATCHES = 5

  /**
   * [out] plus the Settings rows matching [query] ("how do I…"). Highlighting one works only while Settings is in
   * front (it opens the row's page); elsewhere the path tells the user where to go.
   */
  fun withMatches(out: JSONObject, query: String, matches: List<Match>): JSONObject {
    val array = JSONArray()
    matches.take(MAX_MATCHES).forEach {
      array.put(JSONObject().put("target", SETTINGS_TARGET + it.control).put("title", text(it.title))
          .put("where", text(it.path)))
    }
    out.put("settings_matches", array).put("query", text(query))
    return fit(out, "settings_matches", "devices", "rows", "rooms", "up_next", "recent")
  }

  /** [out] cut to [MAX_CHARS] by dropping the last items of [lists], in the order given. */
  internal fun fit(out: JSONObject, vararg lists: String): JSONObject {
    for (key in lists) {
      val array = out.optJSONArray(key) ?: continue
      while (out.toString().length > MAX_CHARS && array.length() > 0) array.remove(array.length() - 1)
    }
    return out
  }

  private fun text(value: String): String = value.trim().take(MAX_TEXT)
}
