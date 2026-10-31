/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class EngineRemoteImageCacheTest {
  private class Fixture : AutoCloseable {
    val root = Files.createTempDirectory("remote-image-cache-test").toFile()
    val dir = File(root, "sources")
    val jobs = ArrayList<(() -> Boolean) -> Unit>()
    var downloads = 0
    var preparations = 0
    var accepting = true
    var downloadResult = true
    var bytes = 64
    var duringDownload: () -> Unit = {}
    private val owner = Mockito.mock(EngineDerivativeWork.Owner::class.java) { invocation ->
      if (invocation.method.name == "submit") {
        if (accepting) jobs.add(invocation.getArgument(1))
        accepting
      } else Mockito.RETURNS_DEFAULTS.answer(invocation)
    }
    val cache = EngineRemoteImageCache(dir,
        download = { _, dst, _ ->
          downloads++; dst.writeBytes(ByteArray(bytes) { 3 }); duringDownload(); downloadResult
        },
        prepare = { source, edge, _, _ ->
          preparations++
          File(root, "${source.name}-$edge.jpg").apply { writeBytes(byteArrayOf(7)) }
        },
        owner = owner, cleanup = { it() }, encodedLimit = 128, sourceBudgetBytes = 150)
    fun run() = jobs.removeAt(0).invoke { true }
    override fun close() { cache.close(); root.deleteRecursively() }
  }

  @Test fun sameTierReadersShareDownloadAndPreparedPixels() = Fixture().use { f ->
    val first = f.cache.request("https://synthetic.invalid/one", 1000)
    val second = f.cache.request("https://synthetic.invalid/one", 1000)
    assertSame(first, second); assertEquals(1, f.jobs.size)
    f.run()
    assertNotNull(first.get()); assertEquals(first.get(), second.get())
    assertEquals(1, f.downloads); assertEquals(1, f.preparations)
    assertEquals(first.get(), f.cache.ready("https://synthetic.invalid/one", 1000))
  }

  @Test fun additionalTierReusesTheEncodedSourceWithoutAnotherTransfer() = Fixture().use { f ->
    val small = f.cache.request("https://synthetic.invalid/one", 600); f.run()
    val large = f.cache.request("https://synthetic.invalid/one", 2400); f.run()
    assertNotNull(small.get()); assertNotNull(large.get())
    assertEquals(1, f.downloads); assertEquals(2, f.preparations)
  }

  @Test fun rejectedQueueDoesNotDownloadOrCreateADirectory() = Fixture().use { f ->
    f.accepting = false
    assertNull(f.cache.request("https://synthetic.invalid/one", 2400).get())
    assertEquals(0, f.downloads); assertFalse(f.dir.exists())
  }

  @Test fun failedAndOversizedTransfersNeverReachPixelPreparation() = Fixture().use { f ->
    f.downloadResult = false
    val failed = f.cache.request("https://synthetic.invalid/one", 2400); f.run()
    assertNull(failed.get()); assertEquals(0, f.preparations)
    f.downloadResult = true; f.bytes = 129
    val oversized = f.cache.request("https://synthetic.invalid/two", 2400); f.run()
    assertNull(oversized.get()); assertEquals(0, f.preparations)
    assertTrue(f.dir.listFiles().orEmpty().isEmpty())
  }

  @Test fun cancellationDuringDownloadReleasesReadersAndRemovesOwnedFiles() = Fixture().use { f ->
    f.duringDownload = { f.cache.close() }
    val result = f.cache.request("https://synthetic.invalid/one", 2400); f.run()
    assertNull(result.get()); assertEquals(0, f.preparations)
    assertFalse(f.dir.exists())
    assertNull(f.cache.request("https://synthetic.invalid/two", 600).get())
  }

  @Test fun encodedSourceBudgetEvictsOldFilesAfterTheirPreparationFinishes() = Fixture().use { f ->
    for (i in 1..4) { f.cache.request("https://synthetic.invalid/$i", 2400); f.run() }
    assertEquals(4, f.preparations)
    assertTrue(f.dir.listFiles().orEmpty().sumOf { it.length() } <= 150)
    assertTrue(f.dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
  }
}
