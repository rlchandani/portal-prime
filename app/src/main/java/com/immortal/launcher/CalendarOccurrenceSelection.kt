/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.PriorityQueue

/** Per-evaluation, stable earliest-occurrence selection with deferred construction. */
internal class CalendarOccurrenceSelection<T>(private val limit: Int) {
  private data class Entry<T>(val startMillis: Long, val order: Long, val value: T)
  private val entries = PriorityQueue<Entry<T>>(limit,
      compareBy<Entry<T>> { it.startMillis }.thenBy { it.order }.reversed())
  private var nextOrder = 0L

  init { require(limit > 0) }

  // Inline avoids allocating a capturing factory for every discarded occurrence.
  inline fun offer(startMillis: Long, create: () -> T) {
    if (accepts(startMillis)) retain(startMillis, create())
  }

  @PublishedApi
  internal fun accepts(startMillis: Long): Boolean =
      entries.size < limit || startMillis < entries.peek().startMillis

  @PublishedApi
  internal fun retain(startMillis: Long, value: T) {
    if (entries.size == limit) entries.remove()
    entries.add(Entry(startMillis, nextOrder++, value))
  }

  fun result(): List<T> = entries.sortedWith(compareBy<Entry<T>> { it.startMillis }
      .thenBy { it.order }).map { it.value }
}
