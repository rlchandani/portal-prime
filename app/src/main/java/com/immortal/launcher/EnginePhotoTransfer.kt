/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream
import java.io.OutputStream

/** Stream encoded photos with fixed scratch space and a one-byte oversize probe. */
internal object EnginePhotoTransfer {
  fun copy(input: InputStream, output: OutputStream,
      limit: Int = SlideshowDecodeBudget.MAX_ENCODED_BYTES, current: () -> Boolean): Boolean {
    if (limit <= 0 || !current()) return false
    val cap = minOf(limit, SlideshowDecodeBudget.MAX_ENCODED_BYTES)
    val buffer = ByteArray(minOf(16 * 1024, cap))
    var written = 0
    while (current()) {
      var count = input.read(buffer, 0, minOf(buffer.size, cap - written + 1))
      if (!current()) return false
      if (count == 0) {
        val byte = input.read()
        if (!current()) return false
        if (byte < 0) return written > 0
        buffer[0] = byte.toByte()
        count = 1
      }
      if (count < 0) return written > 0 && current()
      if (count > cap - written) return false
      output.write(buffer, 0, count)
      written += count
    }
    return false
  }
}
