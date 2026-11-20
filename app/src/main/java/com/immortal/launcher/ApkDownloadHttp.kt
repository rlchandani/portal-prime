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
import java.net.URI

internal object ApkDownloadHttp {
  /** No APK we install comes close; a declared or streamed body past it is refused. */
  const val MAX_BYTES = 512L * 1024 * 1024
  /** Whole-download budget on top of the per-read timeout, so a trickling host can't stall forever. */
  const val BUDGET_MS = 20L * 60 * 1000
  const val MAX_REDIRECTS = 5
  val REDIRECTS = setOf(301, 302, 303, 307, 308)

  /**
   * The next hop of a redirect, or null when refused: a missing target, an https -> http downgrade,
   * or (with [allowedHosts]) a host outside the list. Pure.
   */
  fun nextHop(current: String, location: String?, allowedHosts: Set<String>?): String? {
    if (location.isNullOrBlank()) return null
    if (allowedHosts != null) return ApkUrlPolicy.nextHop(current, location, allowedHosts)
    val next = runCatching { URI(current).resolve(location.trim()) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
    val scheme = next.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return null
    if (current.startsWith("https:", ignoreCase = true) && scheme == "http") return null
    return next.toString()
  }

  /** Publishes only a nonempty, complete body, retaining the caller's connection policy. */
  fun download(
      connection: HttpURLConnection,
      destination: File,
      maxBytes: Long = MAX_BYTES,
      budgetMs: Long = BUDGET_MS,
      clock: () -> Long = { System.nanoTime() / 1_000_000 },
  ): Long {
    val deadline = clock() + budgetMs
    try {
      checkInterrupted()
      val expected = connection.contentLengthLong
      checkInterrupted()
      if (expected == 0L) throw IOException("APK response is empty")
      if (expected > maxBytes) throw IOException("APK is larger than $maxBytes bytes")

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
            if (total + count > maxBytes) throw IOException("APK is larger than $maxBytes bytes")
            if (clock() > deadline) throw IOException("APK download outlasted its time budget")
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
