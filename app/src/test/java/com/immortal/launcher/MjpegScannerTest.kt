/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames are cut by JPEG markers across arbitrary chunking; oversize frames are dropped. */
class MjpegScannerTest {
  private fun jpeg(vararg body: Int) = (listOf(0xFF, 0xD8) + body.toList() + listOf(0xFF, 0xD9)).map { it.toByte() }.toByteArray()
  private fun part(frame: ByteArray) = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + frame + "\r\n".toByteArray()

  @Test fun cutsFramesOutOfMultipart() {
    val a = jpeg(1, 2, 3)
    val b = jpeg(4, 0xFF, 5)
    val frames = MjpegScanner().feed(part(a) + part(b))
    assertEquals(2, frames.size)
    assertArrayEquals(a, frames[0])
    assertArrayEquals(b, frames[1])
  }

  @Test fun survivesAnyChunking() {
    val a = jpeg(9, 8, 7, 6)
    val stream = part(a) + part(a)
    val scanner = MjpegScanner()
    val frames = stream.toList().chunked(1).flatMap { scanner.feed(it.toByteArray()) }
    assertEquals(2, frames.size)
    frames.forEach { assertArrayEquals(a, it) }
  }

  @Test fun dropsOversizeFrames() {
    val big = jpeg(*IntArray(100) { 1 })
    val small = jpeg(2)
    val frames = MjpegScanner(maxFrame = 50).feed(part(big) + part(small))
    assertEquals(1, frames.size)
    assertArrayEquals(small, frames[0])
  }

  @Test fun respectsLength() {
    val buffer = part(jpeg(1)) + ByteArray(10)
    assertTrue(MjpegScanner().feed(buffer, 5).isEmpty())
  }
}
