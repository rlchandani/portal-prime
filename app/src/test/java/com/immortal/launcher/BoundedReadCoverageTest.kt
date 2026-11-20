/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HttpURLConnection-facing caps (declared length, error body) and argument validation. */
class BoundedReadCoverageTest {
  /** A connection that never touches the network: it only serves canned streams. */
  private class CannedConnection(
      private val length: Long,
      private val body: InputStream? = null,
      private val error: InputStream? = null,
  ) : HttpURLConnection(URL("http://example.invalid/")) {
    var bodyOpened = false
    override fun getContentLengthLong(): Long = length
    override fun getInputStream(): InputStream { bodyOpened = true; return body ?: error("no body") }
    override fun getErrorStream(): InputStream? = error
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
  }

  private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

  @Test fun replyReadsABodyWithinTheCap() {
    assertEquals("hello", BoundedRead.reply(CannedConnection(5, stream("hello")), maxBytes = 5))
    assertEquals("no declared length", "hi", BoundedRead.reply(CannedConnection(-1, stream("hi")), maxBytes = 5))
  }

  @Test fun replyRefusesAnOversizeDeclaredLengthWithoutOpeningTheBody() {
    val conn = CannedConnection(6, stream("hello!"))
    assertNull(BoundedRead.reply(conn, maxBytes = 5))
    assertFalse(conn.bodyOpened)
  }

  @Test fun replyRefusesAnUndeclaredBodyThatRunsPastTheCap() {
    assertNull(BoundedRead.reply(CannedConnection(-1, stream("hello!")), maxBytes = 5))
  }

  @Test fun errorReplyIsCappedAndMayBeAbsent() {
    assertEquals("""{"error":"x"}""", BoundedRead.errorReply(CannedConnection(-1, error = stream("""{"error":"x"}""")), 64))
    assertNull("no error stream", BoundedRead.errorReply(CannedConnection(-1), 64))
    assertNull("declared too long", BoundedRead.errorReply(CannedConnection(65, error = stream("x")), 64))
    assertNull("actual too long", BoundedRead.errorReply(CannedConnection(-1, error = stream("x".repeat(65))), 64))
  }

  @Test fun negativeCapIsAProgrammingError() {
    assertThrows(IllegalArgumentException::class.java) { BoundedRead.bytes(stream("x"), -1) }
  }

  @Test fun zeroCapAcceptsOnlyAnEmptyStream() {
    assertArrayEquals(ByteArray(0), BoundedRead.bytes(stream(""), 0))
    assertNull(BoundedRead.bytes(stream("x"), 0))
  }

  @Test fun utf8DecodesMultibyteText() {
    assertEquals("café", BoundedRead.utf8(stream("café"), 5))
    assertNull(BoundedRead.utf8(stream("café"), 4))
  }

  @Test fun declaredFitsBoundary() {
    assertTrue(BoundedRead.declaredFits(10, 10))
    assertTrue(BoundedRead.declaredFits(-1, 0))
    assertFalse(BoundedRead.declaredFits(11, 10))
  }

  @Test fun budgetIsCheckedAfterEachChunkUsingTheInjectedClock() {
    var t = 0L
    val clock = { t.also { t += 10 } }
    val chunky = object : InputStream() {
      var left = 3
      override fun read(): Int = throw UnsupportedOperationException()
      override fun read(b: ByteArray, off: Int, len: Int): Int = if (left-- > 0) { b[off] = 1; 1 } else -1
    }
    assertNull(BoundedRead.bytes(chunky, 100, budgetMs = 15, clock = clock))
  }
}
