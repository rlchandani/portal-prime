/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Pure rules for this Portal's own room in "Music follows you". */
internal object FollowMeArrival {
  /** An unknown room that stays unknown this long and then sees someone counts as an arrival. */
  const val UNKNOWN_MS = 60_000L

  /**
   * What to tell the other rooms. A reading [PresenceHub] marks not confident (Meta's camera
   * presence pauses while Hearthpane's own screens are in front, so the last verdict is only a
   * guess) is reported as UNKNOWN, never as "someone is still here".
   */
  fun local(presence: Presence, confident: Boolean): Presence = if (confident) presence else Presence.UNKNOWN

  /** Someone walked in: PRESENT after an empty room, or after a room we knew nothing about for a while. */
  fun isArrival(previous: Presence, previousSinceMs: Long, next: Presence, nowMs: Long): Boolean = when {
    next != Presence.PRESENT -> false
    previous == Presence.ABSENT -> true
    previous == Presence.UNKNOWN -> nowMs - previousSinceMs >= UNKNOWN_MS
    else -> false
  }
}
