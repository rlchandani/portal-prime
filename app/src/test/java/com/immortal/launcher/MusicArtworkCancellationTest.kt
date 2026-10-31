/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class MusicArtworkCancellationTest {
  @Test fun interruptedWorkerDoesNotBeginAnEncodedRead() {
    var reads = 0
    val input = object : InputStream() {
      override fun read(): Int { reads++; return -1 }
    }
    Thread.currentThread().interrupt()
    try {
      MusicArtworkPolicy.readBounded(input, 1024)
      fail("interrupted reader must abort")
    } catch (_: InterruptedIOException) { assertEquals(0, reads) }
    finally { Thread.interrupted() }
  }

  @Test fun cancellationBetweenChunksStopsReadingAndClosesTheLocalStream() {
    var reads = 0
    var closed = false
    val input = object : InputStream() {
      override fun read(): Int = throw AssertionError("unexpected scalar read")
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        reads++
        if (reads == 1) {
          bytes[offset] = 42
          Thread.currentThread().interrupt()
          return 1
        }
        return -1
      }
      override fun close() { closed = true }
    }
    try {
      assertNull(MusicLocalArtwork.readLocal("content://fixture/cancelled") { input })
      assertEquals(1, reads)
      assertTrue(closed)
    } finally { Thread.interrupted() }
  }
}
