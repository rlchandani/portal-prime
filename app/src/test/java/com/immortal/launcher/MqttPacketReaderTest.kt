/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MQTT 3.1.1 remaining-length decoding (§2.2.3, 1 to 4 bytes, up to 268,435,455) and the explicit
 * packet cap: a packet over [MqttPacketReader.MAX_PACKET_BYTES] is read past in bounded chunks and
 * skipped, so the connection stays up.
 */
class MqttPacketReaderTest {
  private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

  /** The spec's boundary values with their encodings (MQTT 3.1.1 table 2.4). */
  private val boundaries = linkedMapOf(
      0 to bytes(0x00),
      127 to bytes(0x7F),
      128 to bytes(0x80, 0x01),
      16_383 to bytes(0xFF, 0x7F),
      16_384 to bytes(0x80, 0x80, 0x01),
      2_097_151 to bytes(0xFF, 0xFF, 0x7F),
      2_097_152 to bytes(0x80, 0x80, 0x80, 0x01),
      268_435_455 to bytes(0xFF, 0xFF, 0xFF, 0x7F))

  @Test fun decodesEveryBoundaryValue() {
    for ((value, encoded) in boundaries) {
      val input = ByteArrayInputStream(encoded + bytes(0x42))
      assertEquals("$value", value, MqttRemainingLength.decode(input))
      // It reads exactly the length bytes and nothing after them.
      assertEquals("$value", 0x42, input.read())
    }
  }

  @Test fun encodesEveryBoundaryValueAsTheSpecDoes() {
    for ((value, encoded) in boundaries) assertArrayEquals("$value", encoded, MqttRemainingLength.encode(value))
    assertEquals(268_435_455, MqttRemainingLength.MAX)
  }

  @Test fun encodeAndDecodeRoundTripAcrossTheRange() {
    for (v in listOf(1, 200, 321, 16_385, 100_000, 2_097_153, 9_999_999, 268_435_454)) {
      assertEquals(v, MqttRemainingLength.decode(ByteArrayInputStream(MqttRemainingLength.encode(v))))
    }
  }

  @Test fun aFifthLengthByteIsMalformedAndNotRead() {
    for (encoded in listOf(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x7F), bytes(0x80, 0x80, 0x80, 0x80, 0x01), bytes(0x80, 0x80, 0x80, 0x80, 0x00))) {
      val input = ByteArrayInputStream(encoded)
      assertNull(MqttRemainingLength.decode(input))
      // Rejected on the 4th byte's continuation bit: the 5th byte is never consumed.
      assertEquals(1, input.available())
    }
  }

  @Test fun anEndOfStreamInsideTheLengthIsNull() {
    for (encoded in listOf(bytes(), bytes(0x80), bytes(0xFF, 0xFF), bytes(0x80, 0x80, 0x80))) {
      assertNull(MqttRemainingLength.decode(ByteArrayInputStream(encoded)))
    }
  }

  @Test fun theEncoderRefusesWhatTheProtocolCannotCarry() {
    assertTrue(runCatching { MqttRemainingLength.encode(-1) }.isFailure)
    assertTrue(runCatching { MqttRemainingLength.encode(268_435_456) }.isFailure)
  }

  // --- packets ---------------------------------------------------------------

  private fun packet(type: Int, body: ByteArray) = bytes(type) + MqttRemainingLength.encode(body.size) + body

  private fun publish(topic: String, payload: String): ByteArray {
    val t = topic.toByteArray()
    return packet(0x31, bytes(0, t.size) + t + payload.toByteArray())
  }

  /** A stream of [header] then [n] zero bytes, generated on the fly; records the largest read. */
  private class Zeros(private val header: ByteArray, private var n: Long) : InputStream() {
    private var at = 0
    var largestRead = 0
    var bodyRead = 0L
    override fun read(): Int {
      if (at < header.size) return header[at++].toInt() and 0xff
      if (n <= 0) return -1
      n--; bodyRead++
      return 0
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (at < header.size) return super.read(b, off, len)
      largestRead = maxOf(largestRead, len)
      if (n <= 0) return -1
      val k = minOf(len.toLong(), n).toInt()
      java.util.Arrays.fill(b, off, off + k, 0)
      n -= k; bodyRead += k
      return k
    }
  }

  @Test fun packetsUpToTheCapAreDelivered() {
    val cap = MqttPacketReader.MAX_PACKET_BYTES
    assertEquals(2 * 1024 * 1024, cap)
    val logs = ArrayList<String>()
    val reader = MqttPacketReader(log = { logs += it })
    val small = reader.read(ByteArrayInputStream(publish("a/b", "ON")))!!
    assertEquals(0x30, small.type)
    assertEquals(0x01, small.flags)
    val atCap = reader.read(Zeros(bytes(0x30) + MqttRemainingLength.encode(cap), cap.toLong()))!!
    assertEquals(cap, atCap.body.size)
    assertTrue(logs.isEmpty())
    assertEquals(0, reader.oversizedSkipped)
  }

  @Test fun anOversizedPacketIsSkippedInBoundedChunksAndTheNextOneIsRead() {
    val logs = ArrayList<String>()
    val reader = MqttPacketReader(log = { logs += it })
    val over = MqttPacketReader.MAX_PACKET_BYTES + 1
    val big = Zeros(bytes(0x31) + MqttRemainingLength.encode(over), over.toLong())
    val next = ByteArrayInputStream(publish("portal/x/set", "ping"))
    val stream = SequenceInputStream(big, next)
    val pkt = reader.read(stream)!!
    assertEquals(0x30, pkt.type)
    val t = pkt.body
    assertEquals("portal/x/set", String(t, 2, t[1].toInt()))
    // Every oversized byte was consumed (so framing holds) in reads no larger than the chunk.
    assertEquals(over.toLong(), big.bodyRead)
    assertTrue(big.largestRead in 1..MqttPacketReader.SKIP_CHUNK_BYTES)
    assertEquals(1, reader.oversizedSkipped)
    assertEquals(1, logs.size)
    assertTrue(logs[0], logs[0].contains("$over") && logs[0].contains("0x30"))
  }

  @Test fun theLargestLegalPacketIsSkippedWithoutAllocatingIt() {
    val reader = MqttPacketReader(log = {})
    val max = MqttRemainingLength.MAX
    val big = Zeros(bytes(0x30) + MqttRemainingLength.encode(max), max.toLong())
    val stream = SequenceInputStream(big, ByteArrayInputStream(packet(0xD0, bytes())))
    val pkt = reader.read(stream)!!
    assertEquals(0xD0, pkt.type) // PINGRESP
    assertEquals(max.toLong(), big.bodyRead)
    assertTrue(big.largestRead <= MqttPacketReader.SKIP_CHUNK_BYTES)
  }

  @Test fun oversizedPacketsAreLoggedOncePerConnection() {
    val logs = ArrayList<String>()
    val reader = MqttPacketReader(maxPacketBytes = 10, log = { logs += it })
    val out = ByteArrayOutputStream()
    repeat(3) { out.write(packet(0x30, ByteArray(11))) }
    out.write(packet(0x90, bytes(0, 1, 0))) // SUBACK
    val pkt = reader.read(ByteArrayInputStream(out.toByteArray()))!!
    assertEquals(0x90, pkt.type)
    assertEquals(3, reader.oversizedSkipped)
    assertEquals(1, logs.size)
  }

  @Test fun anOversizedPacketCutShortIsAnEndOfStreamError() {
    val reader = MqttPacketReader(maxPacketBytes = 10, log = {})
    val truncated = packet(0x30, ByteArray(11)).copyOf(8)
    assertTrue(runCatching { reader.read(ByteArrayInputStream(truncated)) }.exceptionOrNull() is EOFException)
  }

  @Test fun aCleanEndOfStreamOrMalformedLengthIsNull() {
    val reader = MqttPacketReader(log = {})
    assertNull(reader.read(ByteArrayInputStream(bytes())))
    assertNull(reader.read(ByteArrayInputStream(bytes(0x30))))
    assertNull(reader.read(ByteArrayInputStream(bytes(0x30, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F))))
    // A body cut short inside an accepted packet is an error, as before.
    assertTrue(runCatching { reader.read(ByteArrayInputStream(bytes(0x30, 0x05, 1, 2))) }.exceptionOrNull() is EOFException)
  }

  @Test fun theConnectionSurvivesAnOversizedRetainedMessage() {
    // A broker that answers CONNECT, replays a retained message over the cap, then a normal one,
    // and finally records what the client sends after reading them.
    val over = MqttPacketReader.MAX_PACKET_BYTES + 512
    val retained = packet(0x31, bytes(0, 1, 't'.code) + ByteArray(over - 3))
    val reply = bytes(0x20, 0x02, 0x00, 0x00) + retained + publish("t", "small")
    val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
    val afterRead = CompletableFuture<ByteArray>()
    Thread {
      runCatching {
        server.accept().use { s ->
          s.soTimeout = 10_000
          val inp = s.getInputStream()
          inp.read() // CONNECT type
          val len = MqttRemainingLength.decode(inp)!!
          inp.readNBytes(len)
          s.getOutputStream().apply { write(reply); flush() }
          afterRead.complete(inp.readNBytes(4))
        }
      }.onFailure { afterRead.completeExceptionally(it) }
    }.apply { isDaemon = true }.start()
    server.use {
      val logs = ArrayList<String>()
      val c = MqttClient("127.0.0.1", server.localPort, "portal-1", "", "", null, log = { logs += it })
      assertTrue(c.connect(keepAliveSec = 30, connectTimeoutMs = 2000))
      val pkt = c.readPacket()!!
      assertEquals("small", String(c.parsePublish(pkt).second))
      // Still connected: the next write reaches the broker.
      c.ping()
      assertArrayEquals(bytes(0xC0, 0x00, 0xE0, 0x00), run { c.disconnect(); afterRead.get(10, TimeUnit.SECONDS) })
      c.close()
      assertEquals(1, logs.size)
    }
  }
}
