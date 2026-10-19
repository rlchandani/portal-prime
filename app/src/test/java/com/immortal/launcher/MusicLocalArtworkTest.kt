/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/** Exercise URI admission and the real bounded reader without Android providers/decoders. */
class MusicLocalArtworkTest {
  @Test fun deviceLocalSchemesAreAdmitted() {
    for (uri in listOf("content://fixture/cover/1", "file:///fixture/cover.png",
        "android.resource://fixture/drawable/cover", "CONTENT://fixture/cover/1")) {
      assertEquals(uri, MusicArtworkRoute.LOCAL, MusicLocalArtwork.routeFor(uri))
    }
  }

  @Test fun httpSchemesKeepTheSharedNetworkTransport() {
    for (uri in listOf("http://fixture/cover", "https://fixture/cover", "HTTPS://fixture/cover")) {
      assertEquals(uri, MusicArtworkRoute.NETWORK, MusicLocalArtwork.routeFor(uri))
    }
  }

  @Test fun unknownRelativeOrMalformedUrisStayUnavailable() {
    for (uri in listOf("", "/fixture/cover", "ftp://fixture/cover", "data:image/png;base64,AAAA",
        "javascript:fixture", "content://fixture/%zz", "content://fixture/bad\npath")) {
      assertEquals(uri, MusicArtworkRoute.UNSUPPORTED, MusicLocalArtwork.routeFor(uri))
    }
  }

  @Test fun localReadPreservesBinaryContentAndClosesTheProviderStream() {
    val bytes = byteArrayOf(0, 127, -128, -1, 13, 10)
    for (uri in listOf("content://fixture/cover%20art", "file:///fixture/cover.png",
        "android.resource://fixture/123")) {
      var closed = false
      val input = object : ByteArrayInputStream(bytes) {
        override fun close() { closed = true; super.close() }
      }
      assertArrayEquals(bytes, MusicLocalArtwork.readLocal(uri) { requested ->
        assertEquals(uri, requested)
        input
      })
      assertTrue("provider stream must close after success", closed)
    }
  }

  @Test fun networkAndUnsupportedUrisNeverOpenALocalProvider() {
    for (uri in listOf("http://fixture/cover", "https://fixture/cover", "ftp://fixture/cover",
        "/fixture/cover", "")) {
      assertNull(MusicLocalArtwork.readLocal(uri) {
        throw AssertionError("non-local artwork must not reach ContentResolver")
      })
    }
  }

  @Test fun exactEightMiBProviderBodyIsAcceptedWithoutTruncation() {
    val input = GeneratedInput(8L * 1024 * 1024)
    val bytes = MusicLocalArtwork.readLocal("content://fixture/cover") { input }
    assertNotNull(bytes)
    assertEquals(8 * 1024 * 1024, bytes!!.size)
    assertEquals(0x5a.toByte(), bytes.last())
    assertEquals(8L * 1024 * 1024, input.bytesRead)
    assertTrue(input.closed)
  }

  @Test fun unboundedProviderStopsAtEightMiBPlusOneProbeAndCloses() {
    val input = GeneratedInput(Long.MAX_VALUE, ceiling = 8L * 1024 * 1024 + 1)
    assertNull(MusicLocalArtwork.readLocal("content://fixture/cover") { input })
    assertEquals(8L * 1024 * 1024 + 1, input.bytesRead)
    assertTrue("rejected provider stream must close", input.closed)
  }

  @Test fun providerPermissionDenialReturnsUnavailableArtwork() {
    assertNull(MusicLocalArtwork.readLocal("content://fixture/private") {
      throw SecurityException("synthetic denied permission")
    })
  }

  @Test fun missingOrNullProviderStreamReturnsUnavailableArtwork() {
    assertNull(MusicLocalArtwork.readLocal("file:///fixture/missing") {
      throw FileNotFoundException("synthetic missing file")
    })
    assertNull(MusicLocalArtwork.readLocal("android.resource://fixture/missing") { null })
  }

  @Test fun providerReadFailureDiscardsPartialBytesAndCloses() {
    var closed = false
    val input = object : InputStream() {
      var first = true
      override fun read(): Int = throw IOException("synthetic interrupted provider")
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (!first) throw IOException("synthetic interrupted provider")
        first = false
        bytes[offset] = 42
        return 1
      }
      override fun close() { closed = true }
    }
    assertNull(MusicLocalArtwork.readLocal("content://fixture/cover") { input })
    assertTrue(closed)
  }

  @Test fun decodeAdmissionCapsOversizedRequestsBeforePixelAllocation() {
    val plan = MusicLocalArtwork.decodePlan(4096, 3072, Int.MAX_VALUE)!!
    assertEquals(1024, plan.maxEdge)
    assertEquals(4, plan.sampleSize)
  }

  @Test fun compactDecodeAdmissionKeepsBothEdgesUnder384() {
    val plan = MusicLocalArtwork.decodePlan(4000, 3000, 384)!!
    assertEquals(384, plan.maxEdge)
    assertEquals(16, plan.sampleSize)
  }

  @Test fun malformedDimensionsOrInvalidDecodeBudgetAreRejected() {
    assertNull(MusicLocalArtwork.decodePlan(0, 100, 384))
    assertNull(MusicLocalArtwork.decodePlan(100, -1, 384))
    assertNull(MusicLocalArtwork.decodePlan(100, 100, 0))
  }

  /** Produce a large/never-ending provider body without allocating a matching fixture. */
  private class GeneratedInput(
      private var remaining: Long,
      private val ceiling: Long = Long.MAX_VALUE,
  ) : InputStream() {
    var bytesRead = 0L
      private set
    var closed = false
      private set
    override fun read(): Int {
      if (remaining == 0L) return -1
      if (bytesRead >= ceiling) throw AssertionError("provider body drained beyond the read budget")
      remaining--
      bytesRead++
      return 0x5a
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (remaining == 0L) return -1
      val count = minOf(length.toLong(), remaining).toInt()
      if (count > ceiling - bytesRead) throw AssertionError("unbounded provider bulk read")
      bytes.fill(0x5a.toByte(), offset, offset + count)
      remaining -= count
      bytesRead += count
      return count
    }
    override fun close() { closed = true }
  }
}
