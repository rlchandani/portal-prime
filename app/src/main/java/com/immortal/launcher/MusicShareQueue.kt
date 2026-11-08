/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import kotlin.random.Random

/** The queue a share produces, plus what the phone's confirmation needs to say. */
internal data class MusicShareOutcome(
    val queue: YtQueue,
    /** What actually happened: every action becomes [MusicShareAction.NOW] when nothing was playing. */
    val effective: MusicShareAction,
    /** Title of the song a "Play now" interrupted (it resumes right after). */
    val interrupted: String?,
    /** Title of the playing song a "Play next" waits for. */
    val before: String?,
    /** Songs ahead of an "Add to queue" item, not counting the one playing. */
    val ahead: Int,
    val sharedIds: List<Long>,
) {
  /** True when the player must load the new current entry. */
  val restart: Boolean get() = effective == MusicShareAction.NOW
}

/**
 * Pure queue rules for music shared from a phone (prototype decisions): what's playing is never
 * replaced or lost. Nothing playing: the share plays at once and the old Up next follows. Play now:
 * jumps in, and the interrupted song follows from where it stopped. Play next: after the current
 * song. Add to queue: the end. Only the first shared row carries the sender tag.
 */
internal object MusicShareQueue {
  const val MAX_TRACKS = 200

  /** Valid ids only, capped at [MAX_TRACKS], then shuffled locally when asked. */
  fun prepare(tracks: List<YtTrack>, shuffle: Boolean, seed: Long): List<YtTrack> {
    val capped = tracks.filter { YtTrack.isValidVideoId(it.videoId) }.take(MAX_TRACKS)
    return if (shuffle) capped.shuffled(Random(seed)) else capped
  }

  /** Null when no track is playable. [playing] is whether the room is playing right now. */
  fun apply(queue: YtQueue, playing: Boolean, positionMs: Long, tracks: List<YtTrack>,
      action: MusicShareAction, from: String, label: String): MusicShareOutcome? {
    val valid = tracks.filter { YtTrack.isValidVideoId(it.videoId) }
    if (valid.isEmpty()) return null
    val fresh = valid.mapIndexed { i, t -> YtQueueEntry(queue.nextId + i, t, sharedBy = from.takeIf { i == 0 }) }
    val ids = fresh.map { it.id }
    val current = queue.current
    val after = if (current == null) emptyList() else queue.entries.drop(queue.currentIndex + 1)
    val nextId = queue.nextId + fresh.size
    if (!playing || current == null) {
      val q = queue.copy(entries = fresh + after, currentIndex = 0, sourceLabel = label, unshuffledIds = null, nextId = nextId)
      return MusicShareOutcome(q, MusicShareAction.NOW, null, null, 0, ids)
    }
    return when (action) {
      MusicShareAction.NOW -> {
        val resumed = current.copy(resumeMs = positionMs.coerceAtLeast(0L))
        val q = queue.copy(entries = fresh + resumed + after, currentIndex = 0, sourceLabel = label,
            unshuffledIds = null, nextId = nextId)
        MusicShareOutcome(q, action, current.track.title, null, 0, ids)
      }
      MusicShareAction.NEXT -> {
        val entries = queue.entries.take(queue.currentIndex + 1) + fresh + after
        val order = queue.unshuffledIds?.let { o -> val at = o.indexOf(current.id) + 1; o.take(at) + ids + o.drop(at) }
        MusicShareOutcome(queue.copy(entries = entries, unshuffledIds = order, nextId = nextId), action, null,
            current.track.title, 0, ids)
      }
      MusicShareAction.END -> MusicShareOutcome(queue.copy(entries = queue.entries + fresh,
          unshuffledIds = queue.unshuffledIds?.plus(ids), nextId = nextId), action, null, current.track.title, after.size, ids)
    }
  }

  /** Placeholder rows for playlist ids whose titles aren't known yet (the player fills them in). */
  fun placeholders(videoIds: List<String>, listName: String): List<YtTrack> =
      videoIds.mapIndexed { i, id -> YtTrack(id, "Song ${i + 1}", listName, thumbnailFor(id)) }

  fun thumbnailFor(videoId: String): String = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

  /** Right-pane toast (prototype copy); null for a plain "Play now" (the arrival line says it). */
  fun toast(outcome: MusicShareOutcome, from: String, label: String): String? = when {
    outcome.effective == MusicShareAction.NEXT -> "$from added $label · Up next"
    outcome.effective == MusicShareAction.END -> "$from added $label to the end of the queue"
    outcome.interrupted != null -> "“${outcome.interrupted}” carries on after this"
    else -> null
  }

  /** "“Saltwater hours”" for a song, "24 songs from Sunday slow" for a playlist. */
  fun label(kind: MusicShareKind, title: String, count: Int): String = when {
    kind == MusicShareKind.VIDEO -> "“${title.ifBlank { "a song" }}”"
    title.isBlank() -> if (count == 1) "1 song" else "$count songs"
    else -> "${if (count == 1) "1 song" else "$count songs"} from $title"
  }
}

/** The receiving Portal's rules for a share, reusing "Move to…"'s decline rules ([MusicTargetPolicy]). */
internal object MusicSharePolicy {
  /** Volume cap for "Play anyway, quietly" (prototype: 3 of 15). */
  const val QUIET_FRACTION = 0.2f
  const val ARRIVAL_MS = 6_500L

  enum class Decision { ACCEPT, ACCEPT_QUIETLY, QUIET, ROUTINE, CALL }

  fun decide(quiet: Boolean, routine: Boolean, inCall: Boolean, playAnyway: Boolean): Decision =
      when (MusicTargetPolicy.decide(quiet, routine, inCall, recall = playAnyway)) {
        MusicHandoffStatus.DECLINED_CALL -> Decision.CALL
        MusicHandoffStatus.DECLINED_ROUTINE -> Decision.ROUTINE
        MusicHandoffStatus.DECLINED_QUIET -> Decision.QUIET
        else -> if (quiet) Decision.ACCEPT_QUIETLY else Decision.ACCEPT
      }

  /** Where a shared playlist's songs come from. */
  enum class PlaylistSource { LIBRARY, PLAYER, NEEDS_SIGN_IN }

  /**
   * Signed in: the data layer lists it. Signed out: only the ONE visible player can open it, and
   * cueing replaces what that player shows, so it's allowed only when the playlist is about to
   * play anyway (nothing playing, or Play now). Play next / Add to queue over a playing song on
   * a signed-out Portal is declined with [SIGN_IN_REASON] rather than interrupting it.
   */
  fun playlistSource(signedIn: Boolean, playing: Boolean, action: MusicShareAction): PlaylistSource = when {
    signedIn -> PlaylistSource.LIBRARY
    !playing || action == MusicShareAction.NOW -> PlaylistSource.PLAYER
    else -> PlaylistSource.NEEDS_SIGN_IN
  }

  /** Fallback when the signed-in library can't list it: the visible player, if that's allowed. */
  fun libraryFallback(playing: Boolean, action: MusicShareAction): PlaylistSource =
      playlistSource(signedIn = false, playing = playing, action = action)

  fun signInReason(room: String): String =
      "Sign in to YouTube on $room to queue playlists. Play now works without an account."

  /** Reason the phone shows for a decline. */
  fun reason(decision: Decision, room: String): String? = when (decision) {
    Decision.ROUTINE -> "A routine is on screen in $room. Nothing was played."
    Decision.CALL -> "$room is on a call. Nothing was played."
    else -> null
  }

  /** The GET `state`: why a send would be refused first, then whether something is playing. */
  fun roomState(quiet: Boolean, routine: Boolean, inCall: Boolean, playing: Boolean): String = when {
    routine || inCall -> "routine"
    quiet -> "quiet"
    playing -> "playing"
    else -> "idle"
  }

  /** Never louder than now; at most [QUIET_FRACTION] of the maximum, and at least audible. */
  fun quietStep(current: Int, max: Int): Int {
    if (max <= 0) return current
    val cap = Math.round(max * QUIET_FRACTION).coerceAtLeast(1)
    return current.coerceAtMost(cap)
  }

  /** "7:00 AM" for minute-of-day 420. */
  fun clock(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    val hour = m / 60
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return String.format(Locale.ROOT, "%d:%02d %s", h12, m % 60, if (hour < 12) "AM" else "PM")
  }
}
