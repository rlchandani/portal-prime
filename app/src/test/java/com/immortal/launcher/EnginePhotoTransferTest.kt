/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class EnginePhotoTransferTest {
  @Test fun exactLimitStreamsSuccessfullyWithoutAnOriginalSizedByteArray() {
    val output = ByteArrayOutputStream()
    assertTrue(EnginePhotoTransfer.copy(ByteArrayInputStream(ByteArray(64) { it.toByte() }), output, 64) { true })
    assertArrayEquals(ByteArray(64) { it.toByte() }, output.toByteArray())
  }

  @Test fun oversizedAndInfiniteStreamsProbeOnlyOneExtraByte() {
    var read = 0
    val infinite = object : InputStream() {
      override fun read(): Int { read++; return 7 }
    }
    val output = ByteArrayOutputStream()
    assertFalse(EnginePhotoTransfer.copy(infinite, output, 64) { true })
    assertEquals(65, read)
    assertTrue(output.size() <= 64)
  }

  @Test fun cancellationAfterReadDoesNotWriteTheLateChunk() {
    var current = true
    val source = object : InputStream() {
      override fun read(): Int { current = false; return 5 }
    }
    val output = ByteArrayOutputStream()
    assertFalse(EnginePhotoTransfer.copy(source, output, 64) { current })
    assertEquals(0, output.size())
  }

  @Test fun cancellationBeforeReadDoesNotTouchEitherStream() {
    val source = object : InputStream() { override fun read(): Int = error("late admission") }
    val output = ByteArrayOutputStream()
    assertFalse(EnginePhotoTransfer.copy(source, output, 64) { false })
    assertEquals(0, output.size())
  }

  @Test fun zeroProgressReadsAndEmptyFilesCannotHangOrPublishAnImage() {
    val source = object : InputStream() {
      var bytes = 3
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
      override fun read(): Int = if (bytes-- > 0) 6 else -1
    }
    val output = ByteArrayOutputStream()
    assertTrue(EnginePhotoTransfer.copy(source, output, 64) { true })
    assertEquals(3, output.size())
    assertFalse(EnginePhotoTransfer.copy(ByteArrayInputStream(byteArrayOf()), output, 64) { true })
  }
}
