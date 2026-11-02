/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real loopback HTTP exercises CalendarFeed's production download entry. */
class CalendarDownloadRegressionTest {
  private val small = "BEGIN:VCALENDAR\r\nX-NAME:Café 家\r\nEND:VCALENDAR\r\n".toByteArray(Charsets.UTF_8)
  private val limit = 8 * 1024 * 1024

  @Test fun advertisedOversizedFeedIsNotReturned() {
    val body = ByteArray(limit + 1) { ' '.code.toByte() }
    assertNull(download(body, body.size))
  }

  @Test fun unannouncedOversizedFeedIsNotReturned() {
    val body = ByteArray(limit + 1) { ' '.code.toByte() }
    assertNull(download(body, null))
  }

  @Test fun completeCalendarPrefixCannotHideTruncatedAdvertisedBody() {
    assertNull(download(small, small.size + 100))
  }

  @Test fun completeUtf8FeedRemainsExact() {
    assertEquals(small.toString(Charsets.UTF_8), download(small, small.size))
  }

  private fun download(body: ByteArray, length: Int?): String? {
    val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    listener.soTimeout = 3_000
    val done = CountDownLatch(1)
    val worker = Thread {
      try {
        listener.accept().use { socket ->
          socket.soTimeout = 3_000
          val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
          var count = 0
          while (true) {
            val line = reader.readLine() ?: break
            check(++count <= 100) { "Unexpected fixture request." }
            if (line.isEmpty()) break
          }
          val header = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/calendar\r\n" +
              (length?.let { "Content-Length: $it\r\n" } ?: "") + "\r\n"
          val output = socket.getOutputStream()
          output.write(header.toByteArray(Charsets.ISO_8859_1))
          output.write(body)
          output.flush()
        }
      } catch (_: IOException) {
        // A correctly rejected oversized response closes its body early.
      } finally { done.countDown() }
    }.apply { isDaemon = true; start() }
    try {
      val method = CalendarFeed::class.java.getDeclaredMethod("httpGet", String::class.java)
      method.isAccessible = true
      return method.invoke(CalendarFeed, "http://127.0.0.1:${listener.localPort}/calendar.ics") as? String
    } finally {
      listener.close()
      assertTrue("Owned fixture did not close.", done.await(5, TimeUnit.SECONDS))
      worker.join(500)
      assertFalse(worker.isAlive)
    }
  }
}
