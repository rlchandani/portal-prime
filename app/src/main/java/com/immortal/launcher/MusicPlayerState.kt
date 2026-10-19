/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Availability is explicit: a successful socket connection alone is not READY. */
enum class MusicConnection {
  NOT_CONFIGURED, CONNECTING, READY, OFFLINE, AUTH_REQUIRED, INCOMPATIBLE,
}

enum class MusicCommand {
  PLAY, PAUSE, NEXT, PREVIOUS, SEEK, OPEN_QUEUE, SELECT_QUEUE_ITEM, OPEN_LYRICS,
  CHANGE_ROOMS, SET_VOLUME, SHUFFLE, REPEAT, FAVORITE,
}

/** Commands that can begin output or replace its current stream position. */
internal fun MusicCommand.requiresAudioStartOrReposition(): Boolean = when (this) {
  MusicCommand.PLAY, MusicCommand.NEXT, MusicCommand.PREVIOUS, MusicCommand.SEEK,
  MusicCommand.SELECT_QUEUE_ITEM -> true
  else -> false
}

/** Unknown capabilities default to unavailable, including local media sessions. */
data class MusicCapabilities(
    val play: Boolean = false,
    val pause: Boolean = false,
    val next: Boolean = false,
    val previous: Boolean = false,
    val seek: Boolean = false,
    val queue: Boolean = false,
    val lyrics: Boolean = false,
    val rooms: Boolean = false,
    val volume: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val favorite: Boolean = false,
)

data class MusicTrack(
    val id: String,
    val sourceId: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val artUrl: String = "",
    val durationMs: Long? = null,
    val isLive: Boolean = false,
) {
  /** Async artwork/queue results must still belong to the selected source AND track. */
  fun matches(source: String, trackId: String): Boolean =
      sourceId.isNotBlank() && id.isNotBlank() && sourceId == source && id == trackId
}

data class MusicQueueItem(
    val id: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long? = null,
    val artUrl: String = "",
    val available: Boolean = true,
)

/** Adapters normalize these into ascending time order before publication. */
data class MusicLyricLine(val timeMs: Long, val text: String)

data class MusicRoom(
    val id: String,
    val name: String,
    val available: Boolean,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val groupId: String? = null,
    val canGroupWith: Set<String> = emptySet(),
    val volumeControllable: Boolean = false,
    /** Removable membership in the currently selected server-confirmed group. */
    val removableFromGroup: Boolean = false,
)

data class PendingMusicCommand(
    val requestId: Long,
    val sourceId: String,
    val command: MusicCommand,
    val startedAtElapsedMs: Long,
) {
  fun confirmedBy(replyId: Long, replySource: String): Boolean =
      sourceId.isNotBlank() && requestId == replyId && sourceId == replySource
}

/** Fixed, read-safe outcome retained until the next command or source selection. */
enum class MusicCommandOutcome { CONFIRMED, FAILED, TIMED_OUT, DISCONNECTED }

data class CompletedMusicCommand(
    val requestId: Long,
    val command: MusicCommand,
    val outcome: MusicCommandOutcome,
)

/**
 * Shared presentation state, independent of the full/compact/photo view.
 * Adapters publish bounded copies of collection values; consumers never mutate them.
 */
data class MusicPlayerSnapshot(
    val connection: MusicConnection = MusicConnection.NOT_CONFIGURED,
    val sourceId: String = "",
    val track: MusicTrack? = null,
    val playback: PlaybackState = PlaybackState.IDLE,
    val positionMs: Long = 0,
    val positionAtElapsedMs: Long = 0,
    val capabilities: MusicCapabilities = MusicCapabilities(),
    val queue: List<MusicQueueItem> = emptyList(),
    val lyrics: List<MusicLyricLine> = emptyList(),
    val rooms: List<MusicRoom> = emptyList(),
    val pending: PendingMusicCommand? = null,
    val lastCommand: CompletedMusicCommand? = null,
    val audioOwnedByOther: Boolean = false,
    val problem: String? = null,
    /** Shared session artwork is read-only here; the player does not recycle it. */
    val artBitmap: android.graphics.Bitmap? = null,
    val localSessionToken: android.media.session.MediaSession.Token? = null,
    /** Canonical MA leader ID supplied by the adapter, never guessed from room order. */
    val controlPlayerId: String? = null,
    /** Connection identity carried atomically with the immutable rendered observation. */
    val presentationRevision: Long = 0,
    /** Authoritative MA queue identity, independent of the current track. */
    val queueId: String? = null,
) {
  fun can(command: MusicCommand): Boolean {
    if (connection != MusicConnection.READY || sourceId.isBlank()) return false
    if (audioOwnedByOther && command.requiresAudioStartOrReposition()) return false
    if (track != null && track.sourceId != sourceId) return false
    val live = track?.isLive == true
    return when (command) {
      MusicCommand.PLAY -> capabilities.play
      MusicCommand.PAUSE -> capabilities.pause
      MusicCommand.NEXT -> capabilities.next && !live
      MusicCommand.PREVIOUS -> capabilities.previous && !live
      MusicCommand.SEEK -> capabilities.seek && !live && (track?.durationMs ?: 0) > 0
      MusicCommand.OPEN_QUEUE -> capabilities.queue
      MusicCommand.SELECT_QUEUE_ITEM -> capabilities.queue && !queueId.isNullOrBlank()
      MusicCommand.OPEN_LYRICS -> capabilities.lyrics
      MusicCommand.CHANGE_ROOMS -> capabilities.rooms
      MusicCommand.SET_VOLUME -> capabilities.volume
      MusicCommand.SHUFFLE -> capabilities.shuffle
      MusicCommand.REPEAT -> capabilities.repeat
      MusicCommand.FAVORITE -> capabilities.favorite
    }
  }

  /** MA's shared stream timeline remains authoritative when local output is interrupted. */
  internal val advancesVisiblePosition: Boolean get() =
      playback == PlaybackState.PLAYING && connection == MusicConnection.READY &&
          (!audioOwnedByOther || (sourceId.startsWith("ma:") && sourceId.length > 3 &&
              track?.sourceId == sourceId))

  /**
   * Monotonic extrapolation for the visible progress element only. A hidden UI does not
   * need to call this or subscribe to ticks. No fictional duration for radio/unknown media.
   */
  fun visiblePositionMs(nowElapsedMs: Long): Long? {
    val media = track ?: return null
    if (media.isLive) return null
    val duration = media.durationMs?.takeIf { it > 0 } ?: return null
    val base = positionMs.coerceIn(0, duration)
    if (!advancesVisiblePosition) return base
    val elapsed = (nowElapsedMs.coerceAtLeast(0) - positionAtElapsedMs.coerceAtLeast(0))
        .coerceAtLeast(0)
    return if (elapsed >= duration - base) duration else base + elapsed
  }
}

/** Last reached line in normalized timed lyrics; -1 means no line has begun. */
fun musicLyricIndex(lines: List<MusicLyricLine>, positionMs: Long): Int {
  var low = 0
  var high = lines.lastIndex
  var reached = -1
  while (low <= high) {
    val middle = low + (high - low) / 2
    if (lines[middle].timeMs <= positionMs) {
      reached = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  return reached
}
