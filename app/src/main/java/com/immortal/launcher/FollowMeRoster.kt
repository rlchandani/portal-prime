/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Folds the other rooms' `presence` / `music_live` broadcasts into [FollowMeRoom]s keyed by device
 * id. Pure and immutable: every call returns a new map. Durations on the wire become this Portal's
 * clock here (`since = now - for`), so no two Portals' clocks are ever compared.
 */
internal object FollowMeRoster {
  /** A room silent for this long is dropped (it's off, or left the household). */
  const val FORGET_MS = 30 * 60_000L

  fun onPresence(rooms: Map<String, FollowMeRoom>, id: String, note: FollowMeWire.PresenceNote, nowMs: Long): Map<String, FollowMeRoom> =
      rooms + (id to withPresence(rooms[id] ?: FollowMeRoom(id, note.name), note.name, note.presence, note.forMs, nowMs))

  fun onLive(rooms: Map<String, FollowMeRoom>, id: String, note: FollowMeWire.LiveNote, nowMs: Long): Map<String, FollowMeRoom> {
    val room = withPresence(rooms[id] ?: FollowMeRoom(id, note.name), note.name, note.presence, note.forMs, nowMs)
    return rooms + (id to room.copy(live = note.track, liveSeenMs = nowMs))
  }

  fun prune(rooms: Map<String, FollowMeRoom>, nowMs: Long): Map<String, FollowMeRoom> =
      rooms.filterValues { nowMs - maxOf(it.presenceSeenMs, it.liveSeenMs) <= FORGET_MS }

  /** A repeated report keeps the original start; a changed presence restarts it from the report. */
  private fun withPresence(room: FollowMeRoom, name: String, presence: Presence, forMs: Long, nowMs: Long): FollowMeRoom {
    val since = if (room.presence == presence && room.presenceSinceMs != null) room.presenceSinceMs else nowMs - forMs
    return room.copy(name = name, presence = presence, presenceSinceMs = since, presenceSeenMs = nowMs)
  }
}

/** How often follow-me speaks on the household bus. */
internal object FollowMeCadence {
  /** `music_live` while playing. */
  const val LIVE_EVERY_MS = 20_000L
  /** `presence` heartbeat, so a room that stays put isn't forgotten ([FollowMePolicy.PRESENCE_STALE_MS]). */
  const val PRESENCE_EVERY_MS = 2 * 60_000L
  /** Changes closer together than this are coalesced into one message. */
  const val MIN_GAP_MS = 5_000L

  /** How long to hold a change-triggered message, given when the last one of its kind went out. */
  fun waitMs(lastSentMs: Long?, nowMs: Long): Long =
      if (lastSentMs == null) 0L else (MIN_GAP_MS - (nowMs - lastSentMs)).coerceAtLeast(0L)
}
