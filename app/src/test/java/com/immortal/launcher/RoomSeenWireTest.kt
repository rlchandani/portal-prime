/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `room_seen`: sealed, bound to its sender, minimal, and refused when anything is off. */
class RoomSeenWireTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }
  private val from = "portal-kitchen-1"
  private val seen = RoomSeenWire.Seen("mayaaaaa", "Maya", 3, "Kitchen Portal", "kitchen", 1_500L)

  private fun body(s: RoomSeenWire.Seen = seen) = RoomSeenWire.encode(s, key, from)!!

  @Test fun roundTrips() {
    assertEquals(seen, RoomSeenWire.decode(body(), key, from))
    val noRoom = seen.copy(roomId = null)
    assertNull(RoomSeenWire.decode(body(noRoom), key, from)!!.roomId)
  }

  @Test fun nothingPersonalIsReadableOnTheLan() {
    val b = body()
    val text = b.toString()
    assertFalse("Maya" in text)
    assertFalse("Kitchen" in text)
    assertFalse("kitchen" in text)
    assertFalse("mayaaaaa" in text)
    assertEquals(setOf("v", "n", "s"), b.keys().asSequence().toSet())
  }

  @Test fun eachMessageIsFreshlySealed() {
    assertTrue(body().getString("s") != body().getString("s"))
  }

  @Test fun theWrongKeyOrSenderCannotOpenIt() {
    assertNull(RoomSeenWire.decode(body(), other, from))
    assertNull("replayed as another Portal", RoomSeenWire.decode(body(), key, "portal-living-2"))
  }

  @Test fun tamperingIsRefused() {
    val b = body()
    val s = b.getString("s")
    val flipped = s.substring(0, s.length - 1) + (if (s.last() == '0') '1' else '0')
    assertNull(RoomSeenWire.decode(JSONObject(b.toString()).put("s", flipped), key, from))
    assertNull("nonce swapped", RoomSeenWire.decode(JSONObject(b.toString()).put("n", "00112233aabbccdd"), key, from))
    assertNull("version", RoomSeenWire.decode(JSONObject(b.toString()).put("v", 2), key, from))
    assertNull("too long", RoomSeenWire.decode(JSONObject(b.toString()).put("s", "a".repeat(901)), key, from))
    assertNull("not hex", RoomSeenWire.decode(JSONObject(b.toString()).put("s", "zz"), key, from))
    assertNull("short", RoomSeenWire.decode(JSONObject(b.toString()).put("s", "00"), key, from))
    assertNull("bad nonce", RoomSeenWire.decode(JSONObject(b.toString()).put("n", "x"), key, from))
  }

  @Test fun forgetCarriesOnlyTheIdAndPortal() {
    val f = RoomSeenWire.Seen("mayaaaaa", "Maya", 3, "Kitchen Portal", "kitchen", 0L, forget = true)
    val back = RoomSeenWire.decode(body(f), key, from)!!
    assertTrue(back.forget)
    assertEquals("", back.name)
    assertNull(back.roomId)
    assertEquals("mayaaaaa", back.personId)
  }

  @Test fun badFieldsAreNeverSentOrAccepted() {
    assertNull(RoomSeenWire.encode(seen.copy(personId = "Bad Id"), key, from))
    assertNull(RoomSeenWire.encode(seen.copy(name = "   "), key, from))
    val clipped = RoomSeenWire.decode(body(seen.copy(name = "A very long name indeed that keeps going")), key, from)!!
    assertEquals(WhosThereLimits.MAX_NAME, clipped.name.length)
    val clamped = RoomSeenWire.decode(body(seen.copy(colorIndex = 99, ageMs = -5)), key, from)!!
    assertEquals(63, clamped.colorIndex)
    assertEquals(0L, clamped.ageMs)
    // Age at the limit is refused on arrival (it has already left the map).
    assertNull(RoomSeenWire.decode(body(seen.copy(ageMs = RoomSeenWire.MAX_AGE_MS + 10)), key, from))
    assertNull("room id with spaces dropped", RoomSeenWire.decode(body(seen.copy(roomId = "a room")), key, from)!!.roomId)
  }

  @Test fun itFitsOneSignedHouseholdDatagram() {
    val big = seen.copy(name = "N".repeat(20), portalName = "P".repeat(40), roomId = "r".repeat(64), personId = "a".repeat(32))
    val msg = HouseholdMessage(RoomSeenWire.TYPE, "a".repeat(36), 1L, "b".repeat(24), RoomSeenWire.encode(big, key, "a".repeat(36))!!)
    val wire = HouseholdMessage.encodeOrNull(msg, key)
    assertNotNull(wire)
    assertTrue(wire!!.length <= HouseholdMessage.MAX_WIRE_CHARS)
  }

  @Test fun theSealKeyIsDerivedNotTheHouseholdKeyItself() {
    assertFalse(RoomSeenWire.key(key).contentEquals(key))
    assertFalse(RoomSeenWire.key(key).contentEquals(ReminderSeal.key(key)))
    assertEquals(32, RoomSeenWire.key(key).size)
  }

  @Test fun presenceCarriesAnOptionalRoom() {
    val n = FollowMeWire.PresenceNote(Presence.PRESENT, 5_000L, "Kitchen Portal", "kitchen")
    assertEquals(n, FollowMeWire.decodePresence(FollowMeWire.encode(n)))
    val none = FollowMeWire.PresenceNote(Presence.ABSENT, 0L, "Kitchen Portal")
    assertFalse(FollowMeWire.encode(none).has("rm"))
    assertNull(FollowMeWire.decodePresence(FollowMeWire.encode(none).put("rm", "bad room!"))!!.room)
    assertNull(FollowMeWire.decodePresence(FollowMeWire.encode(n.copy(room = "has space")))!!.room)
  }
}
