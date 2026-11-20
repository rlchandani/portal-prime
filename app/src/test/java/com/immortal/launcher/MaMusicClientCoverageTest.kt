/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Config/limit validation and hostile server envelopes against a scripted in-memory transport. */
class MaMusicClientCoverageTest {
  private class Fake : MaMusicClient.Transport {
    val received = LinkedBlockingQueue<String>()
    val sent = LinkedBlockingQueue<JSONObject>()
    @Volatile var closed = false
    override fun connect(timeoutMs: Int): Boolean {
      received.add("""{"server_version":"2.10.7","schema_version":65}""")
      return true
    }
    override fun sendText(text: String) { if (closed) throw IOException(); sent.add(JSONObject(text)) }
    override fun readText(): String? {
      if (closed) return null
      val next = received.poll(10, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException()
      return if (next == "CLOSE") null else next
    }
    override fun close() { closed = true; received.offer("CLOSE") }
    fun next(command: String): JSONObject {
      val value = sent.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("No request: $command")
      assertEquals(command, value.getString("command"))
      return value
    }
  }

  private val limits = MaMusicClient.Limits(pollMs = 10, requestTimeoutMs = 500,
      refreshCoalesceMs = 30, progressRefreshMs = 100, reconnectInitialMs = 20, reconnectMaxMs = 80)
  private fun config() = MaMusicClient.Config("192.0.2.20", 8095, "fixture-token", "ma:family")

  @Test fun configSourceIdIsCanonicalAndToStringRedactsHostAndToken() {
    assertEquals("", MaMusicClient.Config("h", 1, "t", "  ").sourceId)
    assertEquals("ma:kitchen", MaMusicClient.Config("h", 1, "t", "kitchen").sourceId)
    assertEquals("ma:kitchen", MaMusicClient.Config("h", 1, "t", "ma:kitchen").sourceId)
    val text = MaMusicClient.Config("192.0.2.20", 8095, "fixture-secret", "ma:family").toString()
    assertFalse(text.contains("fixture-secret"))
    assertFalse(text.contains("192.0.2.20"))
    assertTrue(text.contains("8095"))
  }

  @Test fun sameServerIgnoresOnlyTheSelectedPlayer() {
    val base = MaMusicClient.Config("h", 1, "t", "ma:a")
    assertTrue(base.sameServer(base.copy(selectedPlayer = "ma:b")))
    assertFalse(base.sameServer(base.copy(host = "h2")))
    assertFalse(base.sameServer(base.copy(port = 2)))
    assertFalse(base.sameServer(base.copy(token = "t2")))
  }

  @Test fun limitsRejectEveryOutOfRangeBound() {
    val ok = MaMusicClient.Limits()
    val bad = listOf<() -> MaMusicClient.Limits>(
        { ok.copy(pollMs = 0) }, { ok.copy(pollMs = 1001) },
        { ok.copy(requestTimeoutMs = 0) }, { ok.copy(requestTimeoutMs = 60_001) },
        { ok.copy(refreshCoalesceMs = 0) }, { ok.copy(refreshCoalesceMs = 60_001) },
        { ok.copy(progressRefreshMs = 0) }, { ok.copy(progressRefreshMs = 60_001) },
        { ok.copy(selectionMaxAgeMs = 0) }, { ok.copy(selectionMaxAgeMs = 60_001) },
        { ok.copy(reconnectInitialMs = 0) }, { ok.copy(reconnectInitialMs = 60_001, reconnectMaxMs = 60_000) },
        { ok.copy(reconnectInitialMs = 1000, reconnectMaxMs = 999) }, { ok.copy(reconnectMaxMs = 60_001) },
        { ok.copy(livenessIntervalMs = 0) }, { ok.copy(livenessIntervalMs = 60_001) },
    )
    for ((i, make) in bad.withIndex()) assertTrue("case $i", runCatching(make).exceptionOrNull() is IllegalArgumentException)
    MaMusicClient.Limits(pollMs = 1, requestTimeoutMs = 60_000, refreshCoalesceMs = 60_000, progressRefreshMs = 1,
        selectionMaxAgeMs = 60_000, reconnectInitialMs = 60_000, reconnectMaxMs = 60_000, livenessIntervalMs = 1)
  }

  private fun connectionAfter(hostile: String): Pair<MusicConnection?, Boolean> {
    val fake = Fake()
    val selections = LinkedBlockingQueue<MaMusicProtocol.Selection>()
    val client = MaMusicClient(onSelection = { _, s -> selections.add(s) }, transportFactory = { _, _ -> fake }, limits = limits)
    try {
      client.config(config())
      fake.next("auth")
      fake.received.add(hostile)
      val state = selections.poll(2, TimeUnit.SECONDS)?.snapshot?.connection
      return state to fake.closed
    } finally { client.close() }
  }

  @Test fun malformedOrHostileEnvelopesFailClosedAsIncompatible() {
    val deep = "{\"a\":" + "[".repeat(33) + "]".repeat(33) + "}"
    for (hostile in listOf(
        "not json",
        "[1,2,3]",
        "{\"message_id\":\"x\"} trailing",
        "{\"message_id\":\"x\"}{\"message_id\":\"y\"}",
        "{\"unterminated\":\"value}",
        "{\"a\":1}}",
        deep,
        "\"just a string\"",
    )) {
      val (state, closed) = connectionAfter(hostile)
      assertEquals(hostile.take(40), MusicConnection.INCOMPATIBLE, state)
      assertTrue(hostile.take(40), closed)
    }
  }

  @Test fun bracesInsideStringsDoNotCountTowardsNestingDepth() {
    val fake = Fake()
    val selections = LinkedBlockingQueue<MaMusicProtocol.Selection>()
    val client = MaMusicClient(onSelection = { _, s -> selections.add(s) }, transportFactory = { _, _ -> fake }, limits = limits)
    try {
      client.config(config())
      val auth = fake.next("auth")
      // Unrelated, well-formed event with 40 brackets and an escaped quote inside strings: ignored, not fatal.
      fake.received.add("{\"note\":\"" + "{[".repeat(20) + "\\\"\",\"nested\":" + "[".repeat(31) + "]".repeat(31) + "}")
      fake.received.add(JSONObject().put("message_id", auth.getString("message_id"))
          .put("result", JSONObject().put("authenticated", true)).put("partial", false).toString())
      fake.next("players/all")
      assertFalse(fake.closed)
    } finally { client.close() }
  }
}
