/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/** Pairing messages on the wire: strict, versioned, bounded (anything off is dropped). */
class HomeLinkWireTest {
  private val random = SecureRandom()
  private val lake = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
  private val maple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
  private val code = HomeLinkCode.parse("MPT7Q4K2PA")!!

  /** Commit, reveal, open, hello. */
  private fun messages(): List<HomeLinkMessage> {
    val h = HomeLinkInviter.start(HomeLinkInvite(code, 0L), random)
    val j1 = HomeLinkJoiner.receive(HomeLinkJoiner.start(code, random), lake, h.outgoing!!, random)
    val i1 = HomeLinkInviter.receive(h.state, maple, j1.outgoing!!, 1L)
    val j2 = HomeLinkJoiner.receive(j1.state, lake, i1.outgoing!!, random)
    return listOf(j1.outgoing!!, i1.outgoing!!, j2.outgoing!!, h.outgoing!!)
  }

  @Test fun everyMessageRoundTrips() {
    for (m in messages()) assertEquals(m, HomeLinkWire.parse(HomeLinkWire.encode(m)))
  }

  @Test fun rejectsNonJsonAndUnknownTypes() {
    assertNull(HomeLinkWire.parse(""))
    assertNull(HomeLinkWire.parse("not json"))
    assertNull(HomeLinkWire.parse("[]"))
    val reveal = JSONObject(HomeLinkWire.encode(messages()[1]))
    assertNull(HomeLinkWire.parse(reveal.put("t", "drop_in").toString()))
  }

  @Test fun rejectsOtherVersions() {
    val m = JSONObject(HomeLinkWire.encode(messages()[0]))
    assertNull(HomeLinkWire.parse(m.put("v", 1).toString()))
    assertNull(HomeLinkWire.parse(JSONObject(HomeLinkWire.encode(messages()[0])).put("v", "2").toString()))
    assertNull(HomeLinkWire.parse(JSONObject(HomeLinkWire.encode(messages()[0])).apply { remove("v") }.toString()))
  }

  @Test fun rejectsMissingOrEmptyFields() {
    for (m in messages()) {
      val json = JSONObject(HomeLinkWire.encode(m))
      for (key in json.keys().asSequence().toList()) {
        assertNull("without $key", HomeLinkWire.parse(JSONObject(json.toString()).apply { remove(key) }.toString()))
        if (json.get(key) is String) assertNull("empty $key", HomeLinkWire.parse(JSONObject(json.toString()).put(key, "").toString()))
      }
    }
  }

  @Test fun rejectsOversizedAndOddNames() {
    val reveal = JSONObject(HomeLinkWire.encode(messages()[1]))
    assertNull(HomeLinkWire.parse(JSONObject(reveal.toString()).put("n", "x".repeat(HomeLinkWire.MAX_NAME + 1)).toString()))
    assertNull(HomeLinkWire.parse(JSONObject(reveal.toString()).put("n", "Maple\nHouse").toString()))
    assertNull(HomeLinkWire.parse(JSONObject(reveal.toString()).put("n", "   ").toString()))
    assertNotNull(HomeLinkWire.parse(JSONObject(reveal.toString()).put("n", "Priya & June’s").toString()))
  }

  @Test fun rejectsOversizedFieldsAndBadMailboxes() {
    val open = JSONObject(HomeLinkWire.encode(messages()[2]))
    assertNull(HomeLinkWire.parse(JSONObject(open.toString()).put("k", "A".repeat(HomeLinkWire.MAX_FIELD + 1)).toString()))
    assertNull(HomeLinkWire.parse(JSONObject(open.toString()).put("b", "ABCDEF").toString()))
    assertNull(HomeLinkWire.parse(JSONObject(open.toString()).put("b", "g".repeat(32)).toString()))
  }

  @Test fun rejectsAHugeEnvelopeBeforeParsing() {
    assertNull(HomeLinkWire.parse("{\"pad\":\"" + "x".repeat(HomeLinkWire.MAX_BYTES) + "\"}"))
  }

  @Test fun sequenceNumbersAreBoundedIntegers() {
    val m = JSONObject(HomeLinkWire.encode(messages()[0]))
    assertEquals(1, HomeLinkWire.parse(m.toString())!!.seq)
    assertNull(HomeLinkWire.parse(JSONObject(m.toString()).put("q", -1).toString()))
    assertNull(HomeLinkWire.parse(JSONObject(m.toString()).put("q", 16).toString()))
    assertNull(HomeLinkWire.parse(JSONObject(m.toString()).put("q", "1").toString()))
    assertNull(HomeLinkWire.parse(JSONObject(m.toString()).put("q", 1.5).toString()))
  }

  @Test fun signableTextCoversTypeAndSequence() {
    val commit = messages()[0] as HomeLinkMessage.Commit
    assertNotEquals(HomeLinkWire.signable(commit), HomeLinkWire.signable(commit.copy(seq = 2)))
    assertTrue(HomeLinkWire.signable(commit).contains("commit"))
  }

  @Test fun signableTextCoversEveryFieldButTheMac() {
    val reveal = messages()[1] as HomeLinkMessage.Reveal
    val text = HomeLinkWire.signable(reveal)
    assertTrue(text.contains(reveal.key))
    assertTrue(text.contains(reveal.sig))
    assertFalse(text.contains(reveal.mac))
    assertNotEquals(text, HomeLinkWire.signable(reveal.copy(name = "Lakeview")))
  }
}
