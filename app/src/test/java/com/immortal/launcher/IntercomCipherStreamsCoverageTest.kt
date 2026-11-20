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
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Single-byte reads/writes, zero-length reads and stream lifecycle of the sealed intercom audio. */
class IntercomCipherStreamsCoverageTest {
  private val sessionKey = ByteArray(32) { (it * 3).toByte() }

  private class Tracking(private val delegate: OutputStream = ByteArrayOutputStream()) : OutputStream() {
    var flushes = 0
    var closed = false
    val bytes get() = (delegate as ByteArrayOutputStream).toByteArray()
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() { flushes++ }
    override fun close() { closed = true }
  }

  @Test fun singleByteWritesBecomeOneFrameEachAndReadBackByteByByte() {
    val sink = Tracking()
    IntercomSealingOutputStream(sink, sessionKey).use { out ->
      out.write(0x41)
      out.write(0xff)
    }
    val frameSize = 1 + IntercomFrames.TAG_BYTES
    assertEquals(2 * (2 + frameSize), sink.bytes.size)
    assertEquals(frameSize, (sink.bytes[0].toInt() and 0xff) shl 8 or (sink.bytes[1].toInt() and 0xff))
    assertTrue(sink.closed)
    val input = IntercomOpeningInputStream(ByteArrayInputStream(sink.bytes), sessionKey)
    assertEquals(0x41, input.read())
    assertEquals("high byte comes back unsigned", 0xff, input.read())
    assertEquals(-1, input.read())
  }

  @Test fun flushIsForwardedAndEveryWriteFlushes() {
    val sink = Tracking()
    val out = IntercomSealingOutputStream(sink, sessionKey)
    out.write(ByteArray(3), 0, 3)
    assertEquals(1, sink.flushes)
    out.flush()
    assertEquals(2, sink.flushes)
  }

  @Test fun emptyWriteEmitsNothingButStillFlushes() {
    val sink = Tracking()
    IntercomSealingOutputStream(sink, sessionKey).write(ByteArray(4), 2, 0)
    assertEquals(0, sink.bytes.size)
    assertEquals(1, sink.flushes)
  }

  @Test fun zeroLengthReadReturnsZeroWithoutConsuming() {
    val sink = Tracking()
    IntercomSealingOutputStream(sink, sessionKey).write(byteArrayOf(1, 2, 3))
    val input = IntercomOpeningInputStream(ByteArrayInputStream(sink.bytes), sessionKey)
    assertEquals(0, input.read(ByteArray(4), 0, 0))
    val buf = ByteArray(8)
    assertEquals(3, input.read(buf, 2, 6))
    assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOfRange(2, 5))
  }

  @Test fun partialReadsDrainAFrameBeforeTheNext() {
    val sink = Tracking()
    IntercomSealingOutputStream(sink, sessionKey).apply { write(byteArrayOf(1, 2, 3)); write(byteArrayOf(4)) }
    val input = IntercomOpeningInputStream(ByteArrayInputStream(sink.bytes), sessionKey)
    val buf = ByteArray(2)
    assertEquals(2, input.read(buf, 0, 2))
    assertEquals(1, input.read(buf, 0, 2))
    assertEquals(3, buf[0].toInt())
    assertEquals(1, input.read(buf, 0, 2))
    assertEquals(4, buf[0].toInt())
    assertEquals(-1, input.read(buf, 0, 2))
  }

  @Test fun closingTheOpeningStreamClosesTheSource() {
    var closed = false
    val source = object : InputStream() {
      override fun read(): Int = -1
      override fun close() { closed = true }
    }
    IntercomOpeningInputStream(source, sessionKey).close()
    assertTrue(closed)
  }
}
