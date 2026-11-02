/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** Memory-only HTTP work/cleanup checks; no sockets, Android runtime or preferences. */
class CalendarFeedHttpTest {
  @Test fun oversizedAdvertisedBodiesAreRejectedWithoutOpeningOrReadingInput() {
    for (length in listOf(LIMIT.toLong() + 1, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)) {
      val body = CountedInput(32)
      val connection = FakeConnection(body, advertised = length)
      assertNull(CalendarFeedHttp.fetch(connection))
      assertEquals("Rejected metadata must not acquire the body", 0, connection.inputOpens)
      assertEquals(0, body.readCalls)
      assertEquals(0, body.bytesRead)
      assertEquals(0, body.closes)
      assertEquals(1, connection.disconnects)
    }
  }

  @Test fun nonSuccessStatusesNeverOpenOrReadEitherResponseBody() {
    for (status in listOf(199, 300, 304, 400, 401, 500)) {
      val body = CountedInput(32)
      val connection = FakeConnection(body, status = status, advertised = -1)
      assertNull(CalendarFeedHttp.fetch(connection))
      assertEquals(0, connection.inputOpens)
      assertEquals(0, connection.errorOpens)
      assertEquals(0, body.readCalls)
      assertEquals(0, connection.errorBody.readCalls)
      assertEquals(1, connection.disconnects)
    }
  }

  @Test fun unknownLengthAtExactBudgetSucceedsAndOneByteOverFails() {
    assertEquals("The transport budget is eight MiB", LIMIT, CalendarFeedHttp.MAX_BYTES)
    val exact = CountedInput(LIMIT)
    val exactConnection = FakeConnection(exact, advertised = -1)
    val result = CalendarFeedHttp.fetch(exactConnection)
    assertNotNull(result)
    assertEquals(LIMIT, result!!.length)
    assertEquals('x', result.first())
    assertEquals('x', result.last())
    assertEquals(LIMIT, exact.bytesRead)
    assertEquals(1, exact.closes)
    assertEquals(1, exactConnection.disconnects)

    val over = CountedInput(LIMIT + 1)
    val overConnection = FakeConnection(over, advertised = -1)
    assertNull(CalendarFeedHttp.fetch(overConnection))
    assertEquals("The extra byte must be detected", LIMIT + 1, over.bytesRead)
    assertEquals(1, over.closes)
    assertEquals(1, overConnection.disconnects)
  }

  @Test fun advertisedExactBudgetIsAcceptedWithoutTreatingTheCapAsExclusive() {
    val body = CountedInput(LIMIT)
    val connection = FakeConnection(body, advertised = LIMIT.toLong())
    assertEquals(LIMIT, CalendarFeedHttp.fetch(connection)!!.length)
    assertEquals(LIMIT, body.bytesRead)
    assertEquals(1, body.closes)
    assertEquals(1, connection.disconnects)
  }

  @Test fun smallUtf8BodySupportsKnownAndUnknownWireLengths() {
    val text = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Café 日本 🌙\r\nEND:VCALENDAR\r\n"
    val bytes = text.toByteArray(Charsets.UTF_8)
    assertTrue("Wire length differs from character count", bytes.size > text.length)
    for (advertised in listOf(-1L, bytes.size.toLong())) {
      val body = CountedInput(bytes.size, payload = bytes)
      val connection = FakeConnection(body, status = 206, advertised = advertised)
      assertEquals(text, CalendarFeedHttp.fetch(connection))
      assertEquals(bytes.size, body.bytesRead)
      assertEquals(1, body.closes)
      assertEquals(1, connection.disconnects)
    }
  }

  @Test fun emptyAdvertisedBodyRequiresAnActualEmptyBody() {
    val empty = CountedInput(0)
    val emptyConnection = FakeConnection(empty, advertised = 0)
    assertEquals("", CalendarFeedHttp.fetch(emptyConnection))
    assertEquals(1, empty.closes)
    assertEquals(1, emptyConnection.disconnects)

    val nonempty = CountedInput(1)
    val nonemptyConnection = FakeConnection(nonempty, advertised = 0)
    assertNull(CalendarFeedHttp.fetch(nonemptyConnection))
    assertTrue("A declared zero must not silently discard a body", nonempty.bytesRead > 0)
    assertEquals(1, nonempty.closes)
    assertEquals(1, nonemptyConnection.disconnects)
  }

  @Test fun completeCalendarPrefixCannotSatisfyLongerAdvertisedLength() {
    val text = calendar("Premature replacement")
    assertEquals(listOf("Premature replacement"), CalendarFeed.parse(text, NOW).map { it.title })
    val bytes = text.toByteArray(Charsets.UTF_8)
    val body = CountedInput(bytes.size, payload = bytes)
    val connection = FakeConnection(body, advertised = bytes.size.toLong() + 100)
    assertNull("Complete VCALENDAR syntax does not prove a complete HTTP response",
        CalendarFeedHttp.fetch(connection))
    assertEquals(bytes.size, body.bytesRead)
    assertEquals(1, body.closes)
    assertEquals(1, connection.disconnects)
  }

  @Test fun moreActualBytesThanAdvertisedIsAlsoRejected() {
    val bytes = calendar("Unexpected suffix").toByteArray(Charsets.UTF_8)
    val body = CountedInput(bytes.size, payload = bytes)
    val connection = FakeConnection(body, advertised = bytes.size.toLong() - 1)
    assertNull(CalendarFeedHttp.fetch(connection))
    assertTrue(body.bytesRead > bytes.size - 1)
    assertEquals(1, body.closes)
    assertEquals(1, connection.disconnects)
  }

  @Test fun readIoFailureReturnsNullAndStillClosesAndDisconnects() {
    val body = CountedInput(100, failAfter = 7)
    val connection = FakeConnection(body, advertised = -1)
    assertNull(CalendarFeedHttp.fetch(connection))
    assertEquals("Only the prefix before the transport failure was delivered", 7, body.bytesRead)
    assertEquals(1, body.closes)
    assertEquals(1, connection.disconnects)
  }

  @Test fun statusAndInputAcquisitionFailuresReturnNullAndDisconnect() {
    val statusBody = CountedInput(10)
    val statusFailure = FakeConnection(statusBody, statusFailure = IOException("status"))
    assertNull(CalendarFeedHttp.fetch(statusFailure))
    assertEquals(0, statusFailure.inputOpens)
    assertEquals(0, statusBody.closes)
    assertEquals(1, statusFailure.disconnects)

    val inputBody = CountedInput(10)
    val inputFailure = FakeConnection(inputBody, inputFailure = IOException("input"))
    assertNull(CalendarFeedHttp.fetch(inputFailure))
    assertEquals(1, inputFailure.inputOpens)
    assertEquals(0, inputBody.readCalls)
    assertEquals(0, inputBody.closes)
    assertEquals(1, inputFailure.disconnects)
  }

  @Test fun cleanupFailuresCannotReplaceSuccessfulTextOrFailedRead() {
    val bytes = calendar("Saved despite cleanup").toByteArray(Charsets.UTF_8)
    val successBody = CountedInput(bytes.size, payload = bytes, failClose = true)
    val success = FakeConnection(successBody, advertised = bytes.size.toLong(),
        failDisconnect = true)
    assertEquals(String(bytes, Charsets.UTF_8), CalendarFeedHttp.fetch(success))
    assertEquals(1, successBody.closes)
    assertEquals(1, success.disconnects)

    val failedBody = CountedInput(100, failAfter = 7, failClose = true)
    val failure = FakeConnection(failedBody, advertised = -1, failDisconnect = true)
    assertNull(CalendarFeedHttp.fetch(failure))
    assertEquals(7, failedBody.bytesRead)
    assertEquals(1, failedBody.closes)
    assertEquals(1, failure.disconnects)
  }

  @Test fun alreadyInterruptedCallerPropagatesInterruptionAndDisconnects() {
    val body = CountedInput(10)
    val connection = FakeConnection(body, failDisconnect = true)
    val wasInterrupted = Thread.currentThread().isInterrupted
    try {
      Thread.currentThread().interrupt()
      try {
        CalendarFeedHttp.fetch(connection)
        fail("An already interrupted caller must not become an ordinary unavailable response")
      } catch (_: InterruptedException) {
        assertEquals(0, body.readCalls)
        assertEquals(1, connection.disconnects)
      }
    } finally {
      Thread.interrupted()
      if (wasInterrupted) Thread.currentThread().interrupt()
    }
  }

  @Test fun existingRequestSettingsAreEstablishedBeforeResponseAcquisition() {
    val body = CountedInput(3)
    val connection = FakeConnection(body, advertised = 3)
    connection.connectTimeout = 1
    connection.readTimeout = 2
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "previous")
    connection.setRequestProperty("Accept", "application/json")
    assertEquals("xxx", CalendarFeedHttp.fetch(connection))
    assertEquals(8000, connection.settingsAtResponse!!.connectTimeout)
    assertEquals(12000, connection.settingsAtResponse!!.readTimeout)
    assertTrue(connection.settingsAtResponse!!.redirects)
    assertEquals("Immortal/1.0", connection.settingsAtResponse!!.agent)
    assertEquals(listOf("text/calendar", "text/plain", "*/*"),
        connection.settingsAtResponse!!.accept!!.split(',').map { it.trim() })
    assertEquals(1, body.closes)
    assertEquals(1, connection.disconnects)
  }

  @Test fun httpBudgetFailureRetainsLastGoodReaderEventsAndReportsUnavailable() {
    val bytes = calendar("Saved event").toByteArray(Charsets.UTF_8)
    val savedBody = CountedInput(bytes.size, payload = bytes)
    val saved = FakeConnection(savedBody, advertised = bytes.size.toLong())
    val rejectedBody = CountedInput(32)
    val rejected = FakeConnection(rejectedBody, advertised = LIMIT.toLong() + 1)
    val connections = ArrayDeque<FakeConnection>().apply { add(saved); add(rejected) }
    var loads = 0
    val reader = CalendarFeedReader({ loads++; CalendarFeedHttp.fetch(connections.removeFirst()) },
        nowMillis = { 0L })
    val first = reader.read("https://fixture.invalid/calendar.ics", NOW, 45)
    assertFalse(first.unavailable)
    assertEquals(listOf("Saved event"), first.events.map { it.title })
    val fallback = reader.read("https://fixture.invalid/calendar.ics", NOW, 45,
        forceRefresh = true)
    assertTrue(fallback.unavailable)
    assertEquals(first.events, fallback.events)
    assertEquals(2, loads)
    assertEquals(0, rejected.inputOpens)
    assertEquals(0, rejectedBody.readCalls)
    assertEquals(1, savedBody.closes)
    assertEquals(1, saved.disconnects)
    assertEquals(1, rejected.disconnects)
  }

  private data class RequestSettings(val connectTimeout: Int, val readTimeout: Int,
      val redirects: Boolean, val agent: String?, val accept: String?)

  private class FakeConnection(
      val body: CountedInput,
      private val status: Int = 200,
      private val advertised: Long = -1,
      private val statusFailure: IOException? = null,
      private val inputFailure: IOException? = null,
      private val failDisconnect: Boolean = false,
  ) : HttpURLConnection(URL("https://fixture.invalid/calendar.ics")) {
    var inputOpens = 0
    var errorOpens = 0
    var disconnects = 0
    var settingsAtResponse: RequestSettings? = null
    val errorBody = CountedInput(32)
    override fun connect() = Unit
    override fun usingProxy() = false
    override fun disconnect() {
      disconnects++
      if (failDisconnect) throw IllegalStateException("disconnect cleanup")
    }
    override fun getResponseCode(): Int {
      settingsAtResponse = RequestSettings(connectTimeout, readTimeout,
          instanceFollowRedirects, getRequestProperty("User-Agent"), getRequestProperty("Accept"))
      statusFailure?.let { throw it }
      return status
    }
    override fun getContentLengthLong() = advertised
    override fun getContentLength() =
        if (advertised in 0..Int.MAX_VALUE.toLong()) advertised.toInt() else -1
    override fun getHeaderField(name: String?): String? =
        if (name.equals("Content-Length", ignoreCase = true) && advertised >= 0)
          advertised.toString() else null
    override fun getInputStream(): InputStream {
      inputOpens++
      inputFailure?.let { throw it }
      return body
    }
    override fun getErrorStream(): InputStream {
      errorOpens++
      return errorBody
    }
  }

  /** Generates large ASCII bodies without allocating them; counts only bytes delivered. */
  private class CountedInput(
      private val size: Int,
      private val payload: ByteArray? = null,
      private val failAfter: Int? = null,
      private val failClose: Boolean = false,
  ) : InputStream() {
    var bytesRead = 0
    var readCalls = 0
    var closes = 0
    override fun read(): Int {
      readCalls++
      failAfter?.let { if (bytesRead >= it) throw IOException("read failure") }
      if (bytesRead == size) return -1
      val value = payload?.get(bytesRead)?.toInt()?.and(255) ?: 'x'.code
      bytesRead++
      return value
    }
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      readCalls++
      failAfter?.let { if (bytesRead >= it) throw IOException("read failure") }
      if (bytesRead == size) return -1
      val count = minOf(length, size - bytesRead, failAfter?.minus(bytesRead) ?: length)
      if (payload == null) java.util.Arrays.fill(target, offset, offset + count, 'x'.code.toByte())
      else System.arraycopy(payload, bytesRead, target, offset, count)
      bytesRead += count
      return count
    }
    override fun close() {
      closes++
      if (failClose) throw IOException("close cleanup")
    }
  }

  private fun calendar(title: String) = """
    BEGIN:VCALENDAR
    BEGIN:VEVENT
    SUMMARY:$title
    DTSTART:20231115T140000Z
    DTEND:20231115T150000Z
    END:VEVENT
    END:VCALENDAR
  """.trimIndent()

  private companion object {
    const val LIMIT = 8 * 1024 * 1024
    const val NOW = 1_700_000_000_000L
  }
}
