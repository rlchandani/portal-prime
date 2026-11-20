/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Reads a peer's reply without trusting it: at most [maxBytes] (checked while streaming, never
 * after buffering everything) and within a total time budget, so a hostile or broken Portal can't
 * make us hold an unbounded buffer or keep a sync thread busy by trickling bytes under the
 * per-read socket timeout. Every Portal↔Portal client reads replies through here.
 */
internal object BoundedRead {
  /** The whole reply must arrive within this, on top of the connection's per-read timeout. */
  const val DEFAULT_BUDGET_MS = 15_000L
  private const val CHUNK = 16 * 1024

  /** The bytes, or null when the stream is longer than [maxBytes] or outlasts [budgetMs]. */
  fun bytes(input: InputStream, maxBytes: Int, budgetMs: Long = DEFAULT_BUDGET_MS,
            clock: () -> Long = ::uptimeMs): ByteArray? {
    require(maxBytes >= 0)
    val deadline = clock() + budgetMs
    val out = ByteArrayOutputStream(minOf(maxBytes, CHUNK).coerceAtLeast(1))
    val buf = ByteArray(CHUNK)
    while (true) {
      // One byte past the cap is enough to know the stream is too long.
      val n = input.read(buf, 0, minOf(buf.size, maxBytes + 1 - out.size()))
      if (n < 0) return out.toByteArray()
      out.write(buf, 0, n)
      if (out.size() > maxBytes) return null
      if (clock() > deadline) return null
    }
  }

  /** [bytes] decoded as UTF-8. */
  fun utf8(input: InputStream, maxBytes: Int, budgetMs: Long = DEFAULT_BUDGET_MS,
           clock: () -> Long = ::uptimeMs): String? = bytes(input, maxBytes, budgetMs, clock)?.toString(Charsets.UTF_8)

  /**
   * A 2xx reply's body as UTF-8, or null. A declared `Content-Length` over the cap is refused
   * before reading a byte; an undeclared (chunked) one is still capped while streaming.
   */
  fun reply(conn: HttpURLConnection, maxBytes: Int, budgetMs: Long = DEFAULT_BUDGET_MS): String? {
    if (!declaredFits(conn.contentLengthLong, maxBytes)) return null
    return conn.inputStream.use { utf8(it, maxBytes, budgetMs) }
  }

  /** An error reply's body (for its `error` code), capped the same way; null if absent or too long. */
  fun errorReply(conn: HttpURLConnection, maxBytes: Int, budgetMs: Long = DEFAULT_BUDGET_MS): String? {
    if (!declaredFits(conn.contentLengthLong, maxBytes)) return null
    return conn.errorStream?.use { utf8(it, maxBytes, budgetMs) }
  }

  /** A declared length (-1 when absent) that a capped read could accept. */
  fun declaredFits(contentLength: Long, maxBytes: Int): Boolean = contentLength <= maxBytes

  /** Monotonic, so a wall-clock change can't stretch or cut the budget. */
  private fun uptimeMs(): Long = System.nanoTime() / 1_000_000
}
