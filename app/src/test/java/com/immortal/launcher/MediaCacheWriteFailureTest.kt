/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class MediaCacheWriteFailureTest {
  @get:Rule val temporary = TemporaryFolder()
  private val url = "https://generated.invalid/fixture/photo"
  private val prior = byteArrayOf(11, 12, 13, 14)
  private val incoming = byteArrayOf(21, 22, 23, 24, 25, 26)

  private fun cache() = MediaCache(temporary.newFolder("cache"), Long.MAX_VALUE)
  private fun hidden(dir: File) = dir.listFiles().orEmpty().filter { it.name.startsWith(".") }.toSet()

  /** Only the stream boundary is doubled; publication, cleanup and files are production/real. */
  private fun fault(cache: MediaCache, write: Boolean, close: Boolean): File? {
    val dir = cache.imageFile(url).parentFile
    val stagedBytesWritten = AtomicBoolean()
    return Mockito.mockConstruction(FileOutputStream::class.java) { stream, context ->
      val staged = context.arguments().single() as File
      assertEquals("fixture stream must belong to its generated cache", dir, staged.parentFile)
      Mockito.doAnswer { invocation ->
        val bytes = invocation.arguments.single() as ByteArray
        Files.write(staged.toPath(), if (write) bytes.copyOf(2) else bytes)
        stagedBytesWritten.set(true)
        if (write) throw IOException("generated partial-write fault")
        null
      }.`when`(stream).write(ArgumentMatchers.any(ByteArray::class.java))
      if (close) Mockito.doThrow(IOException("generated close fault")).`when`(stream).close()
    }.use {
      cache.putImage(url, incoming).also {
        assertTrue("fixture must actually write staged bytes before the injected fault", stagedBytesWritten.get())
      }
    }
  }

  private fun failedReplacement(write: Boolean, close: Boolean) {
    val cache = cache()
    val target = cache.imageFile(url)
    Files.write(target.toPath(), prior)
    val foreign = File(target.parentFile, ".foreign-owner.tmp")
    Files.write(foreign.toPath(), byteArrayOf(71, 72))
    assertNull(fault(cache, write, close))
    assertArrayEquals(prior, Files.readAllBytes(target.toPath()))
    assertEquals("a failed write must reclaim its own staging file only", setOf(foreign), hidden(target.parentFile))
    assertArrayEquals(byteArrayOf(71, 72), Files.readAllBytes(foreign.toPath()))
  }

  @Test fun failedWriteReclaimsItsTempAndPreservesThePreviousImage() = failedReplacement(true, false)
  @Test fun failedCloseReclaimsItsTempAndPreservesThePreviousImage() = failedReplacement(false, true)

  @Test fun failedFirstWriteLeavesNoHiddenPartialFile() {
    val cache = cache()
    val target = cache.imageFile(url)
    assertNull(fault(cache, true, false))
    assertFalse(target.exists())
    assertTrue("partial image is excluded from resident accounting and must be reclaimed", hidden(target.parentFile).isEmpty())
  }

  @Test fun failedFirstCloseLeavesNoHiddenPartialFile() {
    val cache = cache()
    val target = cache.imageFile(url)
    assertNull(fault(cache, false, true))
    assertFalse(target.exists())
    assertTrue("closed failed image must not remain outside the resident budget", hidden(target.parentFile).isEmpty())
  }

  private class Writer(val bytes: ByteArray, val fail: Boolean = false) {
    val staged = AtomicReference<File>()
    val written = CountDownLatch(1)
    val release = CountDownLatch(1)

    fun put(cache: MediaCache, url: String): File? =
        Mockito.mockConstruction(FileOutputStream::class.java) { stream, context ->
          val file = context.arguments().single() as File
          staged.set(file)
          Mockito.doAnswer { invocation ->
            assertArrayEquals(bytes, invocation.arguments.single() as ByteArray)
            Files.write(file.toPath(), bytes)
            written.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "fixture writer release timeout" }
            if (fail) throw IOException("generated writer fault")
            null
          }.`when`(stream).write(ArgumentMatchers.any(ByteArray::class.java))
        }.use { cache.putImage(url, bytes) }
  }

  @Test fun overlappingSuccessfulWritesPublishTheirOwnCompleteBytes() {
    val cache = cache()
    val target = cache.imageFile(url)
    val a = Writer(byteArrayOf(31, 32, 33))
    val b = Writer(byteArrayOf(41, 42, 43, 44))
    val workers = Executors.newFixedThreadPool(2)
    try {
      val first = workers.submit<File?> { a.put(cache, url) }
      assertTrue("first writer must reach the actual stream", a.written.await(5, TimeUnit.SECONDS))
      val second = workers.submit<File?> { b.put(cache, url) }
      assertTrue("second writer must reach the actual stream", b.written.await(5, TimeUnit.SECONDS))
      a.release.countDown()
      assertNotNull(first.get(5, TimeUnit.SECONDS))
      assertArrayEquals("second writer is still pending; first publication must contain its own bytes", a.bytes, Files.readAllBytes(target.toPath()))
      b.release.countDown()
      assertNotNull(second.get(5, TimeUnit.SECONDS))
      assertArrayEquals(b.bytes, Files.readAllBytes(target.toPath()))
      assertTrue(hidden(target.parentFile).isEmpty())
    } finally {
      a.release.countDown(); b.release.countDown()
      workers.shutdown()
      assertTrue("owned writer threads must retire", workers.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test fun aFailedWriterDoesNotRemoveAnotherPendingWritersFile() {
    val cache = cache()
    val target = cache.imageFile(url)
    Files.write(target.toPath(), prior)
    val a = Writer(byteArrayOf(51, 52), fail = true)
    val b = Writer(byteArrayOf(61, 62, 63))
    val workers = Executors.newFixedThreadPool(2)
    try {
      val first = workers.submit<File?> { a.put(cache, url) }
      assertTrue(a.written.await(5, TimeUnit.SECONDS))
      val second = workers.submit<File?> { b.put(cache, url) }
      assertTrue(b.written.await(5, TimeUnit.SECONDS))
      a.release.countDown()
      assertNull(first.get(5, TimeUnit.SECONDS))
      assertArrayEquals(prior, Files.readAllBytes(target.toPath()))
      assertTrue("failed writer cleanup must preserve the other live staging file", b.staged.get().isFile)
      b.release.countDown()
      assertNotNull(second.get(5, TimeUnit.SECONDS))
      assertArrayEquals(b.bytes, Files.readAllBytes(target.toPath()))
      assertTrue(hidden(target.parentFile).isEmpty())
    } finally {
      a.release.countDown(); b.release.countDown()
      workers.shutdown()
      assertTrue("owned writer threads must retire", workers.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test fun ordinaryReplacementUsesTheRealStreamAndPublishesCompleteBytes() {
    val cache = cache()
    val target = cache.imageFile(url)
    Files.write(target.toPath(), prior)
    assertEquals(target, cache.putImage(url, incoming))
    assertArrayEquals(incoming, Files.readAllBytes(target.toPath()))
    assertTrue(hidden(target.parentFile).isEmpty())
  }

  @Test fun emptyInputRetainsItsExistingAcceptance() {
    val cache = cache()
    val target = cache.imageFile(url)
    assertEquals(target, cache.putImage(url, byteArrayOf()))
    assertEquals(0L, target.length())
    assertNull(cache.getIfPresent(url, false))
    assertTrue(hidden(target.parentFile).isEmpty())
  }
}
