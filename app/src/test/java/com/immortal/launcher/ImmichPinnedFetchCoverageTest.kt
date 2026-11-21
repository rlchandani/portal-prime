/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mockStatic

/** Failure paths of the pinned Immich album fetch: broken redirects, budgets, interrupts, the null-on-failure wrapper. */
class ImmichPinnedFetchCoverageTest {
  private val loopback = InetAddress.getByAddress("immich.home.test", byteArrayOf(127, 0, 0, 1))
  private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
  private val port = server.localPort
  private val base = "http://immich.home.test:$port"
  private val requests = CopyOnWriteArrayList<String>()

  @After fun close() = server.close()

  private fun pinned() = LanUrlPolicy.Target.Pinned("immich.home.test", listOf(loopback))

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

  private fun redirect(location: String?) =
      "HTTP/1.1 302 Found\r\n" + (location?.let { "Location: $it\r\n" } ?: "") +
          "Content-Length: 0\r\nConnection: close\r\n\r\n"

  private fun ok(body: String) =
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

  private fun failure(block: () -> Unit): IOException {
    try {
      block()
    } catch (e: IOException) {
      return e
    }
    fail("expected an IOException")
    throw AssertionError()
  }

  private fun fetch(maxBytes: Int = 4096) = ImmichPinnedFetch.fetchAlbums(base, "k", pinned(), maxBytes, 5_000)

  @Test fun aRedirectWithoutLocationIsRefused() {
    serve(redirect(null))
    assertEquals("Immich redirect has no destination", failure { fetch() }.message)
    assertEquals(1, requests.size)
  }

  @Test fun aRedirectWithABlankLocationIsRefused() {
    serve(redirect("   "))
    assertEquals("Immich redirect has no destination", failure { fetch() }.message)
  }

  @Test fun aRedirectLoopStopsAtTheLimit() {
    serve(*Array(6) { redirect("/hop$it") })
    assertEquals("Immich redirect limit exceeded", failure { fetch() }.message)
    assertEquals(6, requests.size)
  }

  @Test fun fiveRedirectsAreStillFollowed() {
    serve(*(Array(5) { redirect("/hop$it") } + ok("[]")))
    assertEquals(emptyList<ImmichSource.Album>(), fetch())
    assertEquals(6, requests.size)
  }

  @Test fun aDeclaredLengthOverTheBudgetIsRefusedBeforeReading() {
    serve("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\nConnection: close\r\n\r\n[]")
    assertEquals("Immich reply exceeds its budget", failure { fetch(maxBytes = 1024) }.message)
  }

  @Test fun anInterruptedThreadNeverConnects() {
    Thread.currentThread().interrupt()
    try {
      val e = failure { fetch() }
      assertTrue(e is InterruptedIOException)
    } finally {
      Thread.interrupted()
    }
    assertTrue(requests.isEmpty())
  }

  @Test fun listAlbumsReturnsTheAlbumsOnSuccess() {
    serve(ok("""[{"id":"a1","albumName":"Trip","assetCount":3}]"""))
    assertEquals(listOf(ImmichSource.Album("a1", "Trip", 3)),
        ImmichPinnedFetch.listAlbums(base, "k", pinned(), 4096, 5_000))
  }

  @Test fun listAlbumsIsNullAndLogsWithoutTheUrlOnFailure() {
    serve("HTTP/1.1 500 Oops\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    mockStatic(Log::class.java).use { log ->
      assertNull(ImmichPinnedFetch.listAlbums(base, "secret-key", pinned(), 4096, 5_000))
      log.verify { Log.w(org.mockito.ArgumentMatchers.eq("ImmortalImmich"),
          org.mockito.ArgumentMatchers.eq("pinned album list failed: IOException")) }
    }
  }
}
