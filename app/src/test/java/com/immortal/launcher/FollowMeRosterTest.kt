/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What this Portal remembers about the other rooms, folded from their broadcasts (immutably). */
class FollowMeRosterTest {
  private val r = FollowMeRoster
  private val now = 5_000_000L
  private val track = FollowMeTrack("Clair de Lune", "Debussy", "", 42_000L)

  @Test fun presenceSetsSinceFromTheReportedDuration() {
    val rooms = r.onPresence(emptyMap(), "family", FollowMeWire.PresenceNote(Presence.ABSENT, 30_000L, "Family Area"), now)
    assertEquals(FollowMeRoom("family", "Family Area", Presence.ABSENT, now - 30_000L, now), rooms["family"])
  }

  @Test fun samePresenceKeepsTheOriginalSince() {
    val first = r.onPresence(emptyMap(), "family", FollowMeWire.PresenceNote(Presence.ABSENT, 0L, "Family Area"), now)
    val again = r.onPresence(first, "family", FollowMeWire.PresenceNote(Presence.ABSENT, 25_000L, "Family Area"), now + 20_000L)
    assertEquals(now, again["family"]?.presenceSinceMs)
    assertEquals(now + 20_000L, again["family"]?.presenceSeenMs)
    assertEquals(Presence.ABSENT, first["family"]?.presence) // the input map is untouched
    assertEquals(now, first["family"]?.presenceSeenMs)
  }

  @Test fun aChangedPresenceRestartsSince() {
    val absent = r.onPresence(emptyMap(), "family", FollowMeWire.PresenceNote(Presence.ABSENT, 0L, "Family Area"), now)
    val present = r.onPresence(absent, "family", FollowMeWire.PresenceNote(Presence.PRESENT, 1_000L, "Family Area"), now + 9_000L)
    assertEquals(now + 8_000L, present["family"]?.presenceSinceMs)
  }

  @Test fun liveRecordsTheTrackAndPresence() {
    val rooms = r.onLive(emptyMap(), "family", FollowMeWire.LiveNote(Presence.ABSENT, 61_000L, "Family Area", track), now)
    val room = rooms.getValue("family")
    assertEquals(track, room.live)
    assertEquals(now, room.liveSeenMs)
    assertEquals(now - 61_000L, room.presenceSinceMs)
  }

  @Test fun stoppedLiveClearsTheTrack() {
    val playing = r.onLive(emptyMap(), "family", FollowMeWire.LiveNote(Presence.ABSENT, 0L, "Family Area", track), now)
    val stopped = r.onLive(playing, "family", FollowMeWire.LiveNote(Presence.ABSENT, 1_000L, "Family Area", null), now + 1_000L)
    assertNull(stopped.getValue("family").live)
  }

  @Test fun forgetsRoomsNotHeardFromForLong() {
    val rooms = r.onPresence(emptyMap(), "family", FollowMeWire.PresenceNote(Presence.ABSENT, 0L, "Family Area"), now)
    assertEquals(rooms, r.prune(rooms, now + FollowMeRoster.FORGET_MS))
    assertTrue(r.prune(rooms, now + FollowMeRoster.FORGET_MS + 1).isEmpty())
  }

  @Test fun sendWaitHonoursTheMinimumGap() {
    assertEquals(0L, FollowMeCadence.waitMs(null, now))
    assertEquals(0L, FollowMeCadence.waitMs(now - FollowMeCadence.MIN_GAP_MS, now))
    assertEquals(1_000L, FollowMeCadence.waitMs(now - FollowMeCadence.MIN_GAP_MS + 1_000L, now))
  }
}
