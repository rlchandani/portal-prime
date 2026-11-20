/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MQTT 3.1.1 framing against a loopback byte-script "broker" (pure JVM codec, millisecond runtime):
 * CONNECT/PUBLISH/SUBSCRIBE encoding and hostile incoming frames.
 */
class MqttClientCoverageTest {
  private class Broker(private val reply: ByteArray, private val closeAfterReply: Boolean = false) : AutoCloseable {
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
    val port get() = server.localPort
    /** (CONNECT packet bytes incl. fixed header, every byte sent after it until EOF). */
    val received = CompletableFuture<Pair<ByteArray, ByteArray>>()
    init {
      Thread {
        runCatching {
          server.accept().use { sock ->
            sock.soTimeout = 5000
            val inp = DataInputStream(sock.getInputStream())
            val connect = ByteArrayOutputStream()
            connect.write(inp.readUnsignedByte())
            var len = 0
            var shift = 0
            while (true) {
              val b = inp.readUnsignedByte()
              connect.write(b)
              len = len or ((b and 0x7f) shl shift)
              if (b and 0x80 == 0) break
              shift += 7
            }
            val body = ByteArray(len).also { inp.readFully(it) }
            connect.write(body)
            sock.getOutputStream().apply { write(reply); flush() }
            if (closeAfterReply) sock.shutdownOutput()
            received.complete(connect.toByteArray() to inp.readBytes())
          }
        }.onFailure { received.completeExceptionally(it) }
      }.apply { isDaemon = true }.start()
    }
    override fun close() = server.close()
  }

  private val connack = byteArrayOf(0x20, 0x02, 0x00, 0x00)
  private fun client(port: Int, user: String = "", pass: String = "", will: MqttClient.Will? = null) =
      MqttClient("127.0.0.1", port, "portal-1", user, pass, will)

  private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
  private fun str(b: ByteArray, at: Int): Pair<String, Int> { val n = u16(b, at); return String(b, at + 2, n, Charsets.UTF_8) to at + 2 + n }

  @Test fun connectEncodesWillUsernameAndPasswordInSpecOrder() {
    Broker(connack).use { broker ->
      val c = client(broker.port, user = "ha-user", pass = "test-pass", will = MqttClient.Will("portal/availability", "offline", retain = true))
      assertTrue(c.connect(keepAliveSec = 60, connectTimeoutMs = 2000))
      c.close()
      val packet = broker.received.get(3, TimeUnit.SECONDS).first
      assertEquals(0x10, packet[0].toInt() and 0xff)
      val body = packet.copyOfRange(2, packet.size) // remaining length < 128 here
      assertEquals(body.size, packet[1].toInt())
      var (text, at) = str(body, 0)
      assertEquals("MQTT", text)
      assertEquals(4, body[at].toInt())
      val flags = body[at + 1].toInt() and 0xff
      assertEquals(0x02 or 0x04 or 0x20 or 0x80 or 0x40, flags)
      assertEquals(60, u16(body, at + 2))
      at += 4
      val fields = mutableListOf<String>()
      while (at < body.size) { val (s, next) = str(body, at); fields += s; at = next }
      assertEquals(listOf("portal-1", "portal/availability", "offline", "ha-user", "test-pass"), fields)
    }
  }

  @Test fun connectWithoutCredentialsOrRetainedWillSetsOnlyCleanSessionAndWill() {
    Broker(connack).use { broker ->
      val c = client(broker.port, user = " ", will = MqttClient.Will("t", "p", retain = false))
      assertTrue(c.connect(30, 2000))
      c.close()
      val packet = broker.received.get(3, TimeUnit.SECONDS).first
      assertEquals(0x06, packet[2 + 6 + 1].toInt() and 0xff)
    }
  }

  @Test fun connectRejectsNonConnackShortConnackAndEarlyEof() {
    for (reply in listOf(byteArrayOf(0x30, 0x02, 0x00, 0x00), byteArrayOf(0x20, 0x01, 0x00), byteArrayOf())) {
      Broker(reply, closeAfterReply = true).use { broker ->
        val c = client(broker.port)
        try { assertFalse(reply.contentToString(), c.connect(30, 2000)) } finally { c.close() }
      }
    }
  }

  @Test fun publishSubscribePingAndDisconnectAreFramedCorrectly() {
    Broker(connack).use { broker ->
      val c = client(broker.port)
      assertTrue(c.connect(30, 2000))
      val payload = ByteArray(200) { 'x'.code.toByte() }
      c.publish("a/b", payload, retain = true)
      c.publish("é", "hi", retain = false)
      c.subscribe("cmd/+/set")
      c.subscribe("other")
      c.ping()
      c.disconnect()
      c.close()
      val sent = broker.received.get(3, TimeUnit.SECONDS).second
      var at = 0
      // Retained PUBLISH with a two-byte remaining length (2 + 3 + 200 = 205).
      assertEquals(0x31, sent[at].toInt() and 0xff)
      assertEquals(205 % 128 or 0x80, sent[at + 1].toInt() and 0xff)
      assertEquals(205 / 128, sent[at + 2].toInt())
      assertEquals("a/b", str(sent, at + 3).first)
      at += 3 + 205
      assertEquals(0x30, sent[at].toInt() and 0xff)
      assertEquals(2 + 2 + 2, sent[at + 1].toInt())
      assertEquals("é", str(sent, at + 2).first)
      assertEquals("hi", String(sent, at + 6, 2, Charsets.UTF_8))
      at += 2 + 6
      for ((expectedId, topic) in listOf(1 to "cmd/+/set", 2 to "other")) {
        assertEquals(0x82, sent[at].toInt() and 0xff)
        val len = sent[at + 1].toInt()
        assertEquals(2 + 2 + topic.length + 1, len)
        assertEquals(expectedId, u16(sent, at + 2))
        assertEquals(topic, str(sent, at + 4).first)
        assertEquals(0, sent[at + 1 + len].toInt()) // requested QoS 0
        at += 2 + len
      }
      assertArrayEquals(byteArrayOf(0xC0.toByte(), 0, 0xE0.toByte(), 0), sent.copyOfRange(at, sent.size))
    }
  }

  @Test fun readPacketDecodesMultiByteLengthAndFlags() {
    val body = ByteArray(300) { it.toByte() }
    val frame = byteArrayOf(0x3B, (300 % 128 or 0x80).toByte(), (300 / 128).toByte()) + body
    Broker(connack + frame, closeAfterReply = true).use { broker ->
      val c = client(broker.port)
      assertTrue(c.connect(30, 2000))
      val pkt = c.readPacket()!!
      assertEquals(0x30, pkt.type)
      assertEquals(0x0B, pkt.flags)
      assertArrayEquals(body, pkt.body)
      assertNull("clean EOF after the last packet", c.readPacket())
      c.close()
    }
  }

  @Test fun truncatedBodyThrowsAndEofInsideLengthIsTreatedAsClose() {
    Broker(connack + byteArrayOf(0x30, 10, 0, 1, 't'.code.toByte()), closeAfterReply = true).use { broker ->
      val c = client(broker.port)
      assertTrue(c.connect(30, 2000))
      assertTrue(runCatching { c.readPacket() }.exceptionOrNull() is EOFException)
      c.close()
    }
    Broker(connack + byteArrayOf(0x30, 0x80.toByte()), closeAfterReply = true).use { broker ->
      val c = client(broker.port)
      assertTrue(c.connect(30, 2000))
      assertNull(c.readPacket())
      c.close()
    }
  }

  @Test fun overlongRemainingLengthIsRejectedWithoutAllocating() {
    val five = byteArrayOf(0x30, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
    Broker(connack + five, closeAfterReply = true).use { broker ->
      val c = client(broker.port)
      assertTrue(c.connect(30, 2000))
      assertNull(c.readPacket())
      c.close()
    }
  }

  @Test fun unconnectedClientReadsNothingAndWritesAreNoOps() {
    val c = client(1)
    assertNull(c.readPacket())
    c.publish("t", "p", retain = false)
    c.subscribe("t")
    c.ping()
    c.disconnect()
    c.close()
  }

  private fun publish(flags: Int, vararg body: Int) = MqttClient.Packet(0x30, flags, ByteArray(body.size) { body[it].toByte() })

  @Test fun parsePublishSplitsTopicAndPayloadAndSkipsPacketIdAboveQos0() {
    val c = client(1)
    val qos0 = c.parsePublish(publish(0x01, 0, 3, 'a'.code, '/'.code, 'b'.code, 'O'.code, 'N'.code))
    assertEquals("a/b", qos0.first)
    assertEquals("ON", String(qos0.second))
    val qos1 = c.parsePublish(publish(0x02, 0, 1, 't'.code, 0x12, 0x34, 'x'.code))
    assertEquals("t", qos1.first)
    assertEquals("x", String(qos1.second))
    val qos2 = c.parsePublish(publish(0x04, 0, 1, 't'.code, 0, 1))
    assertEquals(0, qos2.second.size)
    val utf8 = "é".toByteArray()
    val unicode = c.parsePublish(MqttClient.Packet(0x30, 0, byteArrayOf(0, utf8.size.toByte()) + utf8))
    assertEquals("é", unicode.first)
    assertEquals(0, unicode.second.size)
  }

  @Test fun parsePublishRefusesTruncatedOrLyingTopicLength() {
    val c = client(1)
    for (packet in listOf(publish(0), publish(0, 0), publish(0, 0, 9, 'a'.code), publish(0x02, 0, 1, 't'.code, 0x12)))
      assertTrue(packet.body.contentToString(), runCatching { c.parsePublish(packet) }.isFailure)
  }
}
