/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The end-of-conversation digest is untrusted input: capped, shaped, and refused without a conversation id. */
class MemoryDigestTest {
  private fun parse(o: JSONObject) = MemoryDigest.parse(o.toString())

  @Test fun readsTurnsAndToolCalls() {
    val d = parse(JSONObject().put("conversation", "abc-1").put("started_at", 5).put("screen", "music")
        .put("user", JSONArray(listOf("hi", "  ", 7)))
        .put("tools", JSONArray().put(JSONObject().put("name", "portal.web_search").put("query", "warriors score"))
            .put(JSONObject().put("name", "Bad Name").put("query", "x")).put(JSONObject().put("name", "portal.play_music"))))!!
    assertEquals("abc-1", d.conversationId)
    assertEquals("music", d.screen)
    assertEquals(listOf("hi"), d.userTurns)
    assertEquals(listOf(MemoryDigest.ToolCall("portal.web_search", "warriors score")), d.toolCalls)
  }

  @Test fun capsEverything() {
    val long = "w".repeat(5_000)
    val d = parse(JSONObject().put("conversation", "c").put("user", JSONArray(List(100) { long }))
        .put("tools", JSONArray(List(50) { JSONObject().put("name", "portal.web_search").put("query", long) })))!!
    assertEquals(MemoryDigest.MAX_TOTAL_CHARS, d.userTurns.sumOf { it.length })
    assertEquals(MemoryDigest.MAX_TOOLS, d.toolCalls.size)
    assertEquals(MemoryDigest.MAX_QUERY_CHARS, d.toolCalls.first().query.length)
  }

  @Test fun refusesWithoutAValidConversation() {
    assertNull(MemoryDigest.parse(null))
    assertNull(MemoryDigest.parse("[]"))
    assertNull(parse(JSONObject().put("conversation", "../../etc")))
  }
}
