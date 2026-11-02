/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real server boundary before a command can apply its parsed body. */
class FleetJsonBodyServerTest {
  private class WireSocket(bytes: ByteArray) : Socket() {
    val input = ByteArrayInputStream(bytes)
    val output = ByteArrayOutputStream()
    var closed = false
    override fun getInetAddress(): InetAddress = InetAddress.getByName("127.0.0.1")
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun setSoTimeout(value: Int) {}
    override fun close() { closed = true }
  }

  private fun serve(body: ByteArray, declared: Int = body.size,
      lengthHeader: String? = declared.toString(),
      handler: (FleetHttpServer.Request) -> FleetHttpServer.Response): String {
    val lengthLine = lengthHeader?.let { "Content-Length: $it\r\n" }.orEmpty()
    val header = "POST /fixture HTTP/1.1\r\n${lengthLine}\r\n".toByteArray()
    val socket = WireSocket(header + body)
    val server = FleetHttpServer(0, handler)
    try {
      FleetHttpServer::class.java.getDeclaredMethod("serve", Socket::class.java)
          .apply { isAccessible = true }.invoke(server, socket)
      assertTrue(socket.closed)
      return socket.output.toString("UTF-8")
    } finally { server.stop() }
  }

  @Test fun incompleteJsonCannotReachCommandApplication() {
    var applied = false
    val response = serve("{}".toByteArray(), declared = 100) {
      it.bodyText()
      applied = true
      FleetHttpServer.Response(200, """{"ok":true}""")
    }
    assertFalse("a complete JSON prefix is still an incomplete request", applied)
    assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"))
    assertTrue(response.endsWith("""{"ok":false,"error":"incomplete_body"}"""))
  }

  @Test fun oversizedJsonCannotApplyACappedPrefix() {
    var applied = false
    val response = serve("{} trailing body".toByteArray()) {
      it.bodyText(2)
      applied = true
      FleetHttpServer.Response(200, """{"ok":true}""")
    }
    assertFalse(applied)
    assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large\r\n"))
    assertTrue(response.endsWith("""{"ok":false,"error":"body_too_large"}"""))
  }

  @Test fun fullUnicodeJsonAtTheByteLimitStillAppliesOnce() {
    val body = """{"name":"café 家"}""".toByteArray(Charsets.UTF_8)
    var calls = 0
    val response = serve(body) {
      assertEquals(String(body, Charsets.UTF_8), it.bodyText(body.size))
      calls++
      FleetHttpServer.Response(200, """{"ok":true}""")
    }
    assertEquals(1, calls)
    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
  }

  @Test fun deliberateEmptyBodyKeepsExistingSuccessfulBehavior() {
    val response = serve(byteArrayOf()) {
      assertEquals("", it.bodyText())
      FleetHttpServer.Response(200, """{"ok":true}""")
    }
    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
  }

  @Test fun overflowingLengthCannotBecomeASuccessfulEmptyRequest() {
    for (length in listOf("2147483648", "99999999999999999999999999")) {
      var dispatched = false
      val response = serve("{}".toByteArray(), lengthHeader = length) {
        dispatched = true
        FleetHttpServer.Response(200, """{"ok":true}""")
      }
      assertFalse(length, dispatched)
      assertTrue(length, response.startsWith("HTTP/1.1 413 Payload Too Large\r\n"))
      assertTrue(response.endsWith("""{"ok":false,"error":"body_too_large"}"""))
    }
  }

  @Test fun invalidExplicitLengthsCannotBecomeEmptyRequests() {
    for (length in listOf("-2", "+2", "invalid", "")) {
      var dispatched = false
      val response = serve("{}".toByteArray(), lengthHeader = length) {
        dispatched = true
        FleetHttpServer.Response(200, """{"ok":true}""")
      }
      assertFalse(length, dispatched)
      assertTrue(length, response.startsWith("HTTP/1.1 400 Bad Request\r\n"))
    }
  }

  @Test fun absentLengthStillSupportsAnEmptyRequest() {
    val response = serve(byteArrayOf(), lengthHeader = null) {
      assertEquals("", it.bodyText())
      FleetHttpServer.Response(200, """{"ok":true}""")
    }
    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
  }

  @Test fun parsingCatchesCanPreserveBodyRejectionWithoutApplyingACommand() {
    for ((declared, limit, status) in listOf(Triple(100, 1024, 400), Triple(2, 1, 413))) {
      var applied = false
      val response = serve("{}".toByteArray(), declared) {
        runCatching { it.bodyText(limit) }.fold(
            onSuccess = { applied = true; FleetHttpServer.Response(200, """{"ok":true}""") },
            onFailure = { FleetHttpServer.requestBodyError(it)
                ?: FleetHttpServer.Response(400, """{"ok":false,"error":"bad_json"}""") })
      }
      assertFalse(applied)
      assertTrue(response.startsWith("HTTP/1.1 $status "))
    }
  }

  @Test fun unrelatedIoFailuresAreNotMisclassifiedAsRequestBodyErrors() {
    assertNull(FleetHttpServer.requestBodyError(IOException("upstream failure")))
    assertNull(FleetHttpServer.requestBodyError(EOFException("upstream incomplete response")))
    assertNull(FleetHttpServer.requestBodyError(IllegalArgumentException("invalid command")))
  }
}
