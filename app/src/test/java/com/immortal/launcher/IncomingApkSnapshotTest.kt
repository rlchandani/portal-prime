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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingApkSnapshotTest {
  @get:Rule val cache = TemporaryFolder()
  private val firstBytes = byteArrayOf(65, 80, 75, 45, 65, 11, 22)
  private val secondBytes = byteArrayOf(65, 80, 75, 45, 66, 33, 44)
  private val foreignBytes = byteArrayOf(70, 79, 82, 69, 73, 71, 78)

  private fun copy(bytes: ByteArray): File =
      requireNotNull(IncomingApkSnapshot.copy(cache.root) { ByteArrayInputStream(bytes) })

  private fun entries(): Set<String> = cache.root.listFiles()!!.map { it.name }.toSet()

  private fun brokenRead(): InputStream = object : InputStream() {
    private var emitted = false
    override fun read(): Int = throw IOException("generated read failure")
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (emitted) throw IOException("generated read failure")
      emitted = true
      byteArrayOf(99, 98, 97).copyInto(buffer, offset)
      return 3
    }
  }

  private fun brokenClose(): InputStream = object : ByteArrayInputStream(secondBytes) {
    override fun close() { throw IOException("generated close failure") }
  }

  @Test fun oneSuccessfulImportPreservesBytesAndClosesExactlyOnce() {
    var opens = 0
    var closes = 0
    val file = IncomingApkSnapshot.copy(cache.root) {
      opens++
      object : ByteArrayInputStream(firstBytes) {
        override fun close() { closes++; super.close() }
      }
    }
    assertNotNull(file)
    assertArrayEquals(firstBytes, file!!.readBytes())
    assertEquals(1, opens)
    assertEquals(1, closes)
  }

  @Test fun successiveImportsRetainFirstBytesAndOwnDistinctPaths() {
    val first = copy(firstBytes)
    val second = copy(secondBytes)
    assertArrayEquals(firstBytes, first.readBytes())
    assertArrayEquals(secondBytes, second.readBytes())
    assertNotEquals(first.canonicalPath, second.canonicalPath)
  }

  @Test fun secondReadFailureCannotChangeFirstSnapshot() {
    val first = copy(firstBytes)
    assertNull(IncomingApkSnapshot.copy(cache.root) { brokenRead() })
    assertArrayEquals(firstBytes, first.readBytes())
  }

  @Test fun secondCloseFailureCannotChangeFirstSnapshot() {
    val first = copy(firstBytes)
    assertNull(IncomingApkSnapshot.copy(cache.root) { brokenClose() })
    assertArrayEquals(firstBytes, first.readBytes())
  }

  @Test fun secondNullSourceRetainsFirstSnapshot() {
    val first = copy(firstBytes)
    assertNull(IncomingApkSnapshot.copy(cache.root) { null })
    assertArrayEquals(firstBytes, first.readBytes())
  }

  @Test fun secondOpenFailureRetainsFirstSnapshot() {
    val first = copy(firstBytes)
    assertNull(IncomingApkSnapshot.copy(cache.root) { throw IOException("generated open failure") })
    assertArrayEquals(firstBytes, first.readBytes())
  }

  @Test fun emptySuccessfulImportOwnsItsSnapshotWithoutChangingFirst() {
    val first = copy(firstBytes)
    val empty = copy(byteArrayOf())
    assertArrayEquals(firstBytes, first.readBytes())
    assertArrayEquals(byteArrayOf(), empty.readBytes())
    assertNotEquals(first.canonicalPath, empty.canonicalPath)
  }

  @Test fun readFailureLeavesOnlyEarlierSnapshotAndForeignCache() {
    val first = copy(firstBytes)
    val foreign = File(cache.root, "foreign-cache.bin").apply { writeBytes(foreignBytes) }
    val before = entries()
    assertNull(IncomingApkSnapshot.copy(cache.root) { brokenRead() })
    assertEquals(before, entries())
    assertArrayEquals(firstBytes, first.readBytes())
    assertArrayEquals(foreignBytes, foreign.readBytes())
  }

  @Test fun closeFailureRemovesItsPartialFileAndRetainsForeignCache() {
    val foreign = File(cache.root, "foreign-cache.bin").apply { writeBytes(foreignBytes) }
    val before = entries()
    assertNull(IncomingApkSnapshot.copy(cache.root) { brokenClose() })
    assertEquals(before, entries())
    assertArrayEquals(foreignBytes, foreign.readBytes())
  }

  @Test fun nullSourceCreatesNoFileAndRetainsForeignCache() {
    val foreign = File(cache.root, "foreign-cache.bin").apply { writeBytes(foreignBytes) }
    val before = entries()
    assertNull(IncomingApkSnapshot.copy(cache.root) { null })
    assertEquals(before, entries())
    assertArrayEquals(foreignBytes, foreign.readBytes())
  }
}
