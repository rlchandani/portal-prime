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
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory byte fixtures for hostile WebSocket handshakes and frames; no real socket. */
class MaWebSocketCoverageTest {
  private class Wire(
      val frames: ByteArray = byteArrayOf(),
      val response: (String) -> String = { accepted(it) },
  ) : Socket() {
    val sent = ByteArrayOutputStream()
    var stopped = false
    var failWrites = false
    private var input: InputStream? = null
    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getOutputStream(): OutputStream = object : OutputStream() {
      override fun write(b: Int) { if (failWrites) throw IOException("peer reset"); sent.write(b) }
      override fun write(b: ByteArray, off: Int, len: Int) { if (failWrites) throw IOException("peer reset"); sent.write(b, off, len) }
    }
    private fun stream(): InputStream = input ?: run {
      val key = sent.toString("ISO-8859-1").lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(":").trim()
      ByteArrayInputStream(response(key).toByteArray(Charsets.ISO_8859_1) + frames).also { input = it }
    }
    override fun getInputStream(): InputStream = object : InputStream() {
      override fun read(): Int = stream().read()
      override fun read(b: ByteArray, off: Int, len: Int): Int = stream().read(b, off, len)
    }
    override fun close() { stopped = true }

    companion object {
      fun accept(key: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
          .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
      fun accepted(key: String, extra: String = "", status: String = "HTTP/1.1 101 Switching Protocols") =
          "$status\r\nUpgrade: WebSocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept(key)}\r\n$extra\r\n"
    }
  }

  private fun connected(frames: ByteArray = byteArrayOf()): Pair<MaWebSocket, Wire> {
    val wire = Wire(frames)
    val ws = MaWebSocket("192.0.2.10", 8095, socketFactory = { wire })
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
  private fun close(code: Int, reason: ByteArray = byteArrayOf()) = frame(0x88, byteArrayOf((code ushr 8).toByte(), code.toByte()) + reason)
  private fun handshake(response: (String) -> String): Pair<Result<Boolean>, Wire> {
    val wire = Wire(response = response)
    return runCatching { MaWebSocket("192.0.2.10", 8095, socketFactory = { wire }).connect() } to wire
  }

  /** Unmask one client frame starting at [at]; returns (opcode byte, payload, next offset). */
  private fun clientFrame(bytes: ByteArray, at: Int): Triple<Int, ByteArray, Int> {
    val b0 = bytes[at].toInt() and 255
    val b1 = bytes[at + 1].toInt() and 255
    assertEquals("client frames are masked", 0x80, b1 and 0x80)
    var i = at + 2
    val size = when (b1 and 127) {
      126 -> ((bytes[i].toInt() and 255) shl 8 or (bytes[i + 1].toInt() and 255)).also { i += 2 }
      127 -> (0 until 8).fold(0L) { acc, k -> (acc shl 8) or (bytes[i + k].toLong() and 255) }.toInt().also { i += 8 }
      else -> b1 and 127
    }
    val mask = bytes.copyOfRange(i, i + 4); i += 4
    val payload = ByteArray(size) { (bytes[i + it].toInt() xor mask[it % 4].toInt()).toByte() }
    return Triple(b0, payload, i + size)
  }

  @Test fun acceptsMinimalStatusLineAndCaseInsensitiveTokens() {
    val (result, _) = handshake { Wire.accepted(it, status = "HTTP/1.1 101") }
    assertTrue(result.getOrThrow())
  }

  @Test fun rejectsHandshakesWithAmbiguousOrNegotiatedHeaders() {
    val mutations = listOf<(String) -> String>(
        { Wire.accepted(it, extra = "Sec-WebSocket-Extensions: permessage-deflate\r\n") },
        { Wire.accepted(it, extra = "Sec-WebSocket-Protocol: chat\r\n") },
        { Wire.accepted(it, extra = " folded: continuation\r\n") },
        { Wire.accepted(it, extra = ": empty-name\r\n") },
        { Wire.accepted(it, extra = "no-colon-here\r\n") },
        { Wire.accepted(it, extra = "Bad_Name: x\r\n") },
        { Wire.accepted(it, extra = (1..65).joinToString("") { n -> "X-$n: y\r\n" }) },
        { Wire.accepted(it, status = "HTTP/1.0 101 Switching Protocols") },
        { Wire.accepted(it, status = "HTTP/1.1 1010") },
    )
    for ((i, mutate) in mutations.withIndex()) {
      val (result, wire) = handshake(mutate)
      assertFalse("case $i", result.getOrThrow())
      assertTrue("case $i must close", wire.stopped)
    }
  }

  @Test fun rejectsControlBytesAndOverlongHeaderLines() {
    for (bad in listOf<(String) -> String>(
        { Wire.accepted(it, extra = "X: a\u0001b\r\n") },
        { Wire.accepted(it, extra = "X: café\r\n") },
        { "HTTP/1.1 101 OK\r\nX: " + "a".repeat(4100) + "\r\n\r\n" },
    )) {
      val (result, wire) = handshake(bad)
      assertTrue(result.exceptionOrNull() is IOException)
      assertTrue(wire.stopped)
    }
  }

  @Test fun bareCarriageReturnsDoNotTerminateHeaders() {
    val (result, _) = handshake { "HTTP/1.1 101 OK\r\r\n\n\r\n" + Wire.accepted(it).substringAfter("\r\n") }
    assertFalse(result.getOrDefault(true))
  }

  @Test fun invalidEndpointArgumentsFailBeforeOpeningSocket() {
    for (ws in listOf(
        MaWebSocket("192.0.2.10", 0, socketFactory = { error("opened") }),
        MaWebSocket("192.0.2.10", 65536, socketFactory = { error("opened") }),
        MaWebSocket("192.0.2.10", 80, path = "ws", socketFactory = { error("opened") }),
        MaWebSocket("192.0.2.10", 80, path = "/w s", socketFactory = { error("opened") }),
        MaWebSocket("192.0.2.10", 80, path = "/" + "a".repeat(2048), socketFactory = { error("opened") }),
        MaWebSocket("", 80, socketFactory = { error("opened") }),
        MaWebSocket("a".repeat(254), 80, socketFactory = { error("opened") }),
        MaWebSocket("ma\\example", 80, socketFactory = { error("opened") }),
    )) assertTrue(runCatching { ws.connect() }.exceptionOrNull() is IllegalArgumentException)
    assertTrue(runCatching { MaWebSocket("192.0.2.10", 80, socketFactory = { error("opened") }).connect(0) }
        .exceptionOrNull() is IllegalArgumentException)
    assertTrue(runCatching { MaWebSocket("192.0.2.10", 80).setReadTimeout(0) }.isFailure)
    assertTrue(runCatching { MaWebSocket("192.0.2.10", 80).setReadTimeout(60_001) }.isFailure)
  }

  @Test fun ipv6HostIsBracketedInTheHostHeader() {
    val wire = Wire()
    assertTrue(MaWebSocket("::1", 8095, path = "/ws?x=1", socketFactory = { wire }).connect())
    val request = wire.sent.toString("ISO-8859-1")
    assertTrue(request, request.startsWith("GET /ws?x=1 HTTP/1.1\r\nHost: [::1]:8095\r\n"))
    val bracketed = Wire()
    assertTrue(MaWebSocket("[::1]", 8095, socketFactory = { bracketed }).connect())
    assertTrue(bracketed.sent.toString("ISO-8859-1").contains("Host: [::1]:8095\r\n"))
  }

  @Test fun connectIsSingleUseAndClosedSocketCannotReconnect() {
    val (ws, _) = connected()
    assertTrue(runCatching { ws.connect() }.exceptionOrNull() is IllegalStateException)
    ws.close()
    ws.close() // idempotent
    assertTrue(runCatching { ws.connect() }.exceptionOrNull() is IllegalStateException)
    assertNull(ws.readText())
  }

  @Test fun outgoingFramesUseMinimalLengthEncodingAndMasking() {
    for (size in listOf(0, 125, 126, 65535, 65536)) {
      val (ws, wire) = connected()
      val start = wire.sent.size()
      val text = "a".repeat(size)
      ws.sendText(text)
      val bytes = wire.sent.toByteArray()
      val (b0, payload, end) = clientFrame(bytes, start)
      assertEquals(0x81, b0)
      assertEquals("size $size", text, String(payload, Charsets.UTF_8))
      assertEquals(bytes.size, end)
      val lenByte = bytes[start + 1].toInt() and 127
      assertEquals(when { size < 126 -> size; size < 65536 -> 126; else -> 127 }, lenByte)
      ws.close()
    }
  }

  @Test fun loneSurrogateIsRefusedBeforeWriting() {
    val (ws, wire) = connected()
    val before = wire.sent.size()
    assertTrue(runCatching { ws.sendText("bad \uD800 text") }.isFailure)
    assertEquals(before, wire.sent.size())
    assertTrue(runCatching { ws.sendText("x".repeat(MaWebSocket.MAX_MESSAGE_BYTES + 1)) }.exceptionOrNull() is IllegalArgumentException)
  }

  @Test fun writeFailureClosesTheConnection() {
    val (ws, wire) = connected()
    wire.failWrites = true
    assertTrue(runCatching { ws.sendText("hi") }.exceptionOrNull() is IOException)
    assertTrue(wire.stopped)
    assertTrue(runCatching { ws.sendText("again") }.exceptionOrNull() is IOException)
  }

  @Test fun validCloseFrameIsEchoedAndEndsTheStream() {
    for (frame in listOf(close(1000, "bye".toByteArray()), frame(0x88, byteArrayOf()), close(3000), close(4999), close(1014))) {
      val (ws, wire) = connected(frame)
      val start = wire.sent.size()
      assertNull(ws.readText())
      assertTrue(wire.stopped)
      val (b0, payload, _) = clientFrame(wire.sent.toByteArray(), start)
      assertEquals(0x88, b0)
      assertArrayEquals(frame.copyOfRange(2, frame.size), payload)
      assertNull(ws.readText())
    }
  }

  @Test fun invalidCloseCodesOrReasonsAreProtocolErrors() {
    for (frame in listOf(close(999), close(1004), close(1006), close(1015), close(2999), close(5000),
        close(1000, byteArrayOf(0xFF.toByte())), frame(0x08, close(1000).copyOfRange(2, 4)))) {
      val (ws, wire) = connected(frame)
      assertTrue(runCatching { ws.readText() }.exceptionOrNull() is IOException)
      assertTrue(wire.stopped)
    }
  }

  @Test fun extendedLengthTextFramesAndPongBetweenFragmentsAreAccepted() {
    val medium = "m".repeat(126)
    val large = "L".repeat(65536)
    val (ws, _) = connected(frame(0x81, medium.toByteArray()) + frame(0x81, large.toByteArray()) +
        frame(0x01, "a".toByteArray()) + frame(0x8A, byteArrayOf()) + frame(0x00, "b".toByteArray()) + frame(0x80, "c".toByteArray()))
    assertEquals(medium, ws.readText())
    assertEquals(large, ws.readText())
    assertEquals("abc", ws.readText())
    assertNull("EOF before a new frame closes cleanly", ws.readText())
  }

  @Test fun nonMinimalSixtyFourBitLengthIsRejected() {
    val (ws, wire) = connected(byteArrayOf(0x81.toByte(), 127, 0, 0, 0, 0, 0, 0, 0, 5) + "hello".toByteArray())
    assertTrue(runCatching { ws.readText() }.exceptionOrNull() is IOException)
    assertTrue(wire.stopped)
  }

  @Test fun endlessControlFramesCannotPinTheReader() {
    val pings = ByteArray(1025 * 2) { if (it % 2 == 0) 0x8A.toByte() else 0 }
    val (ws, wire) = connected(pings + frame(0x81, "late".toByteArray()))
    assertTrue(runCatching { ws.readText() }.exceptionOrNull() is IOException)
    assertTrue(wire.stopped)
  }
}
