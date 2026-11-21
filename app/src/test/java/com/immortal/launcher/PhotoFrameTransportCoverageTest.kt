/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The photo frame's HTTP transports against a loopback server: generations drop late answers,
 * cancelling closes what is open, and the video prefetch stops when asked.
 */
class PhotoFrameTransportCoverageTest {
  private val stopped = AtomicBoolean(false)
  private val transport = PhotoFrameTransport { stopped.get() }
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val agents = CopyOnWriteArrayList<String?>()
  private val base = "http://127.0.0.1:${server.address.port}"
  private val tmp = File.createTempFile("transport", ".bin").apply { deleteOnExit() }

  /** What a path answers: status and body; `onRequest` runs before replying. */
  private var onRequest: (String) -> Unit = {}

  init {
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
  }

  @After fun tearDown() {
    server.stop(0)
    transport.shutdown()
    tmp.delete()
  }

  private fun handle(exchange: HttpExchange) {
    try {
      agents += exchange.requestHeaders.getFirst("User-Agent")
      val path = exchange.requestURI.path
      onRequest(path)
      when (path) {
        "/missing" -> exchange.sendResponseHeaders(404, -1)
        "/empty" -> exchange.sendResponseHeaders(200, -1)
        "/huge" -> {
          exchange.sendResponseHeaders(200, 0)
          exchange.responseBody.use { out ->
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            repeat(33) { out.write(chunk) } // just over 2 MiB
          }
        }
        else -> {
          val body = "body of $path".toByteArray()
          exchange.sendResponseHeaders(200, body.size.toLong())
          exchange.responseBody.use { it.write(body) }
        }
      }
    } catch (_: IOException) {
    } finally {
      exchange.close()
    }
  }

  private fun drainCloses() = transport.cancelIo.submit {}.get(5, TimeUnit.SECONDS)

  private class TrackedStream : InputStream() {
    @Volatile var closed = false
    override fun read(): Int = -1
    override fun close() { closed = true }
  }

  private inline fun <reified E : Throwable> expect(block: () -> Unit): E {
    try {
      block()
    } catch (e: Throwable) {
      if (e is E) return e
      throw e
    }
    fail("expected ${E::class.java.simpleName}")
    throw AssertionError()
  }

  @Test fun currentFollowsTheGenerationStopFlagAndInterrupts() {
    val g = transport.generation.get()
    assertTrue(transport.current(g))
    assertFalse(transport.current(g + 1))
    Thread.currentThread().interrupt()
    try {
      assertFalse(transport.current(g))
    } finally {
      Thread.interrupted()
    }
    stopped.set(true)
    assertFalse(transport.current(g))
  }

  @Test fun registerRefusesSupersededStreamsAndCancelClosesTrackedOnes() {
    val tracked = TrackedStream()
    val removed = TrackedStream()
    val refused = TrackedStream()
    assertTrue(transport.register(tracked) { true })
    assertTrue(transport.register(removed) { true })
    assertFalse(transport.register(refused) { false })
    transport.unregister(removed)
    val before = transport.generation.get()
    transport.cancelAll()
    drainCloses()
    assertEquals(before + 1, transport.generation.get())
    assertTrue(tracked.closed)
    assertFalse(removed.closed)
    assertFalse(refused.closed)
  }

  @Test fun cancelWithNothingOpenStillInvalidatesTheGeneration() {
    val g = transport.generation.get()
    transport.cancelAll()
    transport.cancelAll()
    assertEquals(g + 2, transport.generation.get())
    assertFalse(transport.current(g))
  }

  @Test fun cancelVideoPrefetchWithoutOneIsANoOp() {
    transport.cancelVideoPrefetch()
    drainCloses()
  }

  @Test fun httpGetReadsTheBodyWithTheFrameUserAgent() {
    assertEquals("body of /manifest", transport.httpGet("$base/manifest", transport.generation.get()))
    assertEquals(PhotoFrameController.USER_AGENT, agents.single())
  }

  @Test fun aStaleGenerationNeverConnects() {
    val stale = transport.generation.get()
    transport.cancelAll()
    expect<InterruptedIOException> { transport.httpGet("$base/manifest", stale) }
    assertTrue(agents.isEmpty())
  }

  @Test fun aRequestCancelledWhileTheServerAnswersIsDropped() {
    val g = transport.generation.get()
    onRequest = { transport.cancelAll() }
    // The generation check or the asynchronous disconnect ends it (the JDK can surface the latter as a
    // RuntimeException; every caller wraps these in runCatching). Either way no body comes back.
    expect<Exception> { transport.httpGet("$base/manifest", g) }
  }

  @Test fun anOversizedManifestIsRefused() {
    val e = expect<IOException> { transport.httpGet("$base/huge", transport.generation.get()) }
    assertEquals("Photo manifest unavailable", e.message)
  }

  @Test fun anHttpErrorPropagatesAsIOException() {
    expect<IOException> { transport.httpGet("$base/missing", transport.generation.get()) }
  }

  @Test fun anInvalidUrlIsRefused() {
    expect<IOException> { transport.httpGet("ftp://127.0.0.1/x", transport.generation.get()) }
    assertTrue(agents.isEmpty())
  }

  @Test fun downloadDecodesWhileCurrent() {
    val discarded = CopyOnWriteArrayList<String>()
    val out = transport.download("$base/photo", mapOf("x-api-key" to "k"), transport.generation.get(),
        decode = { it.readBytes().toString(Charsets.UTF_8) }, discard = { discarded += it })
    assertEquals("body of /photo", out)
    assertTrue(discarded.isEmpty())
  }

  @Test fun aPhotoDecodedAfterItsRequestWasSupersededIsDiscarded() {
    val discarded = CopyOnWriteArrayList<String>()
    val out = transport.download("$base/photo", emptyMap(), transport.generation.get(),
        decode = { input -> input.readBytes().toString(Charsets.UTF_8).also { transport.cancelAll() } },
        discard = { discarded += it })
    assertNull(out)
    assertEquals(listOf("body of /photo"), discarded)
  }

  @Test fun aFailedDecodeOfASupersededRequestDiscardsNothing() {
    var discards = 0
    val out = transport.download<String>("$base/photo", emptyMap(), transport.generation.get(),
        decode = { transport.cancelAll(); null }, discard = { discards++ })
    assertNull(out)
    assertEquals(0, discards)
  }

  @Test fun downloadBytesReturnsTheBodyOnlyWhileCurrent() {
    assertArrayEquals("body of /b".toByteArray(),
        transport.downloadBytes("$base/b", emptyMap(), transport.generation.get()))
    val g = transport.generation.get()
    onRequest = { transport.cancelAll() }
    expect<Exception> { transport.downloadBytes("$base/b", emptyMap(), g) }
  }

  @Test fun downloadToFileWritesTheWholeBody() {
    assertTrue(transport.downloadToFile("$base/video", emptyMap(), tmp) { true })
    assertEquals("body of /video", tmp.readText())
    assertEquals(PhotoFrameController.USER_AGENT, agents.single())
  }

  @Test fun downloadToFileIsFalseForAnEmptyBody() {
    assertFalse(transport.downloadToFile("$base/empty", emptyMap(), tmp) { true })
    assertEquals(0L, tmp.length())
  }

  @Test fun downloadToFileNeverStartsWhenNotWanted() {
    assertFalse(transport.downloadToFile("$base/video", emptyMap(), tmp) { false })
    assertTrue(agents.isEmpty())
  }

  @Test fun downloadToFileStopsWhenItIsNoLongerWantedAfterConnecting() {
    var calls = 0
    // Wanted for the start check and the connect, not for the copy loop.
    assertFalse(transport.downloadToFile("$base/video", emptyMap(), tmp) { ++calls <= 2 })
    assertEquals(1, agents.size)
  }

  @Test fun downloadToFileIsFalseOnErrorsAndBadUrls() {
    assertFalse(transport.downloadToFile("$base/missing", emptyMap(), tmp) { true })
    assertFalse(transport.downloadToFile("not a url", emptyMap(), tmp) { true })
  }

  @Test fun shutdownStopsTheCloseExecutor() {
    transport.shutdown()
    assertTrue(transport.cancelIo.awaitTermination(5, TimeUnit.SECONDS))
  }

  @Test fun unregisteringAnUnknownStreamIsHarmless() {
    transport.unregister(ByteArrayInputStream(ByteArray(0)))
    transport.cancelAll()
    drainCloses()
  }
}
