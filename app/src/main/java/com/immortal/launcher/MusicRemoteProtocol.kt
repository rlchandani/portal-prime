/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.MusicRemoteProtocol.Decoded.Accepted
import com.immortal.launcher.MusicRemoteProtocol.Decoded.AlreadyApplied
import com.immortal.launcher.MusicRemoteProtocol.Decoded.Rejected
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Pure wire boundary. Callers must dispatch against the same snapshot/revision they validated. */
internal object MusicRemoteProtocol {
  sealed interface Decoded {
    data class Accepted(val action: MusicPlayerUiAction) : Decoded
    data object AlreadyApplied : Decoded { const val code = "already_applied" }
    data class Rejected(val status: Int, val code: String) : Decoded
  }

  private const val MAX_TEXT = 4096
  private const val MAX_QUEUE = 500
  private const val MAX_ROOMS = 64
  private const val MAX_LYRICS = 1000
  private val trackCommands = setOf("play", "pause", "next", "previous", "seek", "queue-item")
  private val baseKeys = setOf("command", "sourceRevision", "sourceId")

  /**
   * Explicit read-safe allowlist: no adapter problems, credentials, art URLs, borrowed images,
   * session tokens, or raw errors. Only a local command number and fixed outcome are exposed.
   * Unknown/live duration and position remain null.
   * Capability flags describe executable controls, including pending/focus and room eligibility.
   */
  fun state(snapshot: MusicPlayerSnapshot, sourceRevision: Long, elapsedMs: Long): JSONObject {
    val s = snapshot
    val track = currentTrack(s)
    val queue = JSONArray()
    for (item in s.queue.take(MAX_QUEUE)) {
      queue.put(JSONObject().put("id", text(item.id)).put("title", text(item.title))
          .put("artist", text(item.artist)).put("durationMs", nullable(duration(item.durationMs)))
          .put("available", item.available)
          .put("canSelect", can(s, MusicCommand.SELECT_QUEUE_ITEM) &&
              item.available && validId(item.id)))
    }
    val rooms = JSONArray()
    var selectable = false
    var groupable = false
    var volumeControllable = false
    for (room in s.rooms.take(MAX_ROOMS)) {
      val policy = musicRoomActions(s, room)
      val valid = validId(room.id) && mutable(s)
      val canSelect = valid && policy.canSelect
      val canJoin = valid && validId(s.controlPlayerId) && policy.canJoin
      val canRemove = valid && validId(s.controlPlayerId) && policy.canRemove
      val canSetVolume = valid && can(s, MusicCommand.SET_VOLUME, false) &&
          room.available && room.volumeControllable
      selectable = selectable || canSelect
      groupable = groupable || canJoin || canRemove
      volumeControllable = volumeControllable || canSetVolume
      rooms.put(JSONObject().put("id", text(room.id)).put("name", text(room.name))
          .put("available", room.available)
          .put("volume", nullable(room.volume?.takeIf { it in 0..100 }))
          .put("muted", nullable(room.muted))
          .put("groupId", nullable(room.groupId?.let(::text)))
          .put("inCurrentGroup", policy.inCurrentGroup)
          .put("canSelect", canSelect).put("canJoin", canJoin).put("canRemove", canRemove)
          .put("canSetVolume", canSetVolume))
    }
    val lyrics = JSONArray()
    for (line in s.lyrics.take(MAX_LYRICS))
      lyrics.put(JSONObject().put("timeMs", line.timeMs.coerceAtLeast(0)).put("text", text(line.text)))
    val caps = JSONObject()
        .put("play", can(s, MusicCommand.PLAY))
        .put("pause", can(s, MusicCommand.PAUSE) &&
            (s.playback == PlaybackState.PLAYING || s.playback == PlaybackState.PAUSED))
        .put("next", can(s, MusicCommand.NEXT)).put("previous", can(s, MusicCommand.PREVIOUS))
        .put("seek", can(s, MusicCommand.SEEK))
        .put("queue", can(s, MusicCommand.SELECT_QUEUE_ITEM) &&
            s.queue.take(MAX_QUEUE).any { it.available && validId(it.id) })
        .put("lyrics", can(s, MusicCommand.OPEN_LYRICS) && s.lyrics.isNotEmpty())
        .put("rooms", groupable).put("volume", volumeControllable)
        .put("selectPlayer", selectable)
    val metadata = track?.let {
      JSONObject().put("id", text(it.id)).put("title", text(it.title))
          .put("artist", text(it.artist)).put("album", text(it.album))
          .put("durationMs", nullable(if (it.isLive) null else duration(it.durationMs)))
          .put("isLive", it.isLive)
    }
    val pending = s.pending?.let {
      JSONObject().put("requestId", it.requestId).put("command", wire(it.command.name))
          .put("startedAtElapsedMs", it.startedAtElapsedMs.coerceAtLeast(0))
    }
    val lastCommand = s.lastCommand?.let {
      JSONObject().put("requestId", it.requestId).put("command", wire(it.command.name))
          .put("outcome", wire(it.outcome.name))
    }
    return JSONObject().put("schemaVersion", 1).put("sourceRevision", sourceRevision)
        .put("sourceId", text(s.sourceId)).put("connection", wire(s.connection.name))
        .put("playback", wire(s.playback.name)).put("pending", nullable(pending))
        .put("lastCommand", nullable(lastCommand))
        .put("audioOwnedByOther", s.audioOwnedByOther)
        .put("controlPlayerId", nullable(s.controlPlayerId?.let(::text)))
        .put("track", nullable(metadata))
        .put("positionMs", nullable(if (track == null) null else s.visiblePositionMs(elapsedMs)))
        .put("capabilities", caps).put("queue", queue).put("rooms", rooms).put("lyrics", lyrics)
  }

  /**
   * Exact command key sets and runtime JSON types; optInt/optString coercions are forbidden.
   * Malformed payloads are 400/bad_request; changed observations are 409/stale; unsupported or
   * temporarily blocked controls are 409/unavailable. Explicit matching play/pause is a no-op.
   */
  fun decode(body: JSONObject, snapshot: MusicPlayerSnapshot, sourceRevision: Long): Decoded {
    val command = string(body, "command") ?: return bad()
    val extra = when (command) {
      "play", "pause", "next", "previous" -> setOf("trackId")
      "seek" -> setOf("trackId", "positionMs")
      "queue-item" -> setOf("trackId", "queueItemId")
      "volume" -> setOf("roomId", "percent")
      "toggle-room" -> setOf("roomId", "leaderId", "wasInGroup")
      "select-player" -> setOf("roomId")
      else -> return bad()
    }
    val keys = baseKeys + extra
    if (body.length() != keys.size || keys.any { !body.has(it) }) return bad()
    val revision = integer(body, "sourceRevision")?.takeIf { it >= 0 } ?: return bad()
    val sourceId = string(body, "sourceId") ?: return bad()
    val trackId = if (command in trackCommands) string(body, "trackId") ?: return bad() else null
    val roomId = if ("roomId" in extra) string(body, "roomId") ?: return bad() else null
    val leaderId = if ("leaderId" in extra) string(body, "leaderId") ?: return bad() else null
    val wasInGroup = if ("wasInGroup" in extra)
      body.opt("wasInGroup") as? Boolean ?: return bad() else null
    val queueItemId = if ("queueItemId" in extra) string(body, "queueItemId") ?: return bad() else null
    val position = if ("positionMs" in extra)
      integer(body, "positionMs")?.takeIf { it >= 0 } ?: return bad() else null
    val percent = if ("percent" in extra)
      integer(body, "percent")?.takeIf { it in 0..100 }?.toInt() ?: return bad() else null

    val s = snapshot
    if (revision != sourceRevision || sourceId != s.sourceId) return stale()
    if (!mutable(s)) return unavailable()
    if (trackId != null) {
      val track = currentTrack(s) ?: return unavailable()
      if (trackId != track.id) return stale()
    }
    fun accept(action: MusicPlayerUiAction, capability: MusicCommand, trackBound: Boolean = true) =
        if (can(s, capability, trackBound)) Accepted(action) else unavailable()
    return when (command) {
      "play", "pause" -> {
        val requested = if (command == "play") PlaybackState.PLAYING else PlaybackState.PAUSED
        if (s.playback == requested) AlreadyApplied
        // TogglePlayback can only pause PLAYING; a pause request for STOPPED/IDLE must not play.
        else if (command == "pause" && s.playback != PlaybackState.PLAYING) unavailable()
        else accept(MusicPlayerUiAction.TogglePlayback,
            if (command == "play") MusicCommand.PLAY else MusicCommand.PAUSE)
      }
      "next" -> accept(MusicPlayerUiAction.Next, MusicCommand.NEXT)
      "previous" -> accept(MusicPlayerUiAction.Previous, MusicCommand.PREVIOUS)
      "seek" -> {
        val track = currentTrack(s) ?: return unavailable()
        val end = duration(track.durationMs)?.takeUnless { track.isLive } ?: return unavailable()
        if (position!! > end) bad()
        else accept(MusicPlayerUiAction.Seek(position), MusicCommand.SEEK)
      }
      "queue-item" -> {
        if (s.queue.take(MAX_QUEUE).none { it.id == queueItemId && it.available }) unavailable()
        else accept(MusicPlayerUiAction.SelectQueueItem(queueItemId!!), MusicCommand.SELECT_QUEUE_ITEM)
      }
      "volume" -> {
        val room = s.rooms.take(MAX_ROOMS).firstOrNull { it.id == roomId } ?: return unavailable()
        if (!room.available || !room.volumeControllable) unavailable()
        else accept(MusicPlayerUiAction.SetRoomVolume(roomId!!, percent!!), MusicCommand.SET_VOLUME, false)
      }
      "toggle-room" -> {
        val leader = s.controlPlayerId ?: return unavailable()
        if (leaderId != leader) return stale()
        val room = s.rooms.take(MAX_ROOMS).firstOrNull { it.id == roomId } ?: return unavailable()
        val policy = musicRoomActions(s, room)
        if (wasInGroup != policy.inCurrentGroup) return stale()
        if (!policy.canJoin && !policy.canRemove) unavailable()
        else Accepted(MusicPlayerUiAction.ToggleRoom(roomId!!))
      }
      "select-player" -> {
        val room = s.rooms.take(MAX_ROOMS).firstOrNull { it.id == roomId } ?: return unavailable()
        if (!musicRoomActions(s, room).canSelect) unavailable()
        else Accepted(MusicPlayerUiAction.SelectPlayer(roomId!!))
      }
      else -> bad()
    }
  }

  private fun mutable(s: MusicPlayerSnapshot) = s.connection == MusicConnection.READY &&
      validId(s.sourceId) && s.pending == null && !s.audioOwnedByOther
  private fun currentTrack(s: MusicPlayerSnapshot) =
      s.track?.takeIf { validId(it.id) && it.matches(s.sourceId, it.id) }
  private fun can(s: MusicPlayerSnapshot, command: MusicCommand, trackBound: Boolean = true) =
      mutable(s) && s.can(command) && (!trackBound || currentTrack(s) != null)
  private fun validId(value: String?) = value != null && value.isNotBlank() && value.length <= MAX_TEXT
  private fun string(body: JSONObject, key: String) =
      (body.opt(key) as? String)?.takeIf(::validId)
  private fun integer(body: JSONObject, key: String): Long? = when (val value = body.opt(key)) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
  }
  private fun text(value: String) = value.take(MAX_TEXT)
  private fun duration(value: Long?) = value?.takeIf { it > 0 }
  private fun nullable(value: Any?): Any = value ?: JSONObject.NULL
  private fun wire(value: String) = value.lowercase(Locale.ROOT)
  private fun bad() = Rejected(400, "bad_request")
  private fun stale() = Rejected(409, "stale")
  private fun unavailable() = Rejected(409, "unavailable")
}
