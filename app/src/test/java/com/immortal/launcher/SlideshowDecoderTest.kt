/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SlideshowDecoderTest {
  @Test fun encodedReadPreservesAllBytesExactlyAtTheLimitWithoutClosingTheCallerStream() {
    var closed = false
    val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
      override fun close() { closed = true }
    }
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), SlideshowDecoder.readEncoded(stream, 4))
    assertFalse(closed)
  }

  @Test fun oversizedStreamStopsAfterOneByteBeyondTheLimit() {
    var count = 0
    val stream = object : InputStream() {
      override fun read(): Int { count++; return 7 }
    }
    assertNull(SlideshowDecoder.readEncoded(stream, 16))
    assertEquals(17, count)
  }

  @Test fun callerCannotRaiseTheEncodedCapAndNoUnboundedReadIsNeeded() {
    var count = 0L
    val stream = object : InputStream() {
      override fun read(): Int { count++; return 0 }
      override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        count += len
        java.util.Arrays.fill(bytes, off, off + len, 0.toByte())
        return len
      }
    }
    assertNull(SlideshowDecoder.readEncoded(stream, Int.MAX_VALUE))
    assertEquals(32L * 1024 * 1024 + 1, count)
  }

  @Test fun shortAndZeroLengthBulkReadsStillMakeProgress() {
    val data = byteArrayOf(2, 4, 6, 8)
    var index = 0
    var zero = true
    val stream = object : InputStream() {
      override fun read() = if (index < data.size) data[index++].toInt() else -1
      override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        if (zero) { zero = false; return 0 }
        if (index == data.size) return -1
        bytes[off] = data[index++]
        return 1
      }
    }
    assertArrayEquals(data, SlideshowDecoder.readEncoded(stream, 4))
  }

  @Test fun preexistingInterruptionRejectsWorkBeforeReadingAndPreservesTheFlag() {
    var reads = 0
    val stream = object : InputStream() {
      override fun read(): Int { reads++; return -1 }
    }
    try {
      Thread.currentThread().interrupt()
      assertNull(SlideshowDecoder.readEncoded(stream, 16))
      assertNull(SlideshowDecoder.stream(stream))
      assertNull(SlideshowDecoder.bytes(byteArrayOf(1)))
      assertNull(SlideshowDecoder.file("ignored-because-cancelled"))
      assertEquals(0, reads)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test fun interruptionDuringReadingDiscardsEncodedBytes() {
    val stream = object : InputStream() {
      override fun read(): Int { Thread.currentThread().interrupt(); return 1 }
    }
    try {
      assertNull(SlideshowDecoder.readEncoded(stream, 16))
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test fun interruptedIOExceptionIsReportedAsCancellation() {
    val stream = object : InputStream() {
      override fun read(): Int = throw InterruptedIOException("cancelled")
    }
    try {
      assertNull(SlideshowDecoder.readEncoded(stream, 16))
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test fun readFailureReturnsNullRatherThanPublishingAPartialImage() {
    val stream = object : InputStream() {
      override fun read(): Int = throw IOException("truncated")
    }
    assertNull(SlideshowDecoder.readEncoded(stream, 16))
  }

  @Test fun socketTimeoutDoesNotPoisonTheWorkerWithAnInterruptedFlag() {
    val stream = object : InputStream() {
      override fun read(): Int = throw SocketTimeoutException("read deadline")
    }
    assertNull(SlideshowDecoder.readEncoded(stream, 16))
    assertFalse(Thread.currentThread().isInterrupted)
  }

  @Test fun emptyAndOversizedBytesAreRejectedBeforeAndroidDecode() {
    assertNull(SlideshowDecoder.bytes(byteArrayOf()))
    assertNull(SlideshowDecoder.bytes(ByteArray(32 * 1024 * 1024 + 1)))
    assertNull(SlideshowDecoder.bytes(byteArrayOf(1), 0))
    assertNull(SlideshowDecoder.file("/this-file-does-not-exist"))
  }

  @Test fun oversizedFileIsRejectedBeforeReadingOrDecoding() {
    val file = Files.createTempFile("slideshow-oversized-", ".jpg").toFile()
    try {
      RandomAccessFile(file, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
      assertNull(SlideshowDecoder.file(file.path))
    } finally {
      file.delete()
    }
  }

  @Test fun invalidReadLimitDoesNotTouchTheStream() {
    val stream = object : InputStream() {
      override fun read(): Int = throw AssertionError("unexpected read")
    }
    assertNull(SlideshowDecoder.readEncoded(stream, 0))
  }
}
