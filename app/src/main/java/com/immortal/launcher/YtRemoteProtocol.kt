/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** One command a paired phone sends to `/remote/ytmusic`. */
internal sealed interface YtRemoteCommand {
  /** Commands that can start sound; quiet hours, a routine on screen or a call gate them. */
  val startsPlayback: Boolean get() = false

  data class Play(val playAnyway: Boolean) : YtRemoteCommand {
    override val startsPlayback: Boolean get() = true
  }
  data object Pause : YtRemoteCommand
  data object Next : YtRemoteCommand
  data object Previous : YtRemoteCommand
  data class Seek(val positionMs: Long) : YtRemoteCommand
  /** Skip to queue entry [entryId] and play it. */
  data class Jump(val entryId: Long, val playAnyway: Boolean) : YtRemoteCommand {
    override val startsPlayback: Boolean get() = true
  }
  data class Remove(val entryId: Long) : YtRemoteCommand
  data class PlayNext(val entryId: Long) : YtRemoteCommand
}

/** What the room around the player looks like when the phone asks. */
internal data class YtRemoteRoom(
    val quiet: Boolean,
    /** "7:00 AM" while [quiet], else null. */
    val quietUntil: String?,
    val routine: Boolean,
    val inCall: Boolean,
    /** The signed-in YouTube account's name, or null. */
    val account: String?,
    /** STREAM_MUSIC, or null when the audio service is unavailable. */
    val volume: RemoteMedia.VolumeState?,
)

/**
 * Pure wire format of `/remote/ytmusic` (the official-player queue, [YouTubeMusicEngine]):
 * GET → `{ok, player:{state, room, onScreen, ad, current, positionMs, durationMs, upNext[],
 * upNextTotal, label, repeat, shuffle, halt, signedIn, account, quiet, quietUntil, volume}}`;
 * POST `{cmd: play|pause|next|previous|seek|jump|remove|playnext, positionMs?, entryId?, playAnyway?}`.
 */
internal object YtRemoteProtocol {
  /** Up-next rows sent per poll; the phone shows "and N more" beyond this. */
  const val MAX_UP_NEXT = 50
  /** Seek bound (a day) so absurd numbers are refused rather than clamped silently. */
  const val MAX_POSITION_MS = 24L * 60 * 60 * 1000
  /** Bound on text fields copied from track metadata. */
  const val MAX_TEXT = 200

  sealed interface Decoded {
    data class Ok(val command: YtRemoteCommand) : Decoded
    data class Invalid(val code: String) : Decoded
  }

  fun decode(body: JSONObject): Decoded {
    val playAnyway = body.optBoolean("playAnyway", false)
    return when (body.optString("cmd")) {
      "play" -> Decoded.Ok(YtRemoteCommand.Play(playAnyway))
      "pause" -> Decoded.Ok(YtRemoteCommand.Pause)
      "next" -> Decoded.Ok(YtRemoteCommand.Next)
      "previous" -> Decoded.Ok(YtRemoteCommand.Previous)
      "seek" -> whole(body.opt("positionMs"), 0L..MAX_POSITION_MS)
          ?.let { Decoded.Ok(YtRemoteCommand.Seek(it)) } ?: Decoded.Invalid("invalid_position")
      "jump" -> entry(body) { YtRemoteCommand.Jump(it, playAnyway) }
      "remove" -> entry(body) { YtRemoteCommand.Remove(it) }
      "playnext" -> entry(body) { YtRemoteCommand.PlayNext(it) }
      else -> Decoded.Invalid("unknown_cmd")
    }
  }

  /** Why [command] can't apply to [s] (answered as 409), or null. */
  fun conflict(command: YtRemoteCommand, s: YtEngineState): String? {
    fun known(id: Long) = s.queue.entries.any { it.id == id }
    return when (command) {
      is YtRemoteCommand.Play -> if (s.queue.current == null) "nothing_queued" else null
      is YtRemoteCommand.Jump -> if (!known(command.entryId)) "unknown_entry" else null
      is YtRemoteCommand.Remove -> if (!known(command.entryId)) "unknown_entry" else null
      is YtRemoteCommand.PlayNext -> if (!known(command.entryId)) "unknown_entry" else null
      else -> null
    }
  }

  fun state(s: YtEngineState, nowElapsedMs: Long, room: YtRemoteRoom): JSONObject {
    val current = s.queue.current
    val playing = MusicV3NowPlaying.from(s, "").playing
    val after = if (current == null) emptyList() else s.queue.entries.drop(s.queue.currentIndex + 1)
    return JSONObject()
        .put("state", if (current == null) "idle" else if (playing) "playing" else "paused")
        .put("room", MusicSharePolicy.roomState(room.quiet, room.routine, room.inCall, playing))
        .put("onScreen", s.foreground)
        .put("ad", current != null && s.isUnknownItem)
        .put("current", current?.let(::entry) ?: JSONObject.NULL)
        .put("positionMs", if (current == null) 0L else MusicV3NowPlaying.livePositionMs(s, nowElapsedMs))
        .put("durationMs", if (current == null) 0L else MusicV3NowPlaying.durationMs(s))
        .put("upNext", JSONArray().also { array -> after.take(MAX_UP_NEXT).forEach { array.put(entry(it)) } })
        .put("upNextTotal", after.size)
        .put("label", s.queue.sourceLabel.take(MAX_TEXT))
        .put("repeat", s.queue.repeat.name.lowercase())
        .put("shuffle", s.queue.isShuffled)
        .put("halt", s.halt?.let(::halt) ?: JSONObject.NULL)
        .put("signedIn", room.account != null)
        .put("account", room.account?.take(MAX_TEXT) ?: JSONObject.NULL)
        .put("quiet", room.quiet)
        .put("quietUntil", room.quietUntil?.takeIf { room.quiet } ?: JSONObject.NULL)
        .put("volume", room.volume?.toJson() ?: JSONObject.NULL)
  }

  private fun entry(e: YtQueueEntry): JSONObject = JSONObject()
      .put("entryId", e.id)
      .put("videoId", e.track.videoId)
      .put("title", e.track.title.take(MAX_TEXT))
      .put("artist", e.track.artist.take(MAX_TEXT))
      .put("thumb", e.track.thumbnailUrl.ifBlank { MusicShareQueue.thumbnailFor(e.track.videoId) })
      .put("durationMs", e.track.durationMs ?: JSONObject.NULL)
      .put("unplayable", e.unplayableReason?.take(MAX_TEXT) ?: JSONObject.NULL)

  private fun halt(h: YtHalt): JSONObject {
    val code = when (h) {
      YtHalt.EndOfQueue -> "end_of_queue"
      is YtHalt.TooManyFailures -> "too_many_failures"
      is YtHalt.PlayerError -> "player_error"
      YtHalt.PlayerUnavailable -> "player_unavailable"
    }
    return JSONObject().put("code", code).put("title", MusicV3NowPlaying.haltMessage(h).title)
  }

  private fun entry(body: JSONObject, make: (Long) -> YtRemoteCommand): Decoded =
      whole(body.opt("entryId"), 1L..Long.MAX_VALUE)?.let { Decoded.Ok(make(it)) } ?: Decoded.Invalid("invalid_entry")

  /** Integer JSON numbers only (no strings, no fractions) within [range]. */
  private fun whole(value: Any?, range: LongRange): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
  }?.takeIf { it in range }
}
