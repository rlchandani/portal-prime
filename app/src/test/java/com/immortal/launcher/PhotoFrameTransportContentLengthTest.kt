/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: [PhotoFrameTransport.downloadToFile] took any clean EOF as a whole file. A server
 * that declares a Content-Length and then closes early (a crashed NAS, a proxy timeout) left a
 * truncated video that the prefetch would transcode and cache. A raw socket server makes the
 * truncation deterministic; HttpServer refuses to send a short fixed-length body.
 */
class PhotoFrameTransportContentLengthTest {
  private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
  private val transport = PhotoFrameTransport { false }
  private val dst = File.createTempFile("prefetch", ".src").apply { deleteOnExit() }

  @After fun tearDown() {
    runCatching { server.close() }
    transport.shutdown()
    dst.delete()
  }

  /** Answers one request with [headers] then [body], then closes the socket. */
  private fun serveOnce(headers: String, body: ByteArray) = thread(isDaemon = true) {
    runCatching {
      server.accept().use { socket ->
        val input = socket.getInputStream()
        // Drain the request head so the client is not reset mid-write.
        var tail = 0
        while (tail != 0x0d0a0d0a) {
          val b = input.read()
          if (b < 0) break
          tail = (tail shl 8) or b
        }
        val out = socket.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\n$headers\r\n").toByteArray())
        out.write(body)
        out.flush()
      }
    }
  }

  private fun url() = "http://127.0.0.1:${server.localPort}/video"

  @Test fun aBodyShorterThanItsDeclaredLengthIsAFailureAndLeavesNoPartialFile() {
    serveOnce("Content-Length: 4096\r\nConnection: close\r\n", ByteArray(100) { 7 })
    val ok = transport.downloadToFile(url(), emptyMap(), dst) { true }
    assertFalse("a truncated body must not count as complete", ok)
    assertFalse("the partial file is deleted", dst.exists())
  }

  @Test fun aBodyMatchingItsDeclaredLengthIsComplete() {
    val body = ByteArray(3000) { (it % 251).toByte() }
    serveOnce("Content-Length: ${body.size}\r\nConnection: close\r\n", body)
    assertTrue(transport.downloadToFile(url(), emptyMap(), dst) { true })
    assertArrayEquals(body, dst.readBytes())
  }

  @Test fun anUndeclaredLengthKeepsTheEofIsCompleteBehaviour() {
    val body = ByteArray(1500) { 3 }
    serveOnce("Connection: close\r\n", body)
    assertTrue(transport.downloadToFile(url(), emptyMap(), dst) { true })
    assertArrayEquals(body, dst.readBytes())
  }

  @Test fun anEmptyBodyDeclaringALengthIsAFailure() {
    serveOnce("Content-Length: 512\r\nConnection: close\r\n", ByteArray(0))
    assertFalse(transport.downloadToFile(url(), emptyMap(), dst) { true })
    assertFalse(dst.exists())
  }

  /** The engine's remote photos use OkHttp, which itself fails a fixed-length body cut short. */
  @Test fun theEngineRemoteDownloadAlsoRefusesATruncatedBody() {
    serveOnce("Content-Length: 4096\r\nConnection: close\r\n", ByteArray(100) { 7 })
    val owner = EnginePhotoTransports { it() }
    val ok = EngineRemoteHttp.download(url(), emptyMap(), "test", dst, owner) { true }
    owner.close()
    assertFalse("OkHttp must surface the early close as a failure", ok)
  }
}
