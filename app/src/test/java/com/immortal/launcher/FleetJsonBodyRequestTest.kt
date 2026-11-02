/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exercises the real Request body reader and its shared input accounting, without sockets. */
class FleetJsonBodyRequestTest {
  @Test
  fun validJsonPrefixIsRejectedWhenDeclaredBodyIsIncomplete() {
    val prefix = """{"ok":true}""".toByteArray(Charsets.UTF_8)
    val input = BodyInput(prefix, chunk = 2)
    val request = request(input, prefix.size + 7)

    assertThrows(EOFException::class.java) { request.bodyText() }
    assertEquals(prefix.size, input.position)
  }

  @Test
  fun positiveDeclaredLengthWithNoInputThrowsEof() {
    val request = request(BodyInput(byteArrayOf()), 1)

    assertThrows(EOFException::class.java) { request.bodyText(1) }
  }

  @Test
  fun oversizedBodyIsRejectedBeforeReadingEvenWhenCapEndsAtValidJson() {
    val prefix = """{"ok":true}""".toByteArray(Charsets.UTF_8)
    val body = prefix + " trailing bytes".toByteArray(Charsets.UTF_8)
    val trailer = "NEXT".toByteArray(Charsets.UTF_8)
    val input = BodyInput(body + trailer, chunk = 3)
    val request = request(input, body.size)

    assertThrows(IOException::class.java) { request.bodyText(prefix.size) }
    assertEquals(0, input.readCalls)
    assertEquals(0, input.position)
    val copied = ByteArrayOutputStream()
    assertEquals(body.size.toLong(), request.streamBodyTo(copied))
    assertArrayEquals(body, copied.toByteArray())
    assertEquals(body.size, input.position)
    assertArrayEquals(trailer, input.readBytes())
  }

  @Test
  fun defaultCapAlsoRejectsOversizedDeclaredLengthBeforeReading() {
    val input = BodyInput("unread".toByteArray(Charsets.UTF_8))
    val request = request(input, 1_048_577)

    assertThrows(IOException::class.java) { request.bodyText() }
    assertEquals(0, input.readCalls)
    assertEquals(0, input.position)
  }

  @Test
  fun explicitZeroLengthWithZeroCapSucceedsWithoutTouchingInput() {
    val trailer = "not a body".toByteArray(Charsets.UTF_8)
    val input = BodyInput(trailer)
    val request = request(input, 0)

    assertEquals("", request.bodyText(0))
    assertEquals("", request.bodyText())
    assertEquals(0L, request.streamBodyTo(ByteArrayOutputStream()))
    assertEquals(0, input.readCalls)
    assertEquals(0, input.position)
    assertArrayEquals(trailer, input.readBytes())
  }

  @Test
  fun zeroCapRejectsPositiveLengthWithoutConsumingBody() {
    val body = "{}".toByteArray(Charsets.UTF_8)
    val trailer = "NEXT".toByteArray(Charsets.UTF_8)
    val input = BodyInput(body + trailer)
    val request = request(input, body.size)

    assertThrows(IOException::class.java) { request.bodyText(0) }
    assertEquals(0, input.readCalls)
    assertEquals(0, input.position)
    assertEquals("{}", request.bodyText(body.size))
    assertEquals(body.size, input.position)
    assertArrayEquals(trailer, input.readBytes())
  }

  @Test
  fun exactByteLimitPreservesUnicodeAcrossSingleByteReads() {
    val json = """{"name":"café 家 🎵"}"""
    val body = json.toByteArray(Charsets.UTF_8)
    val trailer = "NEXT".toByteArray(Charsets.UTF_8)
    val input = BodyInput(body + trailer, chunk = 1)
    val request = request(input, body.size)

    assertEquals(json, request.bodyText(body.size))
    assertEquals(body.size, input.position)
    assertArrayEquals(trailer, input.readBytes())
  }

  @Test
  fun repeatedCompletedBodyTextReturnsEmptyWithoutReadingTrailer() {
    val body = """{"done":true}""".toByteArray(Charsets.UTF_8)
    val trailer = "NEXT".toByteArray(Charsets.UTF_8)
    val input = BodyInput(body + trailer, chunk = 2)
    val request = request(input, body.size)

    assertEquals("""{"done":true}""", request.bodyText(body.size))
    val completedReadCalls = input.readCalls
    assertEquals("", request.bodyText(body.size))
    assertEquals("", request.bodyText())
    assertEquals(0L, request.streamBodyTo(ByteArrayOutputStream()))
    assertEquals(completedReadCalls, input.readCalls)
    assertEquals(body.size, input.position)
    assertArrayEquals(trailer, input.readBytes())
  }

  @Test
  fun eofAfterPrefixKeepsConsumedPositionForRemainingBodyRecovery() {
    val body = "prefix-remainder".toByteArray(Charsets.UTF_8)
    val trailer = "NEXT".toByteArray(Charsets.UTF_8)
    // One EOF after six delivered bytes exposes accounting at the exception boundary.
    val input = BodyInput(body + trailer, chunk = 2, faultAt = 6)
    val request = request(input, body.size)

    assertThrows(EOFException::class.java) { request.bodyText(body.size) }
    assertEquals(6, input.position)
    val remaining = ByteArrayOutputStream()
    assertEquals(10L, request.streamBodyTo(remaining))
    assertEquals("-remainder", remaining.toString("UTF-8"))
    assertEquals(body.size, input.position)
    val completedReadCalls = input.readCalls
    assertEquals("", request.bodyText())
    assertEquals(completedReadCalls, input.readCalls)
    assertArrayEquals(trailer, input.readBytes())
  }

  @Test
  fun readIOExceptionAfterPrefixKeepsConsumedPositionAndLeavesTrailerUntouched() {
    val body = "prefix-remainder".toByteArray(Charsets.UTF_8)
    val trailer = "NEXT".toByteArray(Charsets.UTF_8)
    val failure = IOException("read failed once after prefix")
    val input = BodyInput(body + trailer, chunk = 2, faultAt = 6, failure = failure)
    val request = request(input, body.size)

    assertSame(failure, assertThrows(IOException::class.java) { request.bodyText(body.size) })
    assertEquals(6, input.position)
    val remaining = ByteArrayOutputStream()
    assertEquals(10L, request.streamBodyTo(remaining))
    assertEquals("-remainder", remaining.toString("UTF-8"))
    assertEquals(body.size, input.position)
    assertEquals(0L, request.streamBodyTo(remaining))
    assertArrayEquals(trailer, input.readBytes())
  }

  private fun request(input: InputStream, contentLength: Int) =
      FleetHttpServer.Request("POST", "/config", "", emptyMap(), input, contentLength)

  /** Bounded actual bytes, optionally interrupted once by EOF or a read exception. */
  private class BodyInput(
      private val bytes: ByteArray,
      private val chunk: Int = Int.MAX_VALUE,
      private val faultAt: Int = -1,
      private val failure: IOException? = null,
  ) : InputStream() {
    var position = 0
      private set
    var readCalls = 0
      private set
    private var faultDelivered = false

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      readCalls++
      if (length == 0) return 0
      if (!faultDelivered && position == faultAt) {
        faultDelivered = true
        if (failure != null) throw failure
        return -1
      }
      if (position == bytes.size) return -1
      val untilFault =
          if (!faultDelivered && faultAt > position) faultAt - position else Int.MAX_VALUE
      val count = minOf(length, chunk, bytes.size - position, untilFault)
      bytes.copyInto(buffer, offset, position, position + count)
      position += count
      return count
    }
  }
}
