/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.DinnerSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Phone → Portal /remote/dinner bodies, peers directory and the settings proposal. */
class DinnerRequestsTest {
  private val rooms = JSONArray().put("11111111-1111-1111-1111-111111111111")

  @Test fun parsesPresetCustomPingAndRetry() {
    val preset = DinnerRequests.parse(JSONObject().put("kind", "preset").put("preset", "dinner").put("rooms", rooms).put("sender", "Rohit’s Pixel"))
    assertEquals(DinnerRequests.Send(DinnerMessage.preset("dinner")!!, listOf(rooms.getString(0)), "Rohit’s Pixel"), preset)
    val custom = DinnerRequests.parse(JSONObject().put("kind", "custom").put("text", "Pizza’s here").put("rooms", rooms)) as DinnerRequests.Send
    assertEquals("Pizza’s here", custom.message.text)
    assertEquals(DinnerRequests.DEFAULT_SENDER, custom.senderName)
    assertEquals(DinnerKind.PING, (DinnerRequests.parse(JSONObject().put("kind", "ping").put("rooms", rooms)) as DinnerRequests.Send).message.kind)
    assertEquals(DinnerRequests.Retry("a1b2c3d4", "la-id"),
        DinnerRequests.parse(JSONObject().put("retry", JSONObject().put("id", "a1b2c3d4").put("room", "la-id"))))
  }

  @Test fun rejectsBadPhoneInput() {
    fun err(body: JSONObject) = (DinnerRequests.parse(body) as DinnerRequests.Invalid).code
    assertEquals("unknown_field", err(JSONObject().put("kind", "ping").put("rooms", rooms).put("x", 1)))
    assertEquals("invalid_kind", err(JSONObject().put("kind", "shout").put("rooms", rooms)))
    assertEquals("invalid_preset", err(JSONObject().put("kind", "preset").put("preset", "x").put("rooms", rooms)))
    assertEquals("invalid_text", err(JSONObject().put("kind", "custom").put("text", "y".repeat(41)).put("rooms", rooms)))
    assertEquals("invalid_rooms", err(JSONObject().put("kind", "ping")))
    assertEquals("invalid_rooms", err(JSONObject().put("kind", "ping").put("rooms", JSONArray())))
    assertEquals("invalid_rooms", err(JSONObject().put("kind", "ping").put("rooms", JSONArray().put("bad id"))))
    assertEquals("invalid_rooms", err(JSONObject().put("kind", "ping").put("rooms", JSONArray((1..9).map { "r$it" }))))
    assertEquals("invalid_sender", err(JSONObject().put("kind", "ping").put("rooms", rooms).put("sender", 5)))
    assertEquals("invalid_retry", err(JSONObject().put("retry", JSONObject().put("id", "zz").put("room", "la"))))
  }

  @Test fun duplicateRoomsCollapse() {
    val twice = JSONArray().put("abc").put("abc")
    assertEquals(listOf("abc"), (DinnerRequests.parse(JSONObject().put("kind", "ping").put("rooms", twice)) as DinnerRequests.Send).rooms)
  }

  @Test fun peersMergeNewestFirstAndSurviveJunk() {
    val one = DinnerPeer("a", "Den", 1L, quiet = false, receive = true)
    val list = DinnerPeers.merge(DinnerPeers.merge(emptyList(), one), one.copy(name = "Kitchen", seenMs = 2L))
    assertEquals(listOf("Kitchen"), list.map { it.name })
    val many = (1..20).fold(emptyList<DinnerPeer>()) { acc, i -> DinnerPeers.merge(acc, one.copy(id = "p$i", seenMs = i.toLong())) }
    assertEquals(DinnerPeers.CAPACITY, many.size)
    assertEquals(many, DinnerPeers.decode(DinnerPeers.encode(many)))
    assertEquals(emptyList<DinnerPeer>(), DinnerPeers.decode("not json"))
    assertEquals(emptyList<DinnerPeer>(), DinnerPeers.decode("[{\"id\":\"bad id\",\"name\":\"x\"}]"))
  }

  @Test fun settingsProposalCoversEveryPersistedField() {
    val fields = DinnerConfig.Settings::class.java.declaredFields.map { it.name }.filterNot { it.contains('$') }.toSet()
    assertEquals(fields, DinnerSettings.domain.specs.map { it.key }.toSet())
  }
}
