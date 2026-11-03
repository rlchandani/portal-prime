/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class HttpErrorBodyTest {
  private class Body(
      private val bytes: ByteArray,
      private val fragment: Int = Int.MAX_VALUE,
      private val failAt: Int? = null,
      private val endless: Boolean = false,
      private val failClose: Boolean = false,
  ) : InputStream() {
    var consumed = 0
      private set
    var readFailures = 0
      private set
    var closes = 0
      private set

    override fun read(): Int {
      val single = ByteArray(1)
      return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
      check(closes == 0) { "Read after owned stream retirement" }
      if (length == 0) return 0
      if (failAt != null && consumed >= failAt) {
        readFailures++
        throw IOException("Generated body tail failure")
      }
      if (!endless && consumed == bytes.size) return -1
      val count =
          minOf(length, fragment, if (endless) Int.MAX_VALUE else bytes.size - consumed,
              failAt?.minus(consumed) ?: Int.MAX_VALUE)
      repeat(count) { index ->
        target[offset + index] = if (endless) 'x'.code.toByte() else bytes[consumed + index]
      }
      consumed += count
      return count
    }

    override fun close() {
      closes++
      if (failClose) throw IOException("Generated close failure")
    }
  }

  private class RejectedConnection(private val body: InputStream?) :
      HttpURLConnection(URL("http://example.invalid/generated")) {
    var statusReads = 0
      private set
    var errorStreamReads = 0
      private set

    override fun getErrorStream(): InputStream? {
      errorStreamReads++
      return body
    }

    override fun getResponseCode(): Int {
      statusReads++
      return 503
    }

    override fun getInputStream(): InputStream = error("Success body must not be accessed")
    override fun getOutputStream(): java.io.OutputStream =
        error("No request body or network in this fixture")
    override fun connect() { error("No network in this fixture") }
    override fun disconnect() { error("Logger must not own connection disconnection") }
    override fun usingProxy(): Boolean = error("No network in this fixture")
  }

  private fun log(body: InputStream?, method: String = "GET"): Pair<RejectedConnection, String> {
    val connection = RejectedConnection(body)
    val messages = mutableListOf<Pair<String, String>>()
    Mockito.mockStatic(Log::class.java).use { mocked ->
      mocked.`when`<Int> { Log.w(Mockito.anyString(), Mockito.anyString()) }
          .thenAnswer { invocation ->
            messages += invocation.getArgument<String>(0) to invocation.getArgument<String>(1)
            0
          }
      Http::class.java
          .getDeclaredMethod(
              "logFailure", String::class.java, String::class.java, HttpURLConnection::class.java)
          .apply { isAccessible = true }
          .invoke(Http, method, "http://example.invalid/generated", connection)
    }
    assertEquals(1, messages.size)
    assertEquals("ImmortalHttp", messages.single().first)
    assertEquals(1, connection.statusReads)
    assertEquals(1, connection.errorStreamReads)
    return connection to messages.single().second
  }

  private fun expected(detail: String?, method: String = "GET"): String =
      "$method http://example.invalid/generated -> HTTP 503" +
          if (detail == null) "" else ": $detail"

  private fun assertBounded(body: Body) {
    // A standard UTF-8 reader may prefetch its 8 KiB byte buffer to produce 300
    // UTF-16 units. The diagnostic must not consume the entire generated body.
    assertTrue("Consumed ${body.consumed} bytes for a 300-unit diagnostic", body.consumed <= 8192)
    assertEquals(1, body.closes)
  }

  @Test fun largeBodyKeepsPrefixWithoutConsumingWholeError() {
    val text = "Generated rejection: " + "abcdef".repeat(40_000)
    val body = Body(text.toByteArray(Charsets.UTF_8))
    assertEquals(expected(text.take(300)), log(body).second)
    assertBounded(body)
  }

  @Test fun failureBeyondDiagnosticReadAheadDoesNotEraseAvailablePrefix() {
    val text = "Retained rejection: " + "abc".repeat(20_000)
    val body = Body(text.toByteArray(Charsets.UTF_8), failAt = 16_384)
    assertEquals(expected(text.take(300)), log(body).second)
    assertEquals(0, body.readFailures)
    assertBounded(body)
  }

  @Test fun nonEofBodyStopsBeforeGeneratedFailureCeiling() {
    // Baseline cannot hang: this generated non-EOF stream throws after16 KiB.
    val body = Body(byteArrayOf(), failAt = 16_384, endless = true)
    assertEquals(expected("x".repeat(300)), log(body).second)
    assertEquals(0, body.readFailures)
    assertBounded(body)
  }

  @Test fun shortEmptyAndExactBoundaryMessagesKeepOriginalFormatting() {
    for (text in listOf("", "Short rejection", "a".repeat(299), "b".repeat(300),
                        "c".repeat(301))) {
      val body = Body(text.toByteArray(Charsets.UTF_8))
      assertEquals(expected(text.take(300)), log(body).second)
      assertEquals(1, body.closes)
    }
  }

  @Test fun fragmentedMultibyteUtf8PreservesDecodedPrefix() {
    val text = "Café δ 😀 ".repeat(1_000)
    val body = Body(text.toByteArray(Charsets.UTF_8), fragment = 1)
    assertEquals(expected(text.take(300)), log(body).second)
    assertBounded(body)
  }

  @Test fun malformedUtf8KeepsStandardReplacementSemantics() {
    val malformed = byteArrayOf(0xc3.toByte(), 0x28, 0xf0.toByte(), 0x28, 0x8c.toByte(), 0xbc.toByte())
    val cases = listOf(
        "Generated: ".toByteArray() + malformed + "tail".repeat(2_000).toByteArray(),
        "Short incomplete: ".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()),
    )
    for (bytes in cases) {
      val body = Body(bytes, fragment = 3)
      assertEquals(expected(bytes.toString(Charsets.UTF_8).take(300)), log(body).second)
      assertBounded(body)
    }
  }

  @Test fun utf16LimitRetainsExistingSplitSurrogateSemantics() {
    val text = "a".repeat(299) + "😀" + "later".repeat(2_000)
    val body = Body(text.toByteArray(Charsets.UTF_8), fragment = 7)
    val detail = text.take(300)
    assertEquals('\uD83D', detail.last())
    assertEquals(expected(detail), log(body).second)
    assertBounded(body)
  }

  @Test fun nullErrorStreamLogsStatusWithoutDetail() {
    assertEquals(expected(null, "POST"), log(null, "POST").second)
  }

  @Test fun immediateReadFailureClosesOnceAndKeepsStatusDiagnostic() {
    val body = Body("Generated".toByteArray(), failAt = 0)
    assertEquals(expected(null), log(body).second)
    assertEquals(1, body.readFailures)
    assertEquals(1, body.closes)
  }

  @Test fun closeFailureAttemptsRetirementOnceAndKeepsStatusDiagnostic() {
    val body = Body("Generated rejection".toByteArray(), failClose = true)
    assertEquals(expected(null), log(body).second)
    assertEquals(1, body.closes)
  }
}
