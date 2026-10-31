/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicBoolean

/** Register before connect/read; retirement invalidates immediately and closes off Main. */
internal class EnginePhotoTransports(private val dispatch: (() -> Unit) -> Unit) : AutoCloseable {
  private val lock = Any()
  private val cancellations = HashSet<Lease>()
  private var closed = false

  inner class Lease internal constructor(internal val cancel: () -> Unit) : AutoCloseable {
    private val active = AtomicBoolean(true)
    fun isCurrent(): Boolean = active.get()
    internal fun invalidate() { active.set(false) }
    override fun close() = synchronized(lock) {
      invalidate()
      cancellations.remove(this)
      Unit
    }
  }

  fun register(cancel: () -> Unit): Lease? {
    val lease = Lease(cancel)
    val accepted = synchronized(lock) { if (closed) false else cancellations.add(lease) }
    if (!accepted) {
      runCatching { dispatch { runCatching(cancel) } }
      return null
    }
    return lease
  }

  fun cancelCurrent() = invalidate(close = false)
  override fun close() = invalidate(close = true)

  private fun invalidate(close: Boolean) {
    val snapshot = synchronized(lock) {
      if (close) closed = true
      cancellations.toList().also { leases ->
        leases.forEach { it.invalidate() }
        cancellations.clear()
      }
    }
    if (snapshot.isNotEmpty()) dispatch { snapshot.forEach { runCatching(it.cancel) } }
  }
}
