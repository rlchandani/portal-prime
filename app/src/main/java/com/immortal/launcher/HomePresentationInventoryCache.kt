/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** One scan at a time; package broadcasts never wait for bitmap rendering or binder IO. */
internal class HomePresentationInventoryCache<T> {
  private val lock = Any()
  private val loadingLock = Any()
  private var generation = 0L
  @Volatile private var snapshot: List<T>? = null

  fun peek(): List<T>? = snapshot

  fun invalidate() = synchronized(lock) {
    generation++
    snapshot = null
  }

  /** Expiry must never discard a replacement published after its first observation. */
  fun invalidateIfCurrent(expected: List<T>): Boolean = synchronized(lock) {
    if (snapshot !== expected) return@synchronized false
    generation++
    snapshot = null
    true
  }

  /** Caller runs on IO. An invalidated in-flight load cannot republish stale app identities. */
  fun load(loader: () -> List<T>): List<T> {
    snapshot?.let { return it }
    // Home and Apps may request the same cold inventory before either has finished.
    // The separate flight lock shares that work without blocking invalidate() or peek().
    synchronized(loadingLock) { return loadSnapshot(loader) }
  }

  private fun loadSnapshot(loader: () -> List<T>): List<T> {
    while (true) {
      val version = synchronized(lock) {
        snapshot?.let { return it }
        generation
      }
      val prepared = loader().toList()
      synchronized(lock) {
        if (generation == version) {
          snapshot?.let { return it }
          snapshot = prepared
          return prepared
        }
      }
    }
  }
}
