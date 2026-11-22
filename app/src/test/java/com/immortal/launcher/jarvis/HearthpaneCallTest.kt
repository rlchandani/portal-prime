/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.CallPeer
import com.immortal.launcher.CallPhoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.call`: in-home Portals only, never a phone, never a room that can't take it now. */
class HearthpaneCallTest {
  private val now = 10_000_000L
  private var ready = true
  private var busy = false
  private var dialWorks = true
  private var peers = listOf(
      CallPeer("lr", "Living Area", now),
      CallPeer("kt", "Kitchen", now, quiet = true),
      CallPeer("of", "Office", now, inCall = true),
      CallPeer("gr", "Garage", 0),
      CallPeer("kr", "Kids Room", now, acceptsCalls = false),
      CallPeer(CallPhoneId.of("token"), "Rohit's Pixel", now))
  private val dialled = mutableListOf<Pair<String, Boolean>>()
  private val tool = HearthpaneCall({ ready }, { "Family Area" }, { peers }, { busy },
      dial = { id, video -> dialled += id to video; dialWorks }, now = { now })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun callsANamedRoomByVideo() {
    val r = call("room" to "the living area")
    assertTrue(r.toString(), r.getBoolean("ok"))
    assertEquals(listOf("lr" to true), dialled)
    assertEquals("Calling the Living Area. Hang up on the screen to stop.", r.getString("message"))
  }

  @Test fun voiceOnlyWhenAsked() {
    call("room" to "Living Area", "video" to false)
    assertEquals(listOf("lr" to false), dialled)
  }

  @Test fun roomsThatCantTakeACallNowAreNotDialled() {
    assertTrue(call("room" to "Kitchen").getString("message").contains("quiet hours"))
    assertTrue(call("room" to "Office").getString("message").contains("another call"))
    assertTrue(call("room" to "Garage").getString("message").contains("isn't answering"))
    assertTrue(call("room" to "Kids Room").getString("message").contains("isn't taking calls"))
    assertTrue(dialled.isEmpty())
  }

  @Test fun aPairedPhoneIsNeverReachable() {
    val r = call("room" to "Rohit's Pixel")
    assertTrue(r.getBoolean("needs_room"))
    val offered = (0 until r.getJSONArray("rooms").length()).map { r.getJSONArray("rooms").getString(it) }
    assertEquals(listOf("Living Area", "Kitchen", "Office", "Garage", "Kids Room"), offered)
    assertTrue(dialled.isEmpty())
  }

  @Test fun unknownOrSelfRoomsAsk() {
    assertTrue(call("room" to "Attic").getBoolean("needs_room"))
    assertTrue(call("room" to "Family Area").getString("message").contains("That's this room"))
    peers = emptyList()
    assertTrue(call("room" to "Attic").getString("message").contains("No other Portals"))
    assertTrue(dialled.isEmpty())
  }

  @Test fun notReadyBusyOrFailedDialSaySo() {
    ready = false
    assertTrue(call("room" to "Living Area").getString("message").contains("aren't set up"))
    ready = true; busy = true
    assertTrue(call("room" to "Living Area").getString("message").contains("already on a call"))
    busy = false; dialWorks = false
    assertTrue(call("room" to "Living Area").getString("message").contains("Couldn't start"))
  }

  @Test fun badArgumentsAreErrors() {
    assertTrue(call().getString("error").contains("room is required"))
    assertTrue(tool.invoke("x").has("error"))
  }
}
