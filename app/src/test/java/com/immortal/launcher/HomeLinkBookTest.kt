/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/** Linked homes: permissions with safe defaults, immutable list rules and a strict codec. */
class HomeLinkBookTest {
  private val random = SecureRandom()
  private fun key() = HomeLinkIdentity.generate(random).publicKey
  private val rooms = listOf("Living room", "Kitchen", "Kids’ room", "Office")

  private fun link(name: String = "Lakeview", k: String = key(), at: Long = 5L) =
      HomeLink.verified(HomeLinkPeer(name, k), at, HomeLinkPermissions.defaults(rooms, quiet = setOf("Kids’ room", "Office")))

  @Test fun defaultsLetThemCallAndSeeAvailabilityAndRingOnlyTheUsualRooms() {
    val p = HomeLinkPermissions.defaults(rooms, quiet = setOf("Kids’ room", "Office"))
    assertTrue(p.canCall)
    assertTrue(p.seesAvailability)
    assertEquals(setOf("Living room", "Kitchen"), p.ringRooms)
  }

  @Test fun quietRoomsThatDontExistAreHarmless() {
    assertEquals(rooms.toSet(), HomeLinkPermissions.defaults(rooms, quiet = setOf("Garage")).ringRooms)
  }

  @Test fun ringsOnlyWhenCallsAreAllowedAndTheRoomIsChosen() {
    val p = HomeLinkPermissions.defaults(rooms, quiet = setOf("Office"))
    assertTrue(p.rings("Kitchen"))
    assertFalse(p.rings("Office"))
    assertFalse(p.copy(canCall = false).rings("Kitchen"))
  }

  @Test fun idIsTheKeyFingerprint() {
    val k = key()
    assertEquals(HomeLinkIdentity.fingerprint(k), link(k = k).id)
  }

  @Test fun upsertAddsThenReplacesWithoutTouchingTheOriginal() {
    val empty = HomeLinkBook()
    val a = link()
    val one = empty.upsert(a)
    assertTrue(empty.links.isEmpty())
    assertEquals(listOf(a), one.links)
    val renamed = a.copy(name = "Lakeview Cabin")
    assertEquals(listOf(renamed), one.upsert(renamed).links)
    assertEquals(listOf(a), one.links)
  }

  @Test fun removeDropsOnlyThatHome() {
    val a = link("Lakeview")
    val b = link("Priya & June’s")
    val book = HomeLinkBook().upsert(a).upsert(b)
    assertEquals(listOf(b), book.remove(a.id).links)
    assertSame(book, book.remove("nope"))
  }

  @Test fun findByKey() {
    val a = link()
    val book = HomeLinkBook().upsert(a)
    assertEquals(a, book.byKey(a.publicKey))
    assertNull(book.byKey(key()))
  }

  @Test fun capsTheNumberOfHomes() {
    var book = HomeLinkBook()
    repeat(HomeLinkBook.MAX_LINKS) { book = book.upsert(link("Home $it")) }
    assertSame(book, book.upsert(link("One too many")))
    assertFalse(book.canAdd)
    // Updating one already there still works at the cap.
    val first = book.links.first()
    assertEquals("Renamed", book.upsert(first.copy(name = "Renamed")).links.first().name)
  }

  @Test fun codecRoundTrips() {
    val book = HomeLinkBook().upsert(link("Lakeview")).upsert(link("Priya & June’s"))
    assertEquals(book, HomeLinkBookCodec.parse(HomeLinkBookCodec.encode(book)))
    assertEquals(HomeLinkBook(), HomeLinkBookCodec.parse(HomeLinkBookCodec.encode(HomeLinkBook())))
  }

  @Test fun codecRejectsCorruptData() {
    assertNull(HomeLinkBookCodec.parse("garbage"))
    assertNull(HomeLinkBookCodec.parse("{}"))
    val good = JSONObject(HomeLinkBookCodec.encode(HomeLinkBook().upsert(link())))
    val entry = good.getJSONArray("links").getJSONObject(0)
    for (bad in listOf(
        JSONObject(entry.toString()).put("k", "junk"),
        JSONObject(entry.toString()).put("id", "0000000000000000"),
        JSONObject(entry.toString()).put("n", ""),
        JSONObject(entry.toString()).put("at", -1),
        JSONObject(entry.toString()).apply { remove("rooms") })) {
      assertNull(bad.toString(), HomeLinkBookCodec.parse(JSONObject().put("v", 1).put("links", JSONArray().put(bad)).toString()))
    }
  }

  @Test fun codecRejectsDuplicatesAndTooMany() {
    val a = link()
    val arr = JSONObject(HomeLinkBookCodec.encode(HomeLinkBook().upsert(a))).getJSONArray("links")
    val dup = JSONObject().put("v", 1).put("links", JSONArray().put(arr.getJSONObject(0)).put(arr.getJSONObject(0)))
    assertNull(HomeLinkBookCodec.parse(dup.toString()))
  }
}
