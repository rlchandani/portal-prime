/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `presence` / `music_live` bodies: small, round-trip, and every field treated as untrusted. */
class FollowMeWireTest {
  private val w = FollowMeWire
  private val track = FollowMeTrack("Clair de Lune", "Debussy", "https://i.ytimg.com/vi/x/hq.jpg", 42_000L)

  @Test fun presenceRoundTrips() {
    val note = FollowMeWire.PresenceNote(Presence.ABSENT, 75_000L, "Family Area")
    assertEquals(note, w.decodePresence(w.encode(note)))
    val present = FollowMeWire.PresenceNote(Presence.PRESENT, 0L, "Living Area")
    assertEquals(present, w.decodePresence(w.encode(present)))
  }

  @Test fun liveRoundTripsPlayingAndStopped() {
    val playing = FollowMeWire.LiveNote(Presence.ABSENT, 61_000L, "Family Area", track)
    assertEquals(playing, w.decodeLive(w.encode(playing)))
    val stopped = FollowMeWire.LiveNote(Presence.PRESENT, 5_000L, "Family Area", null)
    assertEquals(stopped, w.decodeLive(w.encode(stopped)))
  }

  @Test fun bodiesStaySmall() {
    val long = FollowMeTrack("t".repeat(500), "a".repeat(500), "https://i.ytimg.com/" + "x".repeat(900), 1L)
    val body = w.encode(FollowMeWire.LiveNote(Presence.ABSENT, 1L, "n".repeat(200), long)).toString()
    assertTrue("music_live body is ${body.length} chars", body.length <= FollowMeWire.MAX_BODY_CHARS)
  }

  @Test fun decodeRejectsBadPresenceAndName() {
    assertNull(w.decodePresence(JSONObject().put("p", "z").put("d", 1).put("fn", "Kitchen")))
    assertNull(w.decodePresence(JSONObject().put("p", "a").put("d", 1)))
    assertNull(w.decodeLive(JSONObject().put("on", 1).put("p", "a").put("d", 1).put("fn", "Kitchen")))
  }

  @Test fun decodeClampsDurationsAndPosition() {
    val negative = w.decodePresence(JSONObject().put("p", "a").put("d", -5).put("fn", "Kitchen"))
    assertEquals(0L, negative?.forMs)
    val huge = w.decodePresence(JSONObject().put("p", "a").put("d", Long.MAX_VALUE).put("fn", "Kitchen"))
    assertEquals(FollowMeWire.MAX_FOR_MS, huge?.forMs)
    val missing = w.decodePresence(JSONObject().put("p", "u").put("fn", "Kitchen"))
    assertEquals(0L, missing?.forMs)
  }

  @Test fun decodeDropsNonHttpsThumbsAndBlankTitles() {
    val body = w.encode(FollowMeWire.LiveNote(Presence.ABSENT, 1L, "Kitchen", track)).put("th", "http://evil/x.jpg")
    assertEquals("", w.decodeLive(body)?.track?.thumb)
    val blank = w.encode(FollowMeWire.LiveNote(Presence.ABSENT, 1L, "Kitchen", track)).put("t", "  ")
    assertNull(w.decodeLive(blank))
  }

  @Test fun presenceCodes() {
    Presence.entries.forEach { assertEquals(it, FollowMeWire.presenceFromWire(FollowMeWire.presenceWire(it))) }
    assertNull(FollowMeWire.presenceFromWire("x"))
  }

  @Test fun liveCarriesHowLongNobodyHasTouchedThePortal() {
    val note = FollowMeWire.LiveNote(Presence.UNKNOWN, 0L, "Family Area",
        FollowMeTrack("Song", "Artist", "", 1_000L, idleMs = 400_000L))
    assertEquals(400_000L, FollowMeWire.decodeLive(FollowMeWire.encode(note))!!.track!!.idleMs)
    val older = FollowMeWire.encode(note.copy(track = note.track!!.copy(idleMs = null)))
    assertEquals(null, FollowMeWire.decodeLive(older)!!.track!!.idleMs)
  }
}
