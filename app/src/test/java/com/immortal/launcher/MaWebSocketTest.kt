/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Byte-level fixtures, not a real MA server or device test. */
class MaWebSocketTest {
  private class Wire(
      val frames: ByteArray = byteArrayOf(),
      val response: (String) -> String = { key ->
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
      },
      val idleAfterFrameBytes: Int? = null,
  ) : Socket() {
    val sent = ByteArrayOutputStream()
    var stopped = false
    private var input: InputStream? = null
    private var consumed = 0
    private var idleBoundary: Int? = null
    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getOutputStream() = sent
    override fun getInputStream(): InputStream = object : InputStream() {
      override fun read(): Int {
        if (input == null) {
          val key = sent.toString("ISO-8859-1").lineSequence()
              .first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(":").trim()
          val handshake = response(key).toByteArray(Charsets.ISO_8859_1)
          input = ByteArrayInputStream(handshake + frames)
          idleBoundary = idleAfterFrameBytes?.let { handshake.size + it }
        }
        if (consumed == idleBoundary) {
          idleBoundary = null
          throw SocketTimeoutException("Scripted idle poll")
        }
        return input!!.read().also { if (it >= 0) consumed++ }
      }
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (idleAfterFrameBytes != null) {
          val value = read()
          if (value < 0) return -1
          b[off] = value.toByte()
          return 1
        }
        if (input == null) read().also { if (it >= 0) { b[off] = it.toByte(); return 1 } }
        return input!!.read(b, off, len)
      }
    }
    override fun close() { stopped = true }
  }

  private fun connected(frames: ByteArray): Pair<MaWebSocket, Wire> {
    val wire = Wire(frames)
    val ws = MaWebSocket("ma.example", 8095, socketFactory = { wire })
    assertTrue(ws.connect())
    return ws to wire
  }

  private fun frame(first: Int, bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(first)
    when {
      bytes.size < 126 -> out.write(bytes.size)
      bytes.size < 65536 -> { out.write(126); out.write(bytes.size ushr 8); out.write(bytes.size) }
      else -> { out.write(127); for (shift in 7 downTo 0) out.write((bytes.size.toLong() ushr (shift * 8)).toInt()) }
    }
    out.write(bytes)
    return out.toByteArray()
  }

  @Test fun acceptsVerifiedUpgradeAndReassemblesUtf8WithInterleavedPing() {
    val (ws, wire) = connected(frame(0x01, byteArrayOf(0xE2.toByte())) +
        frame(0x89, "hi".toByteArray()) + frame(0x80, byteArrayOf(0x82.toByte(), 0xAC.toByte())))
    val handshakeBytes = wire.sent.size()
    assertEquals("€", ws.readText())
    val pong = wire.sent.toByteArray().drop(handshakeBytes).map { it.toInt() and 255 }
    assertEquals(0x8A, pong[0])
    assertEquals(0x82, pong[1])
    assertEquals('h'.code, pong[6] xor pong[2])
    assertEquals('i'.code, pong[7] xor pong[3])
    ws.close()
  }


  @Test fun standalonePingOrPongThenIdleTimeoutKeepsConnectionForNextText() {
    for (opcode in listOf(0x89, 0x8A)) {
      val control = frame(opcode, "hi".toByteArray())
      val wire = Wire(control + frame(0x81, "next".toByteArray()),
          idleAfterFrameBytes = control.size)
      val ws = MaWebSocket("ma.example", 8095, socketFactory = { wire })
      try {
        assertTrue(ws.connect())
        val handshakeBytes = wire.sent.size()
        ws.setReadTimeout(250)
        assertTrue(runCatching { ws.readText() }.exceptionOrNull() is SocketTimeoutException)
        assertFalse("Complete standalone control frame must permit idle polling", wire.stopped)
        if (opcode == 0x89) {
          assertEquals(0x8A, wire.sent.toByteArray()[handshakeBytes].toInt() and 255)
        } else assertEquals(handshakeBytes, wire.sent.size())
        assertEquals("next", ws.readText())
        assertFalse(wire.stopped)
      } finally { ws.close() }
    }
  }

  @Test fun timeoutInsideFrameOrFragmentedTextStillClosesConnection() {
    val textStart = frame(0x01, "a".toByteArray())
    val ping = frame(0x89, "hi".toByteArray())
    for ((bytes, boundary) in listOf(
        (textStart + ping + frame(0x80, "b".toByteArray())) to (textStart.size + ping.size),
        frame(0x89, "hi".toByteArray()) to 1,
        frame(0x81, "text".toByteArray()) to 3,
    )) {
      val wire = Wire(bytes, idleAfterFrameBytes = boundary)
      val ws = MaWebSocket("ma.example", 8095, socketFactory = { wire })
      try {
        assertTrue(ws.connect())
        ws.setReadTimeout(250)
        assertTrue(runCatching { ws.readText() }.exceptionOrNull() is SocketTimeoutException)
        assertTrue("Unfinished frame/message parser state must be discarded", wire.stopped)
        assertNull(ws.readText())
      } finally { ws.close() }
    }
  }

  @Test fun rejectsForgedMissingDuplicateAcceptAndWrongUpgrade() {
    for (response in listOf(
        "HTTP/1.1 101 Switching Protocols\r\n\r\n",
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: forged\r\n\r\n",
        "HTTP/1.1 200 OK\r\n\r\n",
    )) {
      val wire = Wire(response = { response })
      val ws = MaWebSocket("ma.example", 8095, socketFactory = { wire })
      assertFalse(runCatching { ws.connect() }.getOrDefault(false))
      assertTrue(wire.stopped)
    }
    for (mutate in listOf<(String) -> String>(
        { it.replace("Upgrade: websocket", "Upgrade: h2c") },
        { it.replace("Connection: keep-alive, Upgrade", "Connection: keep-alive") },
        { it.replace("\r\n\r\n", "\r\nSec-WebSocket-Accept: duplicate\r\n\r\n") },
    )) {
      val base = Wire()
      val wire = Wire(response = { mutate(base.response(it)) })
      assertFalse(MaWebSocket("ma.example", 8095, socketFactory = { wire }).connect())
      assertTrue(wire.stopped)
    }
  }

  @Test fun boundsHeadersAndRejectsTruncatedHandshake() {
    for (response in listOf("HTTP/1.1 101 OK\r\nX: " + "a".repeat(17000),
        "HTTP/1.1 101 OK\r\n", "HTTP/1.1 101 OK\n\n")) {
      val wire = Wire(response = { response })
      assertFalse(runCatching { MaWebSocket("ma.example", 8095, socketFactory = { wire })
          .connect() }.getOrDefault(false))
      assertTrue(wire.stopped)
    }
  }

  @Test fun rejectsMalformedOversizedTruncatedAndIllegalFramesBeforeAllocating() {
    val cases = listOf(
        byteArrayOf(0x81.toByte(), 126, 0), // truncated extended length
        byteArrayOf(0x81.toByte(), 127, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0), // sign bit
        byteArrayOf(0x81.toByte(), 127, 0, 0, 0, 0, 0, 0x20, 0, 1), // > 2 MiB
        byteArrayOf(0x81.toByte(), 126, 0, 1, 65), // nonminimal length
        byteArrayOf(0x81.toByte(), 3, 65), // truncated payload
        byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 0, 0, 0), // server mask
        frame(0x80, "orphan".toByteArray()),
        frame(0x01, "a".toByteArray()) + frame(0x81, "b".toByteArray()),
        frame(0x09, byteArrayOf()), // fragmented control
        frame(0x89, ByteArray(126)),
        frame(0xC1, byteArrayOf()), // reserved bits
        frame(0x82, byteArrayOf()), // unsupported binary
        frame(0x83, byteArrayOf()),
        frame(0x81, byteArrayOf(0xC0.toByte(), 0xAF.toByte())), // invalid UTF8
        frame(0x88, byteArrayOf(1)), // malformed close
        frame(0x88, byteArrayOf(3, 0xED.toByte())), // forbidden code 1005
    )
    for ((index, bytes) in cases.withIndex()) {
      val (ws, wire) = connected(bytes)
      assertTrue("fixture $index", runCatching { ws.readText() }.exceptionOrNull() is IOException)
      assertTrue("fixture $index must close", wire.stopped)
    }
  }

  @Test fun capsAggregateFragmentsAndOutgoingUtf8Bytes() {
    val (ws, wire) = connected(frame(0x01, ByteArray(1_000_001) { 65 }) +
        frame(0x80, ByteArray(1_000_000) { 65 }))
    assertTrue(runCatching { ws.readText() }.exceptionOrNull() is IOException)
    assertTrue(wire.stopped)
    val (sender, sent) = connected(byteArrayOf())
    val before = sent.sent.size()
    assertTrue(runCatching { sender.sendText("€".repeat(700_000)) }.isFailure)
    assertEquals(before, sent.sent.size())
    sender.close()
    assertTrue(runCatching { sender.sendText("after close") }.isFailure)
  }

  @Test fun closeUnblocksReadWithoutWaitingForReadOrWriteLock() {
    val reading = CountDownLatch(1)
    val released = CountDownLatch(1)
    val base = Wire()
    val blocking = object : Socket() {
      val handshake = base.getInputStream()
      override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
      override fun setSoTimeout(timeout: Int) = Unit
      override fun getOutputStream() = base.sent
      override fun getInputStream() = object : InputStream() {
        var handshakeDone = false
        var end = 0
        override fun read(): Int {
          if (!handshakeDone) {
            val b = handshake.read()
            end = if (b == (if (end % 2 == 0) 13 else 10)) end + 1 else 0
            if (end == 4) handshakeDone = true
            return b
          }
          reading.countDown()
          released.await(2, TimeUnit.SECONDS)
          return -1
        }
      }
      override fun close() { released.countDown() }
    }
    val ws = MaWebSocket("ma.example", 8095, socketFactory = { blocking })
    assertTrue(ws.connect())
    val reader = Thread { ws.readText() }
    reader.start()
    assertTrue(reading.await(1, TimeUnit.SECONDS))
    ws.close()
    reader.join(1000)
    assertFalse(reader.isAlive)
  }

  @Test fun refusesSchemesAndHeaderInjectionWithoutOpeningSocket() {
    for (host in listOf("https://ma.example", "wss://ma.example", "ma\r\nX: injected", "user@ma")) {
      var opened = false
      val ws = MaWebSocket(host, 8095, socketFactory = { opened = true; Wire() })
      assertTrue(runCatching { ws.connect() }.isFailure)
      assertFalse(opened)
    }
  }
}
