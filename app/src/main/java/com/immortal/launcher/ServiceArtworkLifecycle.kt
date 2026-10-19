/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Owns a service's artwork jobs and posted callbacks. Connection generations reject old
 * producers; request generations allow only the latest artwork to publish. Values returned
 * by [submit] are borrowed (for example, cached bitmaps), so they are never disposed here.
 *
 * Posting and closing share a lock: a worker cannot enqueue after teardown clears the queue.
 * Fetching runs outside the lock, since shutdownNow may not interrupt a network read.
 */
internal class ServiceArtworkLifecycle(
    private val executor: ExecutorService,
    private val enqueue: (Runnable) -> Unit,
    private val clearPosted: () -> Unit,
) {
  private val lock = Any()
  private var closed = false
  private var generation = 0L
  private var request = 0L

  fun nextGeneration(): Long? = synchronized(lock) {
    if (closed) return@synchronized null
    request++
    ++generation
  }

  fun post(generation: Long, action: () -> Unit) = synchronized(lock) {
    if (!current(generation)) return@synchronized
    enqueue(Runnable {
      synchronized(lock) { if (current(generation)) action() }
    })
  }

  fun <T> submit(load: () -> T, apply: (T) -> Unit): Boolean = synchronized(lock) {
    if (closed) return@synchronized false
    val owner = generation
    val ticket = ++request
    try {
      executor.execute {
        if (!synchronized(lock) { current(owner, ticket) }) return@execute
        val value = load()
        synchronized(lock) {
          if (current(owner, ticket)) {
            enqueue(Runnable {
              synchronized(lock) { if (current(owner, ticket)) apply(value) }
            })
          }
        }
      }
      true
    } catch (_: RejectedExecutionException) {
      // A callback racing an executor shutdown must not crash its caller.
      false
    }
  }

  fun close(): Unit = synchronized(lock) {
    if (closed) return@synchronized
    closed = true
    generation++
    request++
    clearPosted()
    executor.shutdownNow()
    Unit
  }

  // Called only while holding lock.
  private fun current(owner: Long, ticket: Long? = null): Boolean =
      !closed && owner == generation && (ticket == null || ticket == request)
}
