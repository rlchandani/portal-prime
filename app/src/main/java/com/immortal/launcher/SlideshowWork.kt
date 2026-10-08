/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One active preparation plus one latest request, rather than an unbounded swipe backlog. */
internal class SlideshowWork : AutoCloseable {
  private val version = AtomicLong()
  @Volatile private var closed = false
  private var active: Future<*>? = null
  private val executor = ThreadPoolExecutor(
      1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
      { runnable -> Thread(runnable, "slideshow-images").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
      } })

  @Synchronized fun submit(block: (() -> Boolean) -> Unit): Long {
    if (closed) return -1
    val ticket = version.incrementAndGet()
    active?.cancel(true)
    executor.queue.clear()
    active = executor.submit {
      val current = { !closed && version.get() == ticket && !Thread.currentThread().isInterrupted }
      if (current()) block(current)
    }
    return ticket
  }

  @Synchronized override fun close() {
    if (closed) return
    closed = true
    version.incrementAndGet()
    active?.cancel(true)
    executor.shutdownNow()
  }
}
