/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Remote image source cache: limits, inactive owners, failed preparation and sibling cleanup. */
class EngineRemoteImageCacheCoverageTest {
  private class Fixture(
      val root: File = Files.createTempDirectory("remote-image-cov").toFile(),
      val dir: File = File(root, "sources"),
      var active: Boolean = true,
      var bytes: Int = 16,
      var prepared: (File, Int) -> File? = { source, edge -> File(root, "${source.name}-$edge.jpg").apply { writeBytes(byteArrayOf(1)) } },
  ) : AutoCloseable {
    val jobs = ArrayList<(() -> Boolean) -> Unit>()
    var downloads = 0
    private val owner = Mockito.mock(EngineDerivativeWork.Owner::class.java) { invocation ->
      if (invocation.method.name == "submit") { jobs.add(invocation.getArgument(1)); true }
      else Mockito.RETURNS_DEFAULTS.answer(invocation)
    }
    val cache = EngineRemoteImageCache(dir,
        download = { _, dst, _ -> downloads++; dst.writeBytes(ByteArray(bytes) { 5 }); true },
        prepare = { source, edge, _, _ -> prepared(source, edge) },
        owner = owner, cleanup = { it() }, isActive = { active }, encodedLimit = 64, sourceBudgetBytes = 64)
    fun run() = jobs.removeAt(0).invoke { true }
    override fun close() { cache.close(); root.deleteRecursively() }
  }
  private val url = "https://synthetic.invalid/photo.jpg"
  private val noop: (String, File, () -> Boolean) -> Boolean = { _, _, _ -> true }
  private val noPrepare: (File, Int, () -> Boolean, java.util.concurrent.CompletableFuture<Unit>) -> File? = { _, _, _, _ -> null }

  @Test fun constructorRejectsImpossibleLimits() {
    val dir = Files.createTempDirectory("remote-image-limits").toFile()
    try {
      assertTrue(runCatching { EngineRemoteImageCache(dir, noop, noPrepare, encodedLimit = 0) }.exceptionOrNull() is IllegalArgumentException)
      assertTrue(runCatching { EngineRemoteImageCache(dir, noop, noPrepare, encodedLimit = SlideshowDecodeBudget.MAX_ENCODED_BYTES + 1) }.isFailure)
      assertTrue(runCatching { EngineRemoteImageCache(dir, noop, noPrepare, encodedLimit = 64, sourceBudgetBytes = 63) }.isFailure)
    } finally { dir.deleteRecursively() }
  }

  @Test fun closedCacheAnswersNullImmediately() = Fixture().use { f ->
    f.cache.request(url, 1000); f.run()
    assertNotNull(f.cache.ready(url, 1000))
    f.cache.close()
    assertNull(f.cache.ready(url, 1000))
    assertNull(f.cache.request(url, 1000).get())
    assertNull(f.cache.prepareForLoad(url, 1000))
    assertTrue(f.jobs.isEmpty())
  }

  @Test fun inactiveOwnerNeverDownloads() = Fixture(active = false).use { f ->
    val result = f.cache.request(url, 1000); f.run()
    assertNull(result.get())
    assertEquals(0, f.downloads)
  }

  @Test fun emptyDownloadIsDiscarded() = Fixture(bytes = 0).use { f ->
    val result = f.cache.request(url, 1000); f.run()
    assertNull(result.get())
    assertTrue(f.dir.listFiles().orEmpty().isEmpty())
  }

  @Test fun failedOrEmptyPreparationIsNotCachedButSourceIsReused() = Fixture().use { f ->
    f.prepared = { _, _ -> null }
    val first = f.cache.request(url, 1000); f.run()
    assertNull(first.get())
    assertNull(f.cache.ready(url, 1000))
    f.prepared = { source, _ -> File(f.root, "${source.name}.empty").apply { createNewFile() } }
    val second = f.cache.request(url, 1000); f.run()
    assertNull(second.get())
    f.prepared = { source, edge -> File(f.root, "${source.name}-$edge.ok").apply { writeBytes(byteArrayOf(1)) } }
    val third = f.cache.request(url, 1000); f.run()
    assertNotNull(third.get())
    assertEquals("the encoded source survives failed preparations", 1, f.downloads)
  }

  @Test fun unusableDirectoryFailsWithoutDownloading() {
    val root = Files.createTempDirectory("remote-image-file").toFile()
    try {
      val blocker = File(root, "sources").apply { writeText("not a directory") }
      Fixture(root = root, dir = blocker).use { f ->
        val result = f.cache.request(url, 1000); f.run()
        assertNull(result.get())
        assertEquals(0, f.downloads)
      }
    } finally { root.deleteRecursively() }
  }

  @Test fun staleSiblingSessionDirectoriesAreRemovedButLiveOnesKept() {
    val root = Files.createTempDirectory("remote-image-siblings").toFile()
    try {
      val parent = File(root, "ss_remote").apply { mkdirs() }
      val stale = File(parent, "old-session").apply { mkdirs(); File(this, "x.source").writeText("old") }
      val strayFile = File(parent, "keep.txt").apply { writeText("file") }
      Fixture(root = root, dir = File(parent, "current")).use { f ->
        val result = f.cache.request(url, 1000); f.run()
        assertNotNull(result.get())
        assertFalse(stale.exists())
        assertTrue("plain files beside session directories are left alone", strayFile.exists())
      }
    } finally { root.deleteRecursively() }
  }

  @Test fun liveSiblingDirectoryIsNotDeleted() {
    val root = Files.createTempDirectory("remote-image-live").toFile()
    try {
      val parent = File(root, "ss_remote").apply { mkdirs() }
      Fixture(root = root, dir = File(parent, "a")).use { a ->
        a.cache.request(url, 1000); a.run()
        assertTrue(a.dir.isDirectory)
        Fixture(root = root, dir = File(parent, "b")).use { b ->
          b.cache.request(url, 1000); b.run()
          assertTrue("a sibling still in use keeps its sources", a.dir.isDirectory)
        }
      }
    } finally { root.deleteRecursively() }
  }

  @Test fun prepareForLoadReturnsReadyFilesAndHonoursInterrupts() = Fixture().use { f ->
    f.cache.request(url, 1000); f.run()
    assertNotNull(f.cache.prepareForLoad(url, 1000))
    Thread.currentThread().interrupt()
    try {
      assertNull(f.cache.prepareForLoad("https://synthetic.invalid/pending.jpg", 1000))
      assertTrue("interrupt status is restored", Thread.currentThread().isInterrupted)
    } finally { Thread.interrupted() }
  }
}
