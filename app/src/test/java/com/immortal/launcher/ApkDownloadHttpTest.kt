/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloadHttpTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun knownAndUnknownNonemptyBodiesPublishExactlyAcrossBufferBoundaries() {
    for (size in listOf(1, 8192, 8193, 65537)) {
      for (declared in listOf(-1L, size.toLong())) {
        val destination = oldDestination()
        val body = ByteArray(size) { (it % 251).toByte() }
        val input = TrackedInput(ByteArrayInputStream(body))
        val connection = Connection(declared, input)
        input.onClose = { assertEquals("old", destination.readText()) }

        assertEquals(size.toLong(), ApkDownloadHttp.download(connection, destination))

        assertArrayEquals(body, destination.readBytes())
        assertTrue(input.closed)
        assertEquals(1, connection.disconnects)
        assertEntries("cached.apk")
      }
    }
  }

  @Test
  fun emptyTruncatedAndOversizedBodiesPreserveCacheAndUnrelatedScratch() {
    val unrelated = temporary.newFile(".immortal-upload-unrelated.part")
    unrelated.writeText("another worker")
    for ((declared, body) in listOf(
      0L to byteArrayOf(),
      -1L to byteArrayOf(),
      1L to byteArrayOf(),
      124L to ByteArray(123),
      2L to ByteArray(3),
      (Int.MAX_VALUE.toLong() + 1L) to byteArrayOf(1),
      Long.MAX_VALUE to byteArrayOf(1),
    )) {
      val destination = oldDestination()
      val input = TrackedInput(ByteArrayInputStream(body))
      val connection = Connection(declared, input)

      assertThrows(IOException::class.java) { ApkDownloadHttp.download(connection, destination) }

      assertEquals("old", destination.readText())
      assertEquals("another worker", unrelated.readText())
      assertEquals(1, connection.disconnects)
      if (connection.acquisitions > 0) assertTrue(input.closed)
      assertEntries("cached.apk", unrelated.name)
    }
  }

  @Test
  fun failedNewDownloadLeavesNoDestinationOrStaging() {
    val destination = File(temporary.root, "new.apk")
    val connection = Connection(10L, TrackedInput(ByteArrayInputStream(byteArrayOf(1))))

    assertThrows(IOException::class.java) { ApkDownloadHttp.download(connection, destination) }

    assertFalse(destination.exists())
    assertEquals(1, connection.disconnects)
    assertEntries()
  }

  @Test
  fun generatedBodyStreamsToDiskWithBoundedMemory() {
    val destination = oldDestination()
    val length = 8L * 1024L * 1024L + 3L
    var remaining = length
    var reads = 0
    val generated = object : InputStream() {
      override fun read(): Int = throw AssertionError("Bulk reads expected")
      override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        assertTrue("Read buffer must stay bounded", buffer.size <= 64 * 1024)
        if (remaining == 0L) return -1
        if (reads++ > 0) {
          val staging = temporary.root.listFiles()!!.single { it.name.endsWith(".part") }
          assertEquals("Each block must already be on disk", length - remaining, staging.length())
          assertEquals("old", destination.readText())
        }
        val copied = minOf(count.toLong(), remaining).toInt()
        java.util.Arrays.fill(buffer, offset, offset + copied, 37.toByte())
        remaining -= copied
        return copied
      }
    }
    val input = TrackedInput(generated)
    val connection = Connection(length, input)

    assertEquals(length, ApkDownloadHttp.download(connection, destination))

    assertEquals(length, destination.length())
    assertTrue(reads > 1)
    destination.inputStream().use { stream ->
      val block = ByteArray(4096)
      var verified = 0L
      while (true) {
        val count = stream.read(block)
        if (count < 0) break
        for (index in 0 until count) assertEquals(37, block[index].toInt())
        verified += count
      }
      assertEquals(length, verified)
    }
    assertTrue(input.closed)
    assertEquals(1, connection.disconnects)
    assertEntries("cached.apk")
  }

  @Test
  fun oversizedGeneratedBodyStopsBeforeWritingUnexpectedChunk() {
    val destination = oldDestination()
    val length = 8L * 1024L * 1024L
    var consumed = 0L
    var stagedBytesOnClose = -1L
    val input = TrackedInput(object : InputStream() {
      override fun read(): Int = throw AssertionError("Bulk reads expected")
      override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (consumed == length) return -1
        val copied = minOf(count.toLong(), length - consumed).toInt()
        java.util.Arrays.fill(buffer, offset, offset + copied, 7.toByte())
        consumed += copied
        return copied
      }
    })
    input.onClose = {
      stagedBytesOnClose = temporary.root.listFiles()!!
        .single { it.name.endsWith(".part") }.length()
      assertEquals("old", destination.readText())
    }
    val connection = Connection(2, input)

    assertThrows(IOException::class.java) { ApkDownloadHttp.download(connection, destination) }

    assertTrue("The unexpected body must not be consumed to EOF", consumed < length)
    assertEquals("The unexpected chunk must not be written", 0L, stagedBytesOnClose)
    assertEquals("old", destination.readText())
    assertTrue(input.closed)
    assertEquals(1, connection.disconnects)
    assertEntries("cached.apk")
  }

  @Test
  fun zeroLengthBulkReadsStillMakeProgress() {
    var position = 0
    val input = TrackedInput(object : InputStream() {
      override fun read(buffer: ByteArray, offset: Int, count: Int): Int = 0
      override fun read(): Int = if (position < 3) ++position else -1
    })
    val connection = Connection(3, input)
    val destination = oldDestination()

    assertEquals(3L, ApkDownloadHttp.download(connection, destination))

    assertArrayEquals(byteArrayOf(1, 2, 3), destination.readBytes())
    assertTrue(input.closed)
    assertEquals(1, connection.disconnects)
  }

  @Test
  fun headerAndInputAcquisitionFailuresDisconnectWithoutPublishing() {
    for (headerFailure in listOf(true, false)) {
      val destination = oldDestination()
      val failure = IOException("connection failed")
      val connection = Connection(3, TrackedInput(ByteArrayInputStream(byteArrayOf(1))))
      if (headerFailure) connection.headerFailure = failure else connection.inputFailure = failure

      assertSame(failure, assertThrows(IOException::class.java) {
        ApkDownloadHttp.download(connection, destination)
      })

      assertEquals("old", destination.readText())
      assertEquals(1, connection.disconnects)
      assertEntries("cached.apk")
    }
  }

  @Test
  fun readAndCloseFailuresPreserveCacheAndCloseBeforeDisconnect() {
    for (failOnClose in listOf(false, true)) {
      val destination = oldDestination()
      val failure = IOException("stream failed")
      var reads = 0
      val source = object : InputStream() {
        override fun read(): Int {
          if (!failOnClose && reads++ > 0) throw failure
          return if (failOnClose && reads++ > 0) -1 else 7
        }
      }
      val input = TrackedInput(source)
      if (failOnClose) input.onClose = { throw failure }
      val connection = Connection(-1, input)
      connection.onDisconnect = { assertTrue(input.closed) }

      assertSame(failure, assertThrows(IOException::class.java) {
        ApkDownloadHttp.download(connection, destination)
      })

      assertEquals("old", destination.readText())
      assertEquals(1, connection.disconnects)
      assertEntries("cached.apk")
    }
  }

  @Test
  fun interruptionAtStartHeadersAcquisitionReadEofAndClosePreservesFlagAndCache() {
    for (phase in listOf("start", "header", "acquire", "read", "eof", "close")) {
      val destination = oldDestination()
      var read = false
      val input = TrackedInput(object : InputStream() {
        override fun read(): Int {
          if (phase == "read" || (phase == "eof" && read)) Thread.currentThread().interrupt()
          return if (read) -1 else { read = true; 7 }
        }
      })
      val connection = Connection(1, input)
      if (phase == "header") connection.onHeader = { Thread.currentThread().interrupt() }
      if (phase == "acquire") connection.onAcquire = { Thread.currentThread().interrupt() }
      if (phase == "close") input.onClose = { Thread.currentThread().interrupt() }
      try {
        if (phase == "start") Thread.currentThread().interrupt()

        assertThrows(InterruptedIOException::class.java) {
          ApkDownloadHttp.download(connection, destination)
        }

        assertTrue("Interrupt flag at $phase must survive", Thread.currentThread().isInterrupted)
        assertEquals("old", destination.readText())
        assertEquals(1, connection.disconnects)
        if (connection.acquisitions > 0) assertTrue(input.closed)
        if (phase == "start") assertEquals(0, connection.headers)
        assertEntries("cached.apk")
      } finally {
        Thread.interrupted()
      }
    }
  }

  @Test
  fun callerHeadersTimeoutsAndRedirectPolicyAreRetained() {
    val connection = Connection(1, TrackedInput(ByteArrayInputStream(byteArrayOf(9))))
    connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
    connection.connectTimeout = 1234
    connection.readTimeout = 5678
    connection.instanceFollowRedirects = false

    ApkDownloadHttp.download(connection, oldDestination())

    assertEquals("application/vnd.android.package-archive", connection.getRequestProperty("Accept"))
    assertEquals(1234, connection.connectTimeout)
    assertEquals(5678, connection.readTimeout)
    assertFalse(connection.instanceFollowRedirects)
  }

  @Test
  fun renameFailurePreservesDirectoryAndClosesInput() {
    val destination = temporary.newFolder("occupied")
    File(destination, "keep").writeText("keep")
    val input = TrackedInput(ByteArrayInputStream(byteArrayOf(1)))
    val connection = Connection(1, input)

    assertThrows(IOException::class.java) { ApkDownloadHttp.download(connection, destination) }

    assertEquals("keep", File(destination, "keep").readText())
    assertTrue(input.closed)
    assertEquals(1, connection.disconnects)
    assertEntries("occupied")
  }

  @Test
  fun symlinkDestinationRetainsLinkAndUpdatesCanonicalTarget() {
    val target = temporary.newFile("target.apk").apply { writeText("old") }
    val link = File(temporary.root, "link.apk")
    Files.createSymbolicLink(link.toPath(), target.toPath())
    val connection = Connection(3, TrackedInput(ByteArrayInputStream(byteArrayOf(1, 2, 3))))

    ApkDownloadHttp.download(connection, link)

    assertTrue(Files.isSymbolicLink(link.toPath()))
    assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    assertEntries("target.apk", "link.apk")
  }

  @Test
  fun concurrentSameTargetDownloadsOwnDistinctStagingAndPublishCompleteBodies() {
    val destination = oldDestination()
    val ready = CountDownLatch(2)
    val release = List(2) { CountDownLatch(1) }
    val connections = (0..1).map { worker ->
      var position = 0
      Connection(2, TrackedInput(object : InputStream() {
        override fun read(): Int {
          if (position == 1) {
            ready.countDown()
            assertTrue(release[worker].await(5, TimeUnit.SECONDS))
          }
          return if (position++ < 2) worker + 1 else -1
        }
        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
          val next = read()
          if (next < 0) return -1
          buffer[offset] = next.toByte()
          return 1
        }
      }))
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val futures = connections.map { connection ->
        executor.submit<Long> { ApkDownloadHttp.download(connection, destination) }
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      assertEquals("old", destination.readText())
      val staging = temporary.root.listFiles()!!.filter { it.name.endsWith(".part") }
      assertEquals(2, staging.map { it.name }.toSet().size)
      assertEquals(setOf(1, 2), staging.map { it.readBytes().single().toInt() }.toSet())

      release[0].countDown()
      assertEquals(2L, futures[0].get(5, TimeUnit.SECONDS).toLong())
      assertArrayEquals(byteArrayOf(1, 1), destination.readBytes())
      assertEquals(1, temporary.root.listFiles()!!.count { it.name.endsWith(".part") })

      release[1].countDown()
      assertEquals(2L, futures[1].get(5, TimeUnit.SECONDS).toLong())
      assertArrayEquals(byteArrayOf(2, 2), destination.readBytes())
      assertTrue(connections.all { it.disconnects == 1 && it.source.closed })
      assertEntries("cached.apk")
    } finally {
      release.forEach { it.countDown() }
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private fun oldDestination(): File =
    File(temporary.root, "cached.apk").apply { writeText("old") }

  private fun assertEntries(vararg expected: String) {
    assertEquals(expected.toSet(), temporary.root.list()!!.toSet())
  }

  private class TrackedInput(private val delegate: InputStream) : InputStream() {
    var closed = false
    var onClose: () -> Unit = {}
    override fun read(): Int = delegate.read()
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
      delegate.read(buffer, offset, count)
    override fun close() {
      closed = true
      delegate.close()
      onClose()
    }
  }

  private class Connection(private val length: Long, val source: TrackedInput) :
    HttpURLConnection(URI("http://example.invalid/app.apk").toURL()) {
    var disconnects = 0
    var headers = 0
    var acquisitions = 0
    var headerFailure: IOException? = null
    var inputFailure: IOException? = null
    var onHeader: () -> Unit = {}
    var onAcquire: () -> Unit = {}
    var onDisconnect: () -> Unit = {}
    override fun getContentLengthLong(): Long {
      headers++
      onHeader()
      headerFailure?.let { throw it }
      return length
    }
    override fun getInputStream(): InputStream {
      acquisitions++
      onAcquire()
      inputFailure?.let { throw it }
      return source
    }
    override fun disconnect() {
      disconnects++
      onDisconnect()
    }
    override fun connect() = Unit
    override fun usingProxy(): Boolean = false
  }
}
