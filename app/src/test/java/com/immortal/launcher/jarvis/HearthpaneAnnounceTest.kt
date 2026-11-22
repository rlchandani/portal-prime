/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.DinnerKind
import com.immortal.launcher.DinnerMessage
import com.immortal.launcher.DinnerPeer
import com.immortal.launcher.DinnerSend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.announce`: message shaping, room resolution and honest replies. */
class HearthpaneAnnounceTest {
  private var ready = true
  private var self: String? = "fa"
  private var peers = listOf(DinnerPeer("fa", "Family Area", 1, null, null), DinnerPeer("lr", "Living Area", 1, false, true),
      DinnerPeer("kt", "Kitchen", 1, true, true), DinnerPeer("of", "Office", 1, null, false))
  private val sent = mutableListOf<Pair<DinnerMessage, List<String>?>>()
  private var hellos = 0
  private var sendWorks = true
  private val tool = HearthpaneAnnounce({ ready }, { self }, { "Family Area" }, { peers },
      send = { m, r -> sent += m to r; if (sendWorks) DinnerSend("s1", m, "Family Area", false, 0, emptyList()) else null },
      hello = { hellos++ })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun dinnersReadyGoesToEveryOtherRoomAsThePreset() {
    val r = call("message" to "Dinner's ready!")
    assertTrue(r.toString(), r.getBoolean("ok"))
    val (m, rooms) = sent.single()
    assertEquals(DinnerKind.PRESET, m.kind)
    assertEquals("dinner", m.presetId)
    assertNull(rooms)
    assertEquals(3, r.getJSONArray("rooms").length())
    assertTrue(r.getString("message"), r.getString("message").startsWith("Announced to 3 rooms"))
  }

  @Test fun quietAndOffRoomsAreNamedInTheReply() {
    val msg = call("message" to "dinner is ready", "room" to "everyone").getString("message")
    assertTrue(msg, msg.contains("Quiet hours in Kitchen"))
    assertTrue(msg, msg.contains("Office turned announcements off"))
    assertEquals("dinner", sent.single().first.presetId)
  }

  @Test fun otherWordsAreCustomTextToOneNamedRoom() {
    val r = call("message" to "The movie is starting", "room" to "the living area")
    assertTrue(r.getBoolean("ok"))
    val (m, rooms) = sent.single()
    assertEquals(DinnerKind.CUSTOM, m.kind)
    assertEquals("The movie is starting", m.text)
    assertEquals(listOf("lr"), rooms)
    assertTrue(r.getString("message").startsWith("Announced to the Living Area"))
  }

  @Test fun anUnknownRoomAsksWhichAndSendsNothing() {
    val r = call("message" to "hi", "room" to "garage")
    assertTrue(r.getBoolean("needs_room"))
    assertTrue(r.getString("message"), r.getString("message").contains("Living Area, Kitchen, Office, every room"))
    assertTrue(sent.isEmpty())
    assertEquals(1, hellos)
  }

  @Test fun thisRoomIsNotATarget() {
    assertFalse(call("message" to "hi", "room" to "Family Area").getBoolean("ok"))
    assertTrue(sent.isEmpty())
  }

  @Test fun tooLongOrMissingTextIsRefused() {
    assertTrue(call("message" to "x".repeat(DinnerMessage.MAX_CUSTOM + 1)).getString("error").contains("too long"))
    assertTrue(call("room" to "Kitchen").getString("error").contains("message is required"))
    assertTrue(tool.invoke("not json").has("error"))
    assertTrue(sent.isEmpty())
  }

  @Test fun notSetUpOrNobodyHeardSaysSo() {
    ready = false
    assertTrue(call("message" to "hi").getString("message").contains("aren't set up"))
    ready = true; self = null
    assertTrue(call("message" to "hi").getString("message").contains("aren't set up"))
    self = "fa"; peers = peers.take(1)
    assertTrue(call("message" to "hi").getString("message").contains("No other Portals"))
    assertEquals(1, hellos)
    peers = listOf(DinnerPeer("lr", "Living Area", 1, null, null)); sendWorks = false
    assertTrue(call("message" to "hi").getString("message").contains("aren't set up"))
  }

  @Test fun messagePresetMatchingIgnoresPunctuationAndCurlyQuotes() {
    assertEquals("dinner", HearthpaneAnnounce.messageFor("Dinner’s ready")!!.presetId)
    assertEquals("bed", HearthpaneAnnounce.messageFor("Bedtime.")!!.presetId)
    assertEquals("bed", HearthpaneAnnounce.messageFor("time for bed")!!.presetId)
    assertEquals("Leaving in 5", HearthpaneAnnounce.messageFor("Leaving in 5!")!!.text)
  }
}
