/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: cutting the video prefetch ([PhotoFrameTransport.cancelVideoPrefetch]) only asks for
 * a disconnect. Whether the blocked read then fails or ends in a clean EOF depends on the
 * URLConnection implementation (the JDK's ends cleanly once the server closes). The copy loop took
 * that EOF as "the whole file arrived" and returned true with a truncated source, which the
 * prefetch would transcode and could commit if the host came back before the transcode ran.
 * Here the server ends the body as soon as the cut is requested, so the EOF is deterministic.
 */
class PhotoFrameTransportCutPrefetchTest {
  private val release = CountDownLatch(1)
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/stall") { exchange ->
      try {
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write("partial".toByteArray())
        exchange.responseBody.flush()
        release.await(10, TimeUnit.SECONDS)
      } catch (_: Exception) {
      } finally {
        runCatching { exchange.close() }
      }
    }
    start()
  }
  private val transport = PhotoFrameTransport { false }
  private val dst = File.createTempFile("prefetch", ".src").apply { deleteOnExit() }

  @After fun tearDown() {
    release.countDown()
    server.stop(0)
    transport.shutdown()
    dst.delete()
  }

  @Test fun aCutPrefetchIsNotReportedAsAWholeFile() {
    var cut = false
    val ok = transport.downloadToFile("http://127.0.0.1:${server.address.port}/stall", emptyMap(), dst) {
      if (dst.length() > 0 && !cut) {
        cut = true
        transport.cancelVideoPrefetch()
        release.countDown() // the stream now ends cleanly, as it can after a disconnect
      }
      true
    }
    assertTrue("the download was cut part-way", cut)
    assertFalse("a cut download must not count as complete", ok)
  }
}
