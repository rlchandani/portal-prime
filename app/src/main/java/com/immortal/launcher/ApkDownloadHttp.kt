/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection

internal object ApkDownloadHttp {
  /** Publishes only a nonempty, complete body, retaining the caller's connection policy. */
  fun download(connection: HttpURLConnection, destination: File): Long {
    try {
      checkInterrupted()
      val expected = connection.contentLengthLong
      checkInterrupted()
      if (expected == 0L) throw IOException("APK response is empty")

      return FleetUploadWriter.write(destination) { output ->
        // The input must close inside the callback, before the writer can publish.
        val written = connection.inputStream.use { input ->
          checkInterrupted()
          val buffer = ByteArray(8192)
          var total = 0L
          while (true) {
            checkInterrupted()
            var count = input.read(buffer)
            checkInterrupted()
            if (count == 0) {
              // Some streams return zero from a bulk read. A single-byte read advances them.
              val next = input.read()
              checkInterrupted()
              if (next < 0) break
              buffer[0] = next.toByte()
              count = 1
            }
            if (count < 0) break
            if (total > Long.MAX_VALUE - count.toLong()) {
              throw IOException("APK byte count overflow")
            }
            if (expected >= 0L && count.toLong() > expected - total) {
              throw IOException("APK body exceeds declared length of $expected bytes")
            }
            output.write(buffer, 0, count)
            total += count.toLong()
          }
          if (total == 0L) throw IOException("APK response is empty")
          if (expected >= 0L && total != expected) {
            throw IOException("APK length mismatch: expected $expected bytes, received $total")
          }
          total
        }
        checkInterrupted()
        written
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) {
      throw InterruptedIOException("APK download interrupted")
    }
  }
}
