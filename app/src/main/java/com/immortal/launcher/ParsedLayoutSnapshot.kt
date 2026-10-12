/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Reuses a pure, immutable layout decode; callers still read the current preference each time. */
internal class ParsedLayoutSnapshot<T>(
    private val emptyValue: T,
    private val decode: (String) -> T,
    private val maxRawChars: Int = 32_768,
) {
  init {
    require(maxRawChars > 0)
  }

  private data class Entry<T>(val raw: String, val value: T)
  private val lock = Any()
  private var entry: Entry<T>? = null

  fun read(raw: String?): T {
    if (raw == null) return synchronized(lock) {
      entry = null
      emptyValue
    }
    // Large layouts keep their existing behavior without retaining an unbounded payload.
    if (raw.length > maxRawChars) return decode(raw)
    return synchronized(lock) {
      val current = entry
      if (current?.raw == raw) current.value else {
        val decoded = decode(raw)
        entry = Entry(raw, decoded)
        decoded
      }
    }
  }
}
