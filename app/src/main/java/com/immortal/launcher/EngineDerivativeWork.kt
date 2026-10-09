/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One decoder across frame owners; bounded requests, idle retirement and non-blocking exit. */
internal class EngineDerivativeWork(queueCapacity: Int = 64) : AutoCloseable {
  private val stopped = AtomicBoolean(false)
  private val executor = ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS,
      ArrayBlockingQueue<Runnable>(queueCapacity), { task ->
        Thread(task, "ss-scaled-cache").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
      }, ThreadPoolExecutor.AbortPolicy())

  fun owner(): Owner = Owner()

  inner class Owner internal constructor() : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val keys = HashSet<String>()

    fun submit(key: String, block: (() -> Boolean) -> Unit): Boolean = synchronized(keys) {
      if (!current() || !keys.add(key)) return false
      try {
        executor.execute(Job(this, key, block))
        true
      } catch (_: RejectedExecutionException) {
        keys.remove(key)
        false
      }
    }

    internal fun current(): Boolean = !closed.get() && !stopped.get()
    internal fun completed(key: String) { synchronized(keys) { keys.remove(key) } }

    override fun close() {
      synchronized(keys) {
        if (!closed.compareAndSet(false, true)) return
        // The running decoder owns its bitmap until completion. Never join or interrupt it here.
        for (job in executor.queue.toTypedArray()) {
          if (job is Job && job.owner === this && executor.remove(job)) keys.remove(job.key)
        }
      }
    }
  }

  private class Job(val owner: Owner, val key: String,
      private val block: (() -> Boolean) -> Unit) : Runnable {
    override fun run() {
      try { if (owner.current()) block(owner::current) }
      finally { owner.completed(key) }
    }
  }

  override fun close() { stopped.set(true); executor.shutdownNow() }
}
