/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A single-threaded scheduled executor on a virtual clock: nothing runs until the test calls
 * [runDue] or [advance], and then everything runs on the test's own thread, in due-time order
 * (ties in submission order). Lets engine tests step through heartbeats, backoff and batching
 * without sleeping.
 */
internal class VirtualScheduler(var now: Long = 0L) : AbstractExecutorService(), ScheduledExecutorService {
  private inner class Task(var due: Long, val period: Long, val body: Runnable) : ScheduledFuture<Any?> {
    val seq = nextSeq++
    var cancelled = false
    var done = false
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean { if (done) return false; cancelled = true; done = true; return true }
    override fun isCancelled() = cancelled
    override fun isDone() = done
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: TimeUnit): Any? = null
    override fun getDelay(unit: TimeUnit) = unit.convert(due - now, TimeUnit.MILLISECONDS)
    override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
  }

  private var nextSeq = 0L
  private val tasks = ArrayList<Task>()

  /** Tasks waiting (not cancelled), for assertions. */
  val pending: Int get() = tasks.count { !it.done }

  /** Delays (ms from now) of the waiting tasks, soonest first. */
  fun pendingDelays(): List<Long> = tasks.filter { !it.done }.sortedWith(compareBy({ it.due }, { it.seq })).map { it.due - now }

  override fun execute(command: Runnable) { add(now, 0L, command) }

  override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
      add(now + unit.toMillis(delay), 0L, command)

  override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
      throw UnsupportedOperationException("not used by the engines")

  override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
      add(now + unit.toMillis(initialDelay), unit.toMillis(period), command)

  override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
      add(now + unit.toMillis(initialDelay), unit.toMillis(delay), command)

  /** Runs what is already queued (like a real single thread would first), then [task], at once. */
  override fun <T : Any?> submit(task: Callable<T>): java.util.concurrent.Future<T> {
    runDue()
    return FutureTask(task).also { it.run() }
  }

  private fun add(due: Long, period: Long, body: Runnable): Task = Task(due, period, body).also { tasks += it }

  /** Runs every task due at or before [now], including ones those tasks queue for now. */
  fun runDue() {
    while (true) {
      val next = tasks.filter { !it.done && it.due <= now }.minWithOrNull(compareBy({ it.due }, { it.seq })) ?: return
      runTask(next)
    }
  }

  /** Moves the clock forward by [ms], running each task at its own due time on the way. */
  fun advance(ms: Long) {
    val target = now + ms
    runDue()
    while (true) {
      val next = tasks.filter { !it.done && it.due <= target }.minWithOrNull(compareBy({ it.due }, { it.seq })) ?: break
      if (next.due > now) now = next.due
      runTask(next)
    }
    now = target
    runDue()
  }

  private fun runTask(t: Task) {
    tasks.remove(t)
    if (t.period > 0) { t.due = now + t.period; tasks += t } else t.done = true
    t.body.run()
  }

  override fun shutdown() = Unit
  override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
  override fun isShutdown() = false
  override fun isTerminated() = false
  override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
