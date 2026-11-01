/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * One-shot UI callbacks whose remaining delays stop while their owner is inactive.
 *
 * The default transport is the main Handler. The injectable constructor needs a monotonic
 * millisecond [clock], a [schedule] that queues wrappers (never runs them inline) and returns
 * whether they were accepted, and a [cancel] that removes that exact wrapper by reference.
 * Transport calls are serialized with queue changes; cancelling may still leave a detached
 * wrapper in flight. Each scheduling attempt owns a fresh wrapper, so such dispatches are inert.
 * The injected constructor does not create a Handler or call any Android API.
 *
 * Worker posts and all queue state are synchronized. Lifecycle calls should share the dispatch
 * thread (main in production): pause/close cannot interrupt a callback already claimed to run.
 * User callbacks run outside the lock and can post, remove, pause, or close this queue.
 */
internal class PausableFrameCallbacks(
    private val clock: () -> Long,
    private val schedule: (Runnable, Long) -> Boolean,
    private val cancel: (Runnable) -> Unit,
) {
  constructor() : this(Handler(Looper.getMainLooper()))

  private constructor(handler: Handler) : this(
      clock = { SystemClock.uptimeMillis() },
      schedule = { wrapper, delay -> handler.postDelayed(wrapper, delay) },
      cancel = { wrapper -> handler.removeCallbacks(wrapper) },
  )

  // Entries use identity equality: posting the same Runnable twice creates two independent tasks.
  private class Entry(
      val runnable: Runnable,
      var remainingMillis: Long,
      var onDiscard: (() -> Unit)?,
  ) {
    var scheduledAtMillis = 0L
    var wrapper: Runnable? = null
  }

  private val lock = Any()
  private val entries = LinkedHashSet<Entry>()
  private var active = true
  private var closed = false

  /** False while paused and permanently false after [close]. */
  val isActive: Boolean get() = synchronized(lock) { active }

  /** Scheduled plus paused entries; excludes a callback already claimed for execution. */
  val pendingCount: Int get() = synchronized(lock) { entries.size }

  fun post(runnable: Runnable): Boolean = postDelayed(runnable, 0L)

  /**
   * Transfers ownership when the callback is claimed; otherwise calls [onDiscard] exactly once.
   * Discards run outside the queue lock, including rejection by a closed queue or the transport.
   * Cleanup failures escape after all discards finish; transport failures remain the primary error.
   */
  fun postOwned(runnable: Runnable, onDiscard: () -> Unit): Boolean =
      postEntry(runnable, 0L, onDiscard)

  /** Paused posts are retained; a closed queue or a rejecting active transport returns false. */
  fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean =
      postEntry(runnable, delayMillis, null)

  private fun postEntry(
      runnable: Runnable,
      delayMillis: Long,
      onDiscard: (() -> Unit)?,
  ): Boolean = withDiscards { discarded ->
    synchronized(lock) {
      if (closed) {
        onDiscard?.let { discarded.add(it) }
        return@synchronized false
      }
      val entry = Entry(runnable, delayMillis.coerceAtLeast(0L), onDiscard)
      entries.add(entry)
      if (active) scheduleLocked(entry, discarded) else true
    }
  }

  /** Removes all pending posts of this exact Runnable, including retained paused entries. */
  fun removeCallbacks(runnable: Runnable): Unit {
    withDiscards { discarded ->
      synchronized(lock) {
        val removed = entries.filter { it.runnable === runnable }
        for (entry in removed) {
          entries.remove(entry)
          discardLocked(entry, discarded)
          cancelLocked(entry)
        }
      }
    }
  }

  fun setActive(value: Boolean): Unit {
    withDiscards { discarded ->
      synchronized(lock) {
        if (closed || active == value) return@synchronized
        active = value
        if (value) {
          // Scheduling may reject and remove entries. The snapshot preserves original enqueue order.
          for (entry in entries.toList()) scheduleLocked(entry, discarded)
        } else {
          val now = clock()
          for (entry in entries) {
            val elapsed = (now - entry.scheduledAtMillis).coerceAtLeast(0L)
            entry.remainingMillis = (entry.remainingMillis - elapsed).coerceAtLeast(0L)
            cancelLocked(entry)
          }
        }
      }
    }
  }

  /** Terminal: invalidate and cancel every wrapper, drop retained entries, and reject future posts. */
  fun close(): Unit {
    withDiscards { discarded ->
      synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        active = false
        val removed = entries.toList()
        entries.clear()
        for (entry in removed) discardLocked(entry, discarded)
        for (entry in removed) cancelLocked(entry)
      }
    }
  }

  private fun discardLocked(entry: Entry, discarded: MutableList<() -> Unit>) {
    entry.onDiscard?.let { discarded.add(it) }
    entry.onDiscard = null
  }

  // Complete the queue mutation before calling resource owners. Drain every discard even when one
  // throws; preserve a transport failure as primary and attach cleanup failures as suppressed.
  private fun <T> withDiscards(action: (MutableList<() -> Unit>) -> T): T {
    val discarded = mutableListOf<() -> Unit>()
    val result = runCatching { action(discarded) }
    var failure = result.exceptionOrNull()
    for (discard in discarded) {
      try {
        discard()
      } catch (error: Throwable) {
        val previous = failure
        if (previous == null) failure = error
        else if (previous !== error) previous.addSuppressed(error)
      }
    }
    failure?.let { throw it }
    return result.getOrThrow()
  }

  // Transport enqueue/cancel stay inside the lock so a late worker enqueue cannot escape a pause,
  // removal, or close that has already cancelled its wrapper. Handler operations never run it inline.
  private fun scheduleLocked(entry: Entry, discarded: MutableList<() -> Unit>): Boolean {
    val wrapper = object : Runnable {
      override fun run() = dispatch(entry, this)
    }
    entry.scheduledAtMillis = clock()
    entry.wrapper = wrapper
    val accepted = try {
      schedule(wrapper, entry.remainingMillis)
    } catch (error: Throwable) {
      entry.wrapper = null
      entries.remove(entry)
      discardLocked(entry, discarded)
      throw error
    }
    if (!accepted) {
      entry.wrapper = null
      entries.remove(entry)
      discardLocked(entry, discarded)
    }
    // Rejection (e.g. a quitting Looper) drops this entry; it is never silently replayed later.
    return accepted
  }

  private fun cancelLocked(entry: Entry) {
    val wrapper = entry.wrapper
    entry.wrapper = null
    if (wrapper != null) cancel(wrapper)
  }

  private fun dispatch(entry: Entry, wrapper: Runnable) {
    val runnable = synchronized(lock) {
      // This is the last active check before user code, including already-dequeued callbacks.
      if (closed || !active || entry.wrapper !== wrapper) return
      if (!entries.remove(entry)) return
      entry.wrapper = null
      entry.onDiscard = null
      entry.runnable
    }
    runnable.run()
  }
}
