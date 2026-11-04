/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkBrowserSelectionTest {
  @get:Rule val temp = TemporaryFolder()
  private val first = byteArrayOf(65, 80, 75, 65, 11, 22)
  private val second = byteArrayOf(65, 80, 75, 66, 33, 44)
  private fun source() = temp.newFile("download.apk").apply { writeBytes(first) }
  private fun cache() = temp.newFolder("cache")
  private fun prepare(cache: File, source: File) =
      requireNotNull(ApkBrowserSelection.prepare(cache, source) { it.readBytes()[3].toInt() })

  @Test fun replacementAfterConfirmationRetainsConfirmedBytesAndMetadata() {
    val source = source()
    val selection = prepare(cache(), source)
    source.writeBytes(second)
    assertEquals(65, selection.metadata)
    assertArrayEquals(first, selection.file.readBytes())
    selection.close()
    assertArrayEquals(second, source.readBytes())
  }

  @Test fun deletingDownloadAfterConfirmationRetainsInstallerInput() {
    val source = source()
    val selection = prepare(cache(), source)
    assertTrue(source.delete())
    assertArrayEquals(first, selection.file.readBytes())
    selection.close()
  }

  @Test fun metadataReadsTheSamePrivateFileHandedToInstaller() {
    val source = source()
    val cache = cache()
    var parsed: File? = null
    val selection = requireNotNull(ApkBrowserSelection.prepare(cache, source) {
      parsed = it
      assertArrayEquals(first, it.readBytes())
      "Generated app A"
    })
    assertEquals(selection.file, parsed)
    assertEquals(cache.canonicalFile, selection.file.parentFile.canonicalFile)
    assertNotEquals(source.canonicalFile, selection.file.canonicalFile)
    selection.close()
  }

  @Test fun closeRemovesOnlyOwnedCopyAndCanRepeat() {
    val source = source()
    val cache = cache()
    val guard = File(cache, "guard.bin").apply { writeBytes(second) }
    val selection = prepare(cache, source)
    selection.close()
    selection.close()
    assertFalse(selection.file.exists())
    assertArrayEquals(first, source.readBytes())
    assertArrayEquals(second, guard.readBytes())
    assertEquals(setOf("guard.bin"), cache.list()!!.toSet())
  }

  @Test fun successiveSelectionsKeepBothCompletedCopies() {
    val source = source()
    val cache = cache()
    val a = prepare(cache, source)
    source.writeBytes(second)
    val b = prepare(cache, source)
    assertNotEquals(a.file.canonicalPath, b.file.canonicalPath)
    assertArrayEquals(first, a.file.readBytes())
    assertArrayEquals(second, b.file.readBytes())
    a.close()
    assertArrayEquals(second, b.file.readBytes())
    b.close()
    assertArrayEquals(second, source.readBytes())
  }

  @Test fun unreadableMetadataLeavesSourceAndForeignCache() {
    val source = source()
    val cache = cache()
    val guard = File(cache, "guard.bin").apply { writeBytes(second) }
    assertNull(ApkBrowserSelection.prepare<String>(cache, source) { null })
    assertArrayEquals(first, source.readBytes())
    assertArrayEquals(second, guard.readBytes())
    assertEquals(setOf("guard.bin"), cache.list()!!.toSet())
  }

  @Test fun metadataFailureCleansOwnedCopyAndPreservesOriginal() {
    val source = source()
    val cache = cache()
    val failure = IOException("generated metadata failure")
    val caught = runCatching {
      ApkBrowserSelection.prepare<String>(cache, source) { throw failure }
    }.exceptionOrNull()
    assertSame(failure, caught)
    assertArrayEquals(first, source.readBytes())
    assertTrue(cache.list()!!.isEmpty())
  }

  @Test fun missingDownloadFailsWithoutCallingMetadata() {
    val cache = cache()
    val missing = File(temp.root, "missing.apk")
    var calls = 0
    assertNull(ApkBrowserSelection.prepare(cache, missing) { calls++; "invalid" })
    assertEquals(0, calls)
    assertTrue(cache.list()!!.isEmpty())
  }
}
