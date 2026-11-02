/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual connection parser and its shared body stream without a network. */
class FleetHttpServerBufferedInputTest {
  private class WireInput(private val bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE,
      private val headerEnd: Int = Int.MAX_VALUE, private val bodyAllowed: () -> Boolean = { true }) : InputStream() {
    var position = 0
    var singleReads = 0
    var bulkReads = 0
    override fun read(): Int {
      singleReads++
      if (position == bytes.size) return -1
      check(position < headerEnd || bodyAllowed()) { "body requested before 100 Continue" }
      return bytes[position++].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
      bulkReads++
      if (len == 0) return 0
      if (position == bytes.size) return -1
      check(position < headerEnd || bodyAllowed()) { "body requested before 100 Continue" }
      val beforeContinue = if (position < headerEnd && !bodyAllowed()) headerEnd - position else Int.MAX_VALUE
      val count = minOf(len, chunk, bytes.size - position, beforeContinue)
      bytes.copyInto(b, off, position, position + count)
      position += count
      return count
    }
    override fun available() = bytes.size - position
  }

  private class WireSocket(val input: WireInput, val output: ByteArrayOutputStream,
      private val onClose: () -> Unit = {}) : Socket() {
    var wasClosed = false
    var timeout = 0
    override fun getInetAddress(): InetAddress = InetAddress.getByName("127.0.0.1")
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun setSoTimeout(value: Int) { timeout = value }
    override fun close() { onClose(); wasClosed = true }
  }

  private fun serve(input: WireInput, output: ByteArrayOutputStream = ByteArrayOutputStream(),
      onClose: () -> Unit = {}, handler: (FleetHttpServer.Request) -> FleetHttpServer.Response): String {
    val socket = WireSocket(input, output, onClose)
    val server = FleetHttpServer(0, handler)
    try {
      FleetHttpServer::class.java.getDeclaredMethod("serve", Socket::class.java).apply { isAccessible = true }
          .invoke(server, socket)
      assertTrue(socket.wasClosed)
      assertEquals(30_000, socket.timeout)
      return output.toString("UTF-8")
    } finally { server.stop() }
  }

  private fun wire(body: ByteArray = byteArrayOf(), extra: String = "", method: String = "POST"): ByteArray =
      "$method /fixture?x=one%20two HTTP/1.1\r\nHost: portal\r\nContent-Length: ${body.size}\r\n$extra\r\n"
          .toByteArray() + body

  @Test fun headersUseBulkReadsRatherThanOneSocketReadPerByte() {
    val input = WireInput(wire(extra = "X-Fixture: ${"x".repeat(2048)}\r\n"))
    val response = serve(input) { request ->
      assertEquals("/fixture", request.path)
      assertEquals("one two", request.queryParam("x"))
      FleetHttpServer.Response(200, "{}")
    }
    assertTrue(response.startsWith("HTTP/1.1 200"))
    assertEquals("header parsing must not perform raw single-byte reads", 0, input.singleReads)
    assertTrue("one complete small request needs at most two bulk reads", input.bulkReads <= 2)
  }

  @Test fun adjacentUtf8JsonBodyIsNotLostByHeaderReadAhead() {
    val body = "{\"name\":\"café 家\"}".toByteArray(Charsets.UTF_8)
    val input = WireInput(wire(body))
    val response = serve(input) {
      assertEquals(String(body, Charsets.UTF_8), it.bodyText())
      FleetHttpServer.Response(200, "{\"ok\":true}")
    }
    assertTrue(response.endsWith("{\"ok\":true}"))
    assertEquals(wire(body).size, input.position)
  }

  @Test fun largeBinaryUploadKeepsEveryByteAcrossBufferBoundaries() {
    val body = ByteArray(100_003) { (it * 31).toByte() }
    val output = ByteArrayOutputStream()
    serve(WireInput(wire(body))) {
      assertEquals(body.size.toLong(), it.streamBodyTo(output))
      FleetHttpServer.Response(200, "{}")
    }
    assertArrayEquals(body, output.toByteArray())
  }

  @Test fun rejectedTextLimitDoesNotConsumeTheUploadStream() {
    val body = "prefix-".toByteArray() + ByteArray(40_007) { (it % 95 + 32).toByte() }
    val output = ByteArrayOutputStream()
    serve(WireInput(wire(body))) {
      assertThrows(java.io.IOException::class.java) { it.bodyText(7) }
      assertEquals(body.size.toLong(), it.streamBodyTo(output))
      FleetHttpServer.Response(200, "{}")
    }
    assertArrayEquals(body, output.toByteArray())
  }

  @Test fun unreadLargeRejectedBodyIsStillDrainedBeforeSocketClose() {
    val bytes = wire(ByteArray(70_001) { 42 })
    val input = WireInput(bytes)
    val response = serve(input) { FleetHttpServer.Response(403, "{}") }
    assertTrue(response.startsWith("HTTP/1.1 403"))
    assertEquals(bytes.size, input.position)
  }

  @Test fun oneByteNetworkFragmentsStillParseAndDeliverBody() {
    val body = "fragmented body".toByteArray()
    val response = serve(WireInput(wire(body), chunk = 1)) {
      assertEquals(String(body), it.bodyText())
      FleetHttpServer.Response(200, "{}")
    }
    assertTrue(response.startsWith("HTTP/1.1 200"))
  }

  @Test fun expectContinueDoesNotWaitForBodyBeforeInterimResponse() {
    val body = ByteArray(25_003) { (it % 251).toByte() }
    val bytes = wire(body, "Expect: 100-continue\r\n")
    val output = ByteArrayOutputStream()
    val input = WireInput(bytes, headerEnd = bytes.size - body.size,
        bodyAllowed = { output.toString("US-ASCII").contains("100 Continue\r\n\r\n") })
    val copied = ByteArrayOutputStream()
    val response = serve(input, output) {
      assertEquals(body.size.toLong(), it.streamBodyTo(copied))
      FleetHttpServer.Response(200, "{}")
    }
    assertTrue(response.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200"))
    assertArrayEquals(body, copied.toByteArray())
  }

  @Test fun optionsDoesNotDispatchAnApplicationHandler() {
    val response = serve(WireInput(wire(method = "OPTIONS"))) { error("OPTIONS must not dispatch") }
    assertTrue(response.startsWith("HTTP/1.1 204"))
    assertTrue(response.contains("Access-Control-Allow-Methods: GET, POST, OPTIONS"))
  }

  @Test fun incompleteHeaderReturnsBadRequestWithoutDispatch() {
    val response = serve(WireInput("GET /info HTTP/1.1\r\nHost: portal\r\n".toByteArray())) {
      error("incomplete header must not dispatch")
    }
    assertTrue(response.startsWith("HTTP/1.1 400"))
  }

  @Test fun oversizedHeaderKeepsExistingRejectionBoundary() {
    val response = serve(WireInput(wire(extra = "X-Oversize: ${"x".repeat(66_000)}\r\n"))) {
      error("oversized header must not dispatch")
    }
    assertTrue(response.startsWith("HTTP/1.1 400"))
  }

  @Test fun streamedBodyStopsAtDeclaredLengthWithReadAheadTrailer() {
    val body = ByteArray(97) { (it * 7).toByte() }
    val copied = ByteArrayOutputStream()
    val bytes = wire(body) + "ignored bytes beyond Content-Length".toByteArray()
    serve(WireInput(bytes)) {
      assertEquals(body.size.toLong(), it.streamBodyTo(copied))
      assertEquals(0L, it.streamBodyTo(copied))
      FleetHttpServer.Response(200, "{}")
    }
    assertArrayEquals(body, copied.toByteArray())
  }

  @Test fun responseStartsAfterDrainAndSocketClosesAfterCompleteResponse() {
    val bytes = wire(ByteArray(70_001) { 42 })
    val input = WireInput(bytes)
    val output = object : ByteArrayOutputStream() {
      override fun write(b: ByteArray, off: Int, len: Int) {
        assertEquals("body must be drained before response bytes", bytes.size, input.position)
        super.write(b, off, len)
      }
    }
    var closeVerified = false
    serve(input, output, onClose = {
      assertTrue("response must be complete before closing", output.toString("UTF-8").endsWith("{\"complete\":true}"))
      closeVerified = true
    }) { FleetHttpServer.Response(200, "{\"complete\":true}") }
    assertTrue(closeVerified)
  }
}
