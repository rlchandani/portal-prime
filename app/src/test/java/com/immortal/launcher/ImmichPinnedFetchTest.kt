/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Audit residual 5 (DNS rebinding): a phone-typed Immich host is resolved once, checked, and then
 * reached only at that answer. The servers run on loopback, which the policy itself refuses, so
 * these tests pin loopback directly; the host names are `.test` names no real resolver answers,
 * so any second lookup would fail the fetch.
 */
class ImmichPinnedFetchTest {
  private val loopback = InetAddress.getByAddress("immich.home.test", byteArrayOf(127, 0, 0, 1))
  private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
  private val port = server.localPort
  private val requests = CopyOnWriteArrayList<String>()

  @After fun close() = server.close()

  private fun pinned(host: String = "immich.home.test") = LanUrlPolicy.Target.Pinned(host, listOf(loopback))

  /** Serves [replies] in order, recording each request head. */
  private fun serve(vararg replies: String) = Thread {
    for (reply in replies) {
      runCatching {
        server.accept().use { s ->
          requests += readHead(s)
          s.getOutputStream().write(reply.toByteArray())
          s.getOutputStream().flush()
        }
      }
    }
  }.apply { isDaemon = true; start() }

  private fun readHead(s: Socket): String {
    val input = s.getInputStream()
    val out = ByteArrayOutputStream()
    while (!out.toString().endsWith("\r\n\r\n")) {
      val b = input.read()
      if (b < 0) break
      out.write(b)
    }
    return out.toString()
  }

  private fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

  @Test fun theCheckedHostIsReachedAtThePinnedAddressWithItsOwnHostHeader() {
    serve(ok("""[{"id":"a1","albumName":"Trip","assetCount":3}]"""))
    val albums = ImmichPinnedFetch.fetchAlbums("http://immich.home.test:$port/api/", "k3y", pinned(), 4096, 5_000)
    assertEquals(listOf(ImmichSource.Album("a1", "Trip", 3)), albums)
    val head = requests.single()
    assertTrue(head, head.startsWith("GET /api/albums HTTP/1.1"))
    assertTrue(head, head.contains("Host: immich.home.test:$port", ignoreCase = true))
    assertTrue(head, head.contains("x-api-key: k3y", ignoreCase = true))
  }

  @Test fun thePinnedDnsNeverAnswersAnotherName() {
    val dns = ImmichPinnedFetch.PinnedDns(pinned())
    assertEquals(listOf(loopback), dns.lookup("IMMICH.home.test"))
    try {
      dns.lookup("evil.test")
      fail("another name must not resolve")
    } catch (_: UnknownHostException) {}
  }

  @Test fun aUrlForAnotherHostIsRefusedBeforeConnecting() {
    try {
      ImmichPinnedFetch.fetchAlbums("http://other.home.test:$port", "k", pinned(), 4096, 5_000)
      fail("must refuse")
    } catch (_: IOException) {}
    assertTrue(requests.isEmpty())
  }

  @Test fun aSameOriginRedirectStaysOnThePinnedAddress() {
    serve("HTTP/1.1 302 Found\r\nLocation: /immich/api/albums\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", ok("[]"))
    assertEquals(emptyList<ImmichSource.Album>(),
        ImmichPinnedFetch.fetchAlbums("http://immich.home.test:$port", "k", pinned(), 4096, 5_000))
    assertEquals(2, requests.size)
    assertTrue(requests[1], requests[1].startsWith("GET /immich/api/albums "))
  }

  @Test fun aRedirectToAnotherHostIsRefused() {
    serve("HTTP/1.1 302 Found\r\nLocation: http://metadata.test:$port/api/albums\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    try {
      ImmichPinnedFetch.fetchAlbums("http://immich.home.test:$port", "k", pinned(), 4096, 5_000)
      fail("must refuse")
    } catch (_: IOException) {}
    assertEquals(1, requests.size)
  }

  @Test fun anOversizedReplyIsRefused() {
    val body = "[" + List(400) { """{"id":"a$it"}""" }.joinToString(",") + "]"
    serve("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n$body")
    try {
      ImmichPinnedFetch.fetchAlbums("http://immich.home.test:$port", "k", pinned(), 1024, 5_000)
      fail("must refuse")
    } catch (_: IOException) {}
  }

  @Test fun anErrorStatusIsAFailure() {
    serve("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    assertNull(runCatching { ImmichPinnedFetch.fetchAlbums("http://immich.home.test:$port", "k", pinned(), 4096, 5_000) }.getOrNull())
  }

  @Test fun httpsSendsTheTypedHostAsSniToThePinnedAddress() {
    val sni = arrayOfNulls<String>(1)
    val t = Thread {
      runCatching { server.accept().use { sni[0] = readClientHelloSni(DataInputStream(it.getInputStream())) } }
    }.apply { isDaemon = true; start() }
    // The handshake fails (no server certificate); what matters is where it went and its SNI.
    runCatching { ImmichPinnedFetch.fetchAlbums("https://immich.home.test:$port", "k", pinned(), 4096, 5_000) }
    t.join(5_000)
    assertEquals("immich.home.test", sni[0])
  }

  @Test fun theRouteResolvesOnceAndPinsThatAnswerEvenIfDnsChangesLater() {
    val calls = AtomicInteger()
    // A rebinding resolver: private on the check, public afterwards.
    val rebinding: (String) -> List<InetAddress> = {
      if (calls.getAndIncrement() == 0) listOf(InetAddress.getByAddress(it, byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
      else listOf(InetAddress.getByAddress(it, byteArrayOf(8, 8, 8, 8)))
    }
    val target = LanUrlPolicy.immichAlbumsTarget("http://rebind.home.test:2283", null, rebinding)
    assertEquals(1, calls.get())
    val pin = target as LanUrlPolicy.Target.Pinned
    assertEquals("rebind.home.test", pin.host)
    assertEquals(listOf("192.168.1.5"), pin.addresses.map { it.hostAddress })
    // The fetch's DNS gives that same checked answer and never asks the resolver again.
    assertEquals(pin.addresses, ImmichPinnedFetch.PinnedDns(pin).lookup("rebind.home.test"))
    assertEquals(1, calls.get())
  }

  @Test fun theSavedServerIsNotPinnedAndRefusalsStayRefused() {
    val dns: (String) -> List<InetAddress> = { listOf(InetAddress.getByAddress(it, byteArrayOf(8, 8, 8, 8))) }
    assertEquals(LanUrlPolicy.Target.Saved, LanUrlPolicy.immichAlbumsTarget("https://photos.example.com/api", "https://photos.example.com", dns))
    assertNull(LanUrlPolicy.immichAlbumsTarget("https://photos.example.com", null, dns))
    assertNull(LanUrlPolicy.immichAlbumsTarget("http://127.0.0.1:2283", null, dns))
  }

  /** The server_name from a TLS ClientHello (record, handshake, then the extensions). */
  private fun readClientHelloSni(input: DataInputStream): String? {
    if (input.readUnsignedByte() != 0x16) return null // handshake record
    input.skipBytes(2)
    val record = ByteArray(input.readUnsignedShort()).also(input::readFully)
    val d = DataInputStream(record.inputStream())
    if (d.readUnsignedByte() != 0x01) return null // ClientHello
    d.skipBytes(3 + 2 + 32) // length, version, random
    d.skipBytes(d.readUnsignedByte()) // session id
    d.skipBytes(d.readUnsignedShort()) // cipher suites
    d.skipBytes(d.readUnsignedByte()) // compression
    var left = d.readUnsignedShort()
    while (left > 0) {
      val type = d.readUnsignedShort()
      val len = d.readUnsignedShort()
      left -= 4 + len
      if (type != 0) { d.skipBytes(len); continue }
      d.skipBytes(2 + 1) // list length, name type (host_name)
      return String(ByteArray(d.readUnsignedShort()).also(d::readFully))
    }
    return null
  }
}
