/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RunnableFuture
import java.util.concurrent.TimeUnit

/**
 * The sync's worker and network threads replaced by one FIFO queue that only runs when the test
 * thread drains it. Nothing runs concurrently, so the order of everything recorded is decided by
 * the scenario alone, never by thread scheduling: a step's own (main-thread) work is always
 * recorded before the work it hands the worker, as on a Portal where the worker is a separate
 * thread that has not yet been scheduled.
 *
 * A blocking `submit(...).get()` (the peer route waiting on the worker) runs the queue on the
 * caller until its task is done, exactly what the real worker would have done first.
 */
internal class SyncGoldenQueue {
  private val tasks = ArrayDeque<Runnable>()
  private var running = false

  /** One of the sync's executors; all lanes share this queue's order. */
  fun lane(): SyncGoldenLane = SyncGoldenLane(this)

  fun enqueue(task: Runnable) { tasks.addLast(task) }

  /** Runs queued work, including work it queues, until nothing is left. */
  fun drain() { runUntil { false } }

  /** Runs queued work in order until [done]; fails if the queue runs dry first (a real deadlock). */
  fun runUntil(done: () -> Boolean) {
    check(!running) { "sync work waited on the sync queue from inside it (would deadlock on a Portal)" }
    running = true
    try {
      while (!done()) {
        val next = tasks.pollFirst() ?: break
        next.run()
      }
    } finally {
      running = false
    }
  }
}

internal class SyncGoldenLane(private val queue: SyncGoldenQueue) : AbstractExecutorService() {
  override fun execute(command: Runnable) = queue.enqueue(command)

  override fun <T> newTaskFor(callable: Callable<T>): RunnableFuture<T> = DrainingTask(callable)

  override fun <T> newTaskFor(runnable: Runnable, value: T): RunnableFuture<T> = DrainingTask(Callable { runnable.run(); value })

  /** Waiting on it drains the queue up to (and including) this task instead of blocking. */
  private inner class DrainingTask<T>(callable: Callable<T>) : FutureTask<T>(callable) {
    override fun get(): T { awaitByDraining(); return super.get() }

    override fun get(timeout: Long, unit: TimeUnit): T { awaitByDraining(); return super.get(timeout, unit) }

    private fun awaitByDraining() {
      if (isDone) return
      queue.runUntil { isDone }
      check(isDone) { "waited on sync work that was never queued" }
    }
  }

  override fun shutdown() = Unit
  override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
  override fun isShutdown() = false
  override fun isTerminated() = false
  override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
