/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Portal HTTP has six threads, so no single client may hold one indefinitely: the request head and
 * a text body have total time budgets, and an unread body is drained only up to a cap.
 */
class FleetHttpServerLimitsTest {

  /** Yields [bytes] one at a time, advancing a fake clock by [stepMs] per byte, then endless 'x'. */
  private class Trickle(private val bytes: ByteArray, private val stepMs: Long, private val clock: LongArray) : InputStream() {
    var pulled = 0L
    override fun read(): Int {
      clock[0] += stepMs
      val b = if (pulled < bytes.size) bytes[pulled.toInt()].toInt() and 255 else 'x'.code
      pulled++
      return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
  }

  @Test fun aNormalHeadParses() {
    val clock = longArrayOf(0)
    val head = FleetHttpServer.readHead(Trickle("GET /info HTTP/1.1\r\nHost: p\r\n\r\n".toByteArray(), 1, clock)) { clock[0] }
    assertNotNull(head)
    assertEquals("/info", head!!.path)
  }

  @Test fun aHeadDribbledUnderTheSocketTimeoutIsDroppedAtItsBudget() {
    val clock = longArrayOf(0)
    // One byte every 9 s never trips the 10 s per-read timeout; the total budget still ends it.
    val input = Trickle("GET /info HTTP/1.1\r\nX-Slow: ".toByteArray(), 9_000, clock)
    assertNull(FleetHttpServer.readHead(input) { clock[0] })
    assertTrue("gave up at ${clock[0]} ms", clock[0] <= FleetHttpServer.HEAD_BUDGET_MS + 9_000)
    assertTrue(FleetHttpServer.HEAD_READ_TIMEOUT_MS < 30_000)
  }

  @Test fun aTextBodyDribbledUnderTheSocketTimeoutIsRefusedAtItsBudget() {
    val clock = longArrayOf(0)
    val req = FleetHttpServer.Request("POST", "/remote/pair", "", emptyMap(),
        Trickle(ByteArray(0), 1_000, clock), 500_000) { clock[0] }
    try {
      req.bodyText()
      fail("a body trickled for minutes must not be accepted")
    } catch (e: FleetHttpServer.RequestBodyTooSlow) {
      assertEquals(408, FleetHttpServer.requestBodyError(e)!!.status)
    }
    assertTrue(clock[0] <= FleetHttpServer.BODY_TEXT_BUDGET_MS + 1_000)
  }

  @Test fun aPromptTextBodyIsUnaffected() {
    val req = FleetHttpServer.Request("POST", "/x", "", emptyMap(), "{\"a\":1}".byteInputStream(), 7)
    assertEquals("{\"a\":1}", req.bodyText())
  }

  @Test fun anUnreadHugeBodyIsDrainedOnlyUpToTheCap() {
    val clock = longArrayOf(0)
    val input = Trickle(ByteArray(0), 0, clock)
    val req = FleetHttpServer.Request("POST", "/x", "", emptyMap(), input, Int.MAX_VALUE) { clock[0] }
    req.drainRemaining()
    assertTrue("drained ${input.pulled}", input.pulled <= FleetHttpServer.MAX_DRAIN_BYTES)
  }

  @Test fun aTricklingUnreadBodyIsDrainedOnlyUntilTheBudget() {
    val clock = longArrayOf(0)
    val input = Trickle(ByteArray(0), 100, clock)
    val req = FleetHttpServer.Request("POST", "/x", "", emptyMap(), input, 1_000_000) { clock[0] }
    req.drainRemaining()
    assertTrue("drained until ${clock[0]} ms", clock[0] <= FleetHttpServer.DRAIN_BUDGET_MS + 100)
  }
}
