/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection

/** Bounded UTF-8 metadata only. Callers retain request policy and connection ownership. */
internal object StoreMetadataHttp {
  const val MAX_METADATA_BYTES = 4 * 1024 * 1024

  fun readText(connection: HttpURLConnection, maxBytes: Int = MAX_METADATA_BYTES): String {
    require(maxBytes >= 0)
    checkpoint()
    val length = connection.contentLengthLong
    checkpoint()
    if (length > maxBytes) throw IOException("Store metadata exceeds its response budget")
    return connection.inputStream.use { input ->
      val output = ByteArrayOutputStream(minOf(8192, maxBytes))
      val buffer = ByteArray(8192)
      while (true) {
        checkpoint()
        val remaining = maxBytes - output.size()
        if (remaining == 0) {
          // Probe EOF without retaining or consuming more than one byte beyond the budget.
          val byte = input.read()
          checkpoint()
          if (byte != -1) throw IOException("Store metadata exceeds its response budget")
          break
        }
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        checkpoint()
        if (count < 0) break
        if (count == 0) {
          // Some transports return zero from bulk reads; a single-byte read makes progress.
          val byte = input.read()
          checkpoint()
          if (byte < 0) break
          output.write(byte)
        } else output.write(buffer, 0, count)
      }
      checkpoint()
      if (length >= 0 && output.size().toLong() != length)
        throw IOException("Store metadata response is incomplete")
      output.toByteArray().toString(Charsets.UTF_8)
    }
  }

  private fun checkpoint() {
    if (Thread.currentThread().isInterrupted)
      throw InterruptedIOException("Store metadata read interrupted")
  }
}
