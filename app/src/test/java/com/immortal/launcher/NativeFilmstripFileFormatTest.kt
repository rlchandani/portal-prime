/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.management.ThreadMXBean
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeFilmstripFileFormatTest {
  @get:Rule val temporary = TemporaryFolder()

  private val gifSignature = "GIF89a".toByteArray(Charsets.US_ASCII)
  private val jpegSignature = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

  @Test fun largeSparseGifIsRejectedWithoutAllocatingItsEncodedSize() {
    val file = sparseFile("large-cached.jpg", 16L * 1024 * 1024, gifSignature)
    assertRejectedWithLowAllocation(file)
  }

  @Test fun renamingGifToJpegDoesNotChangeAdmission() {
    // A complete single-frame 1x1 GIF, so the extension cannot disguise its format.
    val gif = gifSignature + byteArrayOf(
      1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
      0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b
    )
    val original = temporary.newFile("photo.gif").apply { writeBytes(gif) }
    assertFalse(NativeFilmstripFormat.isConfirmedStatic(original))
    val renamed = File(temporary.root, "photo.jpg")
    assertTrue(original.renameTo(renamed))
    assertFalse(NativeFilmstripFormat.isConfirmedStatic(renamed))
    assertArrayEquals(gif, renamed.readBytes())
  }

  @Test fun interruptedCallerGetsInterruptedIOExceptionBeforeScanning() {
    val file = temporary.newFile("photo.jpg").apply { writeBytes(jpegSignature) }
    try {
      Thread.currentThread().interrupt()
      try {
        NativeFilmstripFormat.isConfirmedStatic(file)
        fail("An interrupted scan must throw InterruptedIOException")
      } catch (_: InterruptedIOException) {
        // Cancellation must propagate rather than become a format decision.
      }
    } finally {
      Thread.interrupted()
    }
  }

  @Test fun fileAbove32MiBLimitIsRejectedWithoutAllocatingItsEncodedSize() {
    // JPEG would be admitted from its signature if the file-size limit were missed.
    val file = sparseFile("oversize.jpg", 32L * 1024 * 1024 + 1, jpegSignature)
    assertRejectedWithLowAllocation(file)
  }

  @Test fun jpegFileUsesTheSameThreeByteSignatureAsByteArrayAdmission() {
    val cases = listOf(
      jpegSignature to true,
      (jpegSignature + byteArrayOf(0xe0.toByte(), 0, 16)) to true,
      jpegSignature.copyOf(2) to false,
      byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0) to false
    )
    for ((encoded, expected) in cases) {
      val file = temporary.newFile().apply { writeBytes(encoded) }
      assertEquals(expected, NativeFilmstripFormat.isConfirmedStatic(encoded))
      assertEquals(expected, NativeFilmstripFormat.isConfirmedStatic(file))
    }
  }

  @Test fun inspectedFileRemainsCallerOwnedAndCanBeDeletedAndReplaced() {
    val file = temporary.newFile("caller-owned.jpg").apply { writeBytes(jpegSignature) }
    assertTrue(NativeFilmstripFormat.isConfirmedStatic(file))
    assertTrue(file.exists())
    assertArrayEquals(jpegSignature, file.readBytes())
    assertTrue("Inspection must release the file for deletion", file.delete())
    assertTrue(file.createNewFile())
    file.writeBytes(gifSignature)
    assertFalse(NativeFilmstripFormat.isConfirmedStatic(file))
    assertTrue(file.exists())
    assertArrayEquals(gifSignature, file.readBytes())
    assertTrue("Rejected files also remain caller-owned", file.delete())
  }

  private fun sparseFile(name: String, length: Long, signature: ByteArray): File {
    val file = temporary.newFile(name)
    RandomAccessFile(file, "rw").use {
      it.write(signature)
      it.setLength(length)
    }
    assertEquals(length, file.length())
    return file
  }

  private fun assertRejectedWithLowAllocation(file: File) {
    val counter = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    assumeNotNull(counter)
    val bean = checkNotNull(counter)
    assumeTrue("JVM has no calling-thread allocation counter", bean.isThreadAllocatedMemorySupported)
    assumeTrue("Calling-thread allocation measurement is disabled", bean.isThreadAllocatedMemoryEnabled)

    // Initialize scanner classes and both signature paths on tiny files. Fixture creation,
    // warmup, counter initialization, and assertions are outside the measured interval.
    val warmGif = temporary.newFile().apply { writeBytes(gifSignature) }
    val warmJpeg = temporary.newFile().apply { writeBytes(jpegSignature) }
    val threadId = Thread.currentThread().id
    repeat(16) {
      assertFalse(NativeFilmstripFormat.isConfirmedStatic(warmGif))
      assertTrue(NativeFilmstripFormat.isConfirmedStatic(warmJpeg))
      assumeTrue("Allocation counter is unavailable for this thread",
        bean.getThreadAllocatedBytes(threadId) >= 0L)
    }

    val before = bean.getThreadAllocatedBytes(threadId)
    val confirmed = NativeFilmstripFormat.isConfirmedStatic(file)
    val after = bean.getThreadAllocatedBytes(threadId)

    assumeTrue("Calling-thread allocation measurement became unavailable",
      bean.isThreadAllocatedMemoryEnabled && before >= 0L && after >= before)
    val allocated = after - before
    println("Filmstrip allocation: encodedBytes=${file.length()}, allocatedJavaBytes=$allocated")
    assertFalse(confirmed)
    assertTrue("Scanner allocated $allocated Java bytes on the calling thread (limit 128 KiB)",
      allocated <= 128L * 1024)
  }
}
