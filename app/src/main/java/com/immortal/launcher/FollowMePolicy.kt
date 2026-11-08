/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** This Portal's "Music follows you" setting. [wire] is what [FollowMeConfig] stores. */
internal enum class FollowMeMode(val wire: String) {
  ASK("ask"),
  AUTO("auto"),
  OFF("off");

  companion object {
    /** Anything unknown (or never set) is the default, Ask first. */
    fun fromWire(text: String?): FollowMeMode = entries.firstOrNull { it.wire == text } ?: ASK
  }
}

/** What another room says it is playing ([positionMs] at the time it said so). */
internal data class FollowMeTrack(val title: String, val artist: String, val thumb: String, val positionMs: Long)

/**
 * What this Portal knows about another room, folded by [FollowMeRoster]. Times are this Portal's
 * clock: [presenceSinceMs] is when the room's current [presence] began, the `*SeenMs` when we last
 * heard about it. [live] is null when the room isn't playing.
 */
internal data class FollowMeRoom(
    val id: String,
    val name: String,
    val presence: Presence = Presence.UNKNOWN,
    val presenceSinceMs: Long? = null,
    val presenceSeenMs: Long = 0L,
    val live: FollowMeTrack? = null,
    val liveSeenMs: Long = 0L,
)

/** This Portal at the moment of deciding. [arrivedMs]: its latest absent→present transition. */
internal data class FollowMeHere(
    val id: String,
    val arrivedMs: Long?,
    val playing: Boolean,
    val moving: Boolean,
    val quiet: Boolean,
    val routine: Boolean,
    val inCall: Boolean,
)

internal sealed interface FollowMeDecision {
  /** Nothing to do; [reason] is one of the [FollowMePolicy] reason constants, for the logs. */
  data class None(val reason: String) : FollowMeDecision
  /** Ask first: show "Continue <title> here?" for [source]. */
  data class Ask(val source: FollowMeRoom) : FollowMeDecision
  /** Automatic: pull the music from [source] now. */
  data class Follow(val source: FollowMeRoom) : FollowMeDecision
}

/**
 * Pure follow-me rule, evaluated on the room R that someone just walked into. R offers (or takes)
 * the music of exactly one room S when:
 * - the setting isn't Off, and R became present at most [WINDOW_MS] ago;
 * - R isn't playing, mid-move, on a call, showing a routine or in quiet hours;
 * - S reported music within [LIVE_STALE_MS] and is absent (fresh report) for ≥ [ABSENT_MS], or
 *   became absent after R's arrival (its camera notices the empty room a little later);
 * - no other room qualifies as S, and no other idle room also became present around R's arrival
 *   (two arrivals: we can't tell who walked where);
 * - this pair of rooms hasn't followed in either direction within [COOLDOWN_MS].
 */
internal object FollowMePolicy {
  /** R keeps re-evaluating this long after its arrival. */
  const val WINDOW_MS = 60_000L
  /** S must have been empty this long (unless it emptied after R's arrival). */
  const val ABSENT_MS = 60_000L
  /** A `music_live` report older than this means S stopped or went away (reports come every 20 s). */
  const val LIVE_STALE_MS = 50_000L
  /** A presence report older than this is unknown (heartbeats come every 2 min). */
  const val PRESENCE_STALE_MS = 5 * 60_000L
  /** One follow per pair of rooms (either direction) in this long. */
  const val COOLDOWN_MS = 5 * 60_000L

  const val OFF = "off"
  const val NOT_ARRIVED = "not_arrived"
  const val WINDOW_OVER = "window_over"
  const val PLAYING_HERE = "playing_here"
  const val MOVING = "moving"
  const val CALL = "call"
  const val ROUTINE = "routine"
  const val QUIET = "quiet"
  const val NO_MUSIC = "no_music"
  const val SOURCE_PRESENT = "source_present"
  const val SOURCE_NOT_ABSENT = "source_not_absent"
  const val SEVERAL_SOURCES = "several_sources"
  const val OTHERS_PRESENT = "others_present"
  const val COOLDOWN = "cooldown"

  fun decide(
      mode: FollowMeMode,
      here: FollowMeHere,
      rooms: List<FollowMeRoom>,
      cooldowns: Map<String, Long>,
      nowMs: Long,
  ): FollowMeDecision {
    blockedHere(mode, here, nowMs)?.let { return FollowMeDecision.None(it) }
    val arrived = here.arrivedMs ?: return FollowMeDecision.None(NOT_ARRIVED)
    val others = rooms.filter { it.id != here.id }
    val playing = others.filter { isPlaying(it, nowMs) }
    if (playing.isEmpty()) return FollowMeDecision.None(NO_MUSIC)
    val sources = playing.filter { leftBehind(it, arrived, nowMs) }
    val source = when {
      sources.size > 1 -> return FollowMeDecision.None(SEVERAL_SOURCES)
      sources.isEmpty() ->
        return FollowMeDecision.None(if (playing.any { isPresent(it, nowMs) }) SOURCE_PRESENT else SOURCE_NOT_ABSENT)
      else -> sources.single()
    }
    if (others.any { it.id != source.id && arrivedAlongside(it, arrived, nowMs) }) return FollowMeDecision.None(OTHERS_PRESENT)
    val last = cooldowns[pairKey(here.id, source.id)]
    if (last != null && nowMs - last < COOLDOWN_MS) return FollowMeDecision.None(COOLDOWN)
    return if (mode == FollowMeMode.AUTO) FollowMeDecision.Follow(source) else FollowMeDecision.Ask(source)
  }

  /** The same key for a pair of rooms whichever way the music went. */
  fun pairKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"

  private fun blockedHere(mode: FollowMeMode, here: FollowMeHere, nowMs: Long): String? = when {
    mode == FollowMeMode.OFF -> OFF
    here.arrivedMs == null -> NOT_ARRIVED
    nowMs - here.arrivedMs !in 0..WINDOW_MS -> WINDOW_OVER
    here.playing -> PLAYING_HERE
    here.moving -> MOVING
    here.inCall -> CALL
    here.routine -> ROUTINE
    here.quiet -> QUIET
    else -> null
  }

  private fun isPlaying(r: FollowMeRoom, nowMs: Long) = r.live != null && nowMs - r.liveSeenMs <= LIVE_STALE_MS

  private fun fresh(r: FollowMeRoom, nowMs: Long) = r.presenceSinceMs != null && nowMs - r.presenceSeenMs <= PRESENCE_STALE_MS

  private fun isPresent(r: FollowMeRoom, nowMs: Long) = fresh(r, nowMs) && r.presence == Presence.PRESENT

  private fun leftBehind(r: FollowMeRoom, arrivedMs: Long, nowMs: Long): Boolean {
    if (!fresh(r, nowMs) || r.presence != Presence.ABSENT) return false
    val since = r.presenceSinceMs ?: return false
    return nowMs - since >= ABSENT_MS || since >= arrivedMs
  }

  /** Another idle room that also lit up around R's arrival: someone else may be the listener. */
  private fun arrivedAlongside(r: FollowMeRoom, arrivedMs: Long, nowMs: Long): Boolean {
    if (!isPresent(r, nowMs) || isPlaying(r, nowMs)) return false
    val since = r.presenceSinceMs ?: return false
    return since >= arrivedMs - WINDOW_MS
  }
}
