/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTTP request parsing + the LAN-only peer guard (no sockets needed). */
class FleetHttpServerTest {

  @Test
  fun parseHead_splitsRequestLineQueryAndHeaders() {
    val raw = "GET /info?x=1&y=2 HTTP/1.1\r\nHost: portal\r\nAuthorization: Bearer tok123\r\n\r\n"
    val h = FleetHttpServer.parseHead(raw)!!
    assertEquals("GET", h.method)
    assertEquals("/info", h.path)
    assertEquals("x=1&y=2", h.query)
    assertEquals("Bearer tok123", h.headers["authorization"]) // keys lower-cased
    assertEquals("portal", h.headers["host"])
  }

  @Test
  fun parseHead_noQuery_andLowercasesKeys() {
    val h = FleetHttpServer.parseHead("POST /install HTTP/1.1\r\nContent-Length: 18\r\n\r\n")!!
    assertEquals("POST", h.method)
    assertEquals("/install", h.path)
    assertEquals("", h.query)
    assertEquals("18", h.headers["content-length"])
  }

  @Test
  fun parseHead_rejectsMalformedRequestLine() {
    assertNull(FleetHttpServer.parseHead("garbage\r\n\r\n"))
  }

  @Test
  fun isLanAddress_acceptsPrivateAndLoopback_rejectsPublicAndNull() {
    assertTrue(FleetHttpServer.isLanAddress(InetAddress.getByName("127.0.0.1")))
    assertTrue(FleetHttpServer.isLanAddress(InetAddress.getByName("192.168.1.5")))
    assertTrue(FleetHttpServer.isLanAddress(InetAddress.getByName("10.0.0.4")))
    assertTrue(FleetHttpServer.isLanAddress(InetAddress.getByName("172.16.3.9")))
    assertTrue(FleetHttpServer.isLanAddress(InetAddress.getByName("169.254.1.1"))) // link-local
    assertFalse(FleetHttpServer.isLanAddress(InetAddress.getByName("8.8.8.8")))
    assertFalse(FleetHttpServer.isLanAddress(null))
  }
}
