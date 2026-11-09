/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** When music follows someone from the room they left into the room they walked into. */
class FollowMePolicyTest {
  private val p = FollowMePolicy
  private val arrived = 10_000_000L
  private val now = arrived + 5_000L
  private val track = FollowMeTrack("Clair de Lune", "Debussy", "https://i.ytimg.com/vi/x/hq.jpg", 42_000L)
  private val here = FollowMeHere("living", arrivedMs = arrived, playing = false, moving = false,
      quiet = false, routine = false, inCall = false)

  /** The Family Area plays music and has been empty for two minutes. */
  private val family = FollowMeRoom("family", "Family Area", Presence.ABSENT,
      presenceSinceMs = arrived - 120_000L, presenceSeenMs = now - 1_000L, live = track, liveSeenMs = now - 2_000L)

  private fun decide(
      mode: FollowMeMode = FollowMeMode.ASK,
      self: FollowMeHere = here,
      rooms: List<FollowMeRoom> = listOf(family),
      cooldowns: Map<String, Long> = emptyMap(),
      at: Long = now,
  ) = p.decide(mode, self, rooms, cooldowns, at)

  private fun none(reason: String) = FollowMeDecision.None(reason)

  @Test fun askFirstOffersTheMusicFromTheEmptyRoom() {
    assertEquals(FollowMeDecision.Ask(family), decide())
  }

  @Test fun automaticFollowsStraightAway() {
    assertEquals(FollowMeDecision.Follow(family), decide(FollowMeMode.AUTO))
  }

  @Test fun offDoesNothing() {
    assertEquals(none(FollowMePolicy.OFF), decide(FollowMeMode.OFF))
  }

  @Test fun onlyRightAfterAnArrival() {
    assertEquals(none(FollowMePolicy.NOT_ARRIVED), decide(self = here.copy(arrivedMs = null)))
    assertEquals(none(FollowMePolicy.WINDOW_OVER), decide(at = arrived + FollowMePolicy.WINDOW_MS + 1))
    val stillFresh = family.copy(presenceSeenMs = arrived + FollowMePolicy.WINDOW_MS, liveSeenMs = arrived + FollowMePolicy.WINDOW_MS)
    assertEquals(FollowMeDecision.Ask(stillFresh), decide(rooms = listOf(stillFresh), at = arrived + FollowMePolicy.WINDOW_MS))
  }

  @Test fun busyHereBlocks() {
    assertEquals(none(FollowMePolicy.PLAYING_HERE), decide(self = here.copy(playing = true)))
    assertEquals(none(FollowMePolicy.MOVING), decide(self = here.copy(moving = true)))
    assertEquals(none(FollowMePolicy.CALL), decide(self = here.copy(inCall = true)))
    assertEquals(none(FollowMePolicy.ROUTINE), decide(self = here.copy(routine = true)))
    assertEquals(none(FollowMePolicy.QUIET), decide(self = here.copy(quiet = true)))
  }

  @Test fun nothingPlayingAnywhere() {
    assertEquals(none(FollowMePolicy.NO_MUSIC), decide(rooms = listOf(family.copy(live = null))))
    assertEquals(none(FollowMePolicy.NO_MUSIC), decide(rooms = emptyList()))
  }

  @Test fun staleMusicReportCountsAsNothingPlaying() {
    val stale = family.copy(liveSeenMs = now - FollowMePolicy.LIVE_STALE_MS - 1)
    assertEquals(none(FollowMePolicy.NO_MUSIC), decide(rooms = listOf(stale)))
  }

  @Test fun sourceStillOccupiedDoesNothing() {
    val occupied = family.copy(presence = Presence.PRESENT, presenceSinceMs = arrived - 600_000L)
    assertEquals(none(FollowMePolicy.SOURCE_PRESENT), decide(rooms = listOf(occupied)))
  }

  @Test fun sourceUnknownOrStalePresenceDoesNothing() {
    val unknown = family.copy(presence = Presence.UNKNOWN)
    assertEquals(none(FollowMePolicy.SOURCE_NOT_ABSENT), decide(rooms = listOf(unknown)))
    val stale = family.copy(presenceSeenMs = now - FollowMePolicy.PRESENCE_STALE_MS - 1)
    assertEquals(none(FollowMePolicy.SOURCE_NOT_ABSENT), decide(rooms = listOf(stale)))
  }

  @Test fun sourceMustBeEmptyForAMinute() {
    val justLeft = family.copy(presenceSinceMs = now - FollowMePolicy.ABSENT_MS + 1)
    assertEquals(none(FollowMePolicy.SOURCE_NOT_ABSENT), decide(rooms = listOf(justLeft)))
    val aMinute = family.copy(presenceSinceMs = now - FollowMePolicy.ABSENT_MS)
    assertEquals(FollowMeDecision.Ask(aMinute), decide(rooms = listOf(aMinute)))
  }

  @Test fun sourceEmptyingAfterTheArrivalCounts() {
    // The camera in the room they left notices a little after the one they walked into.
    val emptiedLater = family.copy(presenceSinceMs = arrived + 40_000L, presenceSeenMs = arrived + 41_000L)
    assertEquals(FollowMeDecision.Ask(emptiedLater), decide(rooms = listOf(emptiedLater), at = arrived + 41_000L))
  }

  @Test fun twoPlayingEmptyRoomsAreAmbiguous() {
    val kitchen = family.copy(id = "kitchen", name = "Kitchen")
    assertEquals(none(FollowMePolicy.SEVERAL_SOURCES), decide(rooms = listOf(family, kitchen)))
  }

  @Test fun anotherRoomArrivingTooIsAmbiguous() {
    val kitchen = FollowMeRoom("kitchen", "Kitchen", Presence.PRESENT, presenceSinceMs = arrived + 2_000L,
        presenceSeenMs = now)
    assertEquals(none(FollowMePolicy.OTHERS_PRESENT), decide(rooms = listOf(family, kitchen)))
  }

  @Test fun aRoomOccupiedLongBeforeDoesNotBlock() {
    val study = FollowMeRoom("study", "Study", Presence.PRESENT, presenceSinceMs = arrived - 3_600_000L, presenceSeenMs = now)
    assertEquals(FollowMeDecision.Ask(family), decide(rooms = listOf(family, study)))
  }

  @Test fun aRoomPlayingItsOwnMusicDoesNotBlock() {
    val kitchen = FollowMeRoom("kitchen", "Kitchen", Presence.PRESENT, presenceSinceMs = arrived + 1_000L,
        presenceSeenMs = now, live = track, liveSeenMs = now)
    assertEquals(FollowMeDecision.Ask(family), decide(rooms = listOf(family, kitchen)))
  }

  @Test fun selfIsNeverASource() {
    val echo = family.copy(id = "living")
    assertEquals(none(FollowMePolicy.NO_MUSIC), decide(rooms = listOf(echo)))
  }

  @Test fun cooldownPerPairInEitherDirection() {
    val key = FollowMePolicy.pairKey("family", "living")
    assertEquals(key, FollowMePolicy.pairKey("living", "family"))
    val recent = mapOf(key to now - FollowMePolicy.COOLDOWN_MS + 1)
    assertEquals(none(FollowMePolicy.COOLDOWN), decide(cooldowns = recent))
    val old = mapOf(key to now - FollowMePolicy.COOLDOWN_MS)
    assertEquals(FollowMeDecision.Ask(family), decide(cooldowns = old))
    val otherPair = mapOf(FollowMePolicy.pairKey("kitchen", "living") to now)
    assertEquals(FollowMeDecision.Ask(family), decide(cooldowns = otherPair))
  }

  @Test fun modeFromWireDefaultsToAskFirst() {
    assertEquals(FollowMeMode.AUTO, FollowMeMode.fromWire("auto"))
    assertEquals(FollowMeMode.OFF, FollowMeMode.fromWire("off"))
    assertEquals(FollowMeMode.ASK, FollowMeMode.fromWire("ask"))
    assertEquals(FollowMeMode.ASK, FollowMeMode.fromWire("nonsense"))
    assertEquals(FollowMeMode.ASK, FollowMeMode.fromWire(null))
  }

  /**
   * Meta's camera presence goes quiet while Hearthpane's music screen is in front ("top app is not
   * whitelisted"), so the playing room often reports UNKNOWN. Ask first then treats a room nobody
   * has touched for [FollowMePolicy.IDLE_MS] as possibly empty; Automatic still needs ABSENT.
   */
  private val unknownIdle = family.copy(presence = Presence.UNKNOWN, presenceSinceMs = arrived - 600_000L,
      live = track.copy(idleMs = FollowMePolicy.IDLE_MS))

  @Test fun askFirstOffersFromAPlayingRoomNobodyHasTouchedLately() {
    assertEquals(FollowMeDecision.Ask(unknownIdle), decide(rooms = listOf(unknownIdle)))
  }

  @Test fun automaticStillNeedsTheRoomToBeSeenEmpty() {
    assertEquals(none(FollowMePolicy.SOURCE_NOT_ABSENT), decide(FollowMeMode.AUTO, rooms = listOf(unknownIdle)))
  }

  @Test fun aRecentTouchMeansSomeoneIsStillListening() {
    val touched = unknownIdle.copy(live = track.copy(idleMs = 30_000L))
    assertEquals(none(FollowMePolicy.SOURCE_NOT_ABSENT), decide(rooms = listOf(touched)))
    val unknownIdleTime = unknownIdle.copy(live = track.copy(idleMs = null))
    assertEquals(none(FollowMePolicy.SOURCE_NOT_ABSENT), decide(rooms = listOf(unknownIdleTime)))
  }

  @Test fun idleTimeKeepsCountingSinceTheLastReport() {
    // Reported 4 min 50 s idle, 20 s ago: idle for over 5 minutes now.
    val aging = unknownIdle.copy(live = track.copy(idleMs = FollowMePolicy.IDLE_MS - 10_000L), liveSeenMs = now - 20_000L)
    assertEquals(FollowMeDecision.Ask(aging), decide(rooms = listOf(aging)))
  }

  @Test fun aRoomSeenOccupiedIsNeverTreatedAsIdle() {
    val present = unknownIdle.copy(presence = Presence.PRESENT)
    assertEquals(none(FollowMePolicy.SOURCE_PRESENT), decide(rooms = listOf(present)))
  }
}
