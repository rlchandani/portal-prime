/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream

/**
 * Cuts JPEG frames out of an MJPEG (`multipart/x-mixed-replace`) byte stream by their start
 * (FF D8) and end (FF D9) markers, ignoring the multipart boundaries and headers in between.
 * Feed bytes as they arrive; complete frames come back in order. A frame bigger than [maxFrame]
 * is dropped rather than buffered without limit. Not thread-safe; one per stream. Pure.
 */
internal class MjpegScanner(private val maxFrame: Int = DEFAULT_MAX_FRAME) {
  private val frame = ByteArrayOutputStream()
  private var inFrame = false
  private var previous = -1

  fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
    val out = ArrayList<ByteArray>(1)
    for (i in 0 until length) {
      val b = bytes[i].toInt() and BYTE_MASK
      if (!inFrame) {
        if (previous == MARKER && b == SOI) {
          inFrame = true
          frame.reset()
          frame.write(MARKER)
          frame.write(SOI)
        }
      } else {
        frame.write(b)
        if (previous == MARKER && b == EOI) {
          out += frame.toByteArray()
          inFrame = false
          frame.reset()
          previous = -1
          continue
        }
        if (frame.size() > maxFrame) {
          inFrame = false
          frame.reset()
        }
      }
      previous = b
    }
    return out
  }

  companion object {
    const val DEFAULT_MAX_FRAME = 4 * 1024 * 1024
    private const val BYTE_MASK = 0xFF
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
  }
}
