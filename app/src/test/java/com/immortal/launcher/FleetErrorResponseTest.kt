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
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

/** A stalled upload must not delay a rejection that the server already knows. */
class FleetErrorResponseTest {
  private class StalledBodySocket : Socket() {
    private var replyFlushed = false
    val output = object : ByteArrayOutputStream() {
      override fun flush() { replyFlushed = true }
    }
    var drainStartedBeforeReply = false
    var drainAttempted = false
    var outputShutdown = false
    var drainStartedBeforeOutputShutdown = false
    var closed = false
    private val head = ("POST /fixture HTTP/1.1\r\n" +
        "Content-Length: 1048577\r\nConnection: close\r\n\r\n").toByteArray()
    private var position = 0
    private val input = object : InputStream() {
      override fun read(): Int {
        if (position < head.size) return head[position++].toInt() and 255
        return stalled()
      }
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == head.size) return stalled()
        val count = minOf(length, head.size - position)
        head.copyInto(bytes, offset, position, position + count)
        position += count
        return count
      }
      private fun stalled(): Int {
        drainAttempted = true
        drainStartedBeforeReply = !replyFlushed
        drainStartedBeforeOutputShutdown = !outputShutdown
        throw SocketTimeoutException("fixture client has not sent its body")
      }
    }
    override fun getInetAddress(): InetAddress = InetAddress.getByName("127.0.0.1")
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun setSoTimeout(value: Int) {}
    override fun shutdownOutput() { outputShutdown = true }
    override fun close() { closed = true }
  }

  private fun serve(handler: (FleetHttpServer.Request) -> FleetHttpServer.Response): StalledBodySocket {
    val socket = StalledBodySocket()
    val server = FleetHttpServer(0, handler)
    try {
      FleetHttpServer::class.java.getDeclaredMethod("serve", Socket::class.java)
          .apply { isAccessible = true }.invoke(server, socket)
      return socket
    } finally { server.stop() }
  }

  @Test fun unauthorizedReplyArrivesBeforeWaitingForUnsentUpload() {
    val socket = serve { FleetHttpServer.Response(401, """{"ok":false,"error":"unauthorized"}""") }
    assertFalse("the complete rejection must be flushed before cleanup can block",
        socket.drainStartedBeforeReply)
    assertTrue(socket.output.toString("UTF-8").startsWith("HTTP/1.1 401 Unauthorized\r\n"))
    assertTrue(socket.drainAttempted)
    assertTrue(socket.closed)
  }

  @Test fun oversizedTextReplyArrivesWithoutWaitingForRejectedBody() {
    var applied = false
    val socket = serve {
      it.bodyText()
      applied = true
      FleetHttpServer.Response(200, "{}")
    }
    assertFalse(applied)
    assertFalse("body-limit rejection must not wait on a body it will never apply",
        socket.drainStartedBeforeReply)
    assertTrue(socket.output.toString("UTF-8").startsWith("HTTP/1.1 413 Payload Too Large\r\n"))
    assertTrue(socket.closed)
  }

  @Test fun internalErrorReplyArrivesBeforeUnreadBodyCleanup() {
    val socket = serve {
      FleetHttpServer.Response(500, """{"ok":false,"error":"server_error"}""")
    }
    assertFalse("a failed command must report its error before upload cleanup",
        socket.drainStartedBeforeReply)
    assertTrue(socket.output.toString("UTF-8").startsWith("HTTP/1.1 500 Internal Server Error\r\n"))
    assertTrue(socket.output.toString("UTF-8").endsWith("""{"ok":false,"error":"server_error"}"""))
    assertTrue(socket.closed)
  }

  @Test fun streamedErrorSignalsReplyCompletionBeforeCleanupAndReleasesOnce() {
    var releases = 0
    val socket = serve {
      FleetHttpServer.Response.stream(403, "text/plain", -1,
          onClose = { releases++ }) { it.write("rejected".toByteArray()) }
    }
    assertFalse("an unknown-length error needs write-side EOF before cleanup can wait",
        socket.drainStartedBeforeOutputShutdown)
    assertTrue(socket.output.toString("UTF-8").endsWith("rejected"))
    assertFalse(socket.output.toString("UTF-8").contains("Content-Length:"))
    assertTrue(socket.outputShutdown)
    assertEquals(1, releases)
    assertTrue(socket.closed)
  }
}
