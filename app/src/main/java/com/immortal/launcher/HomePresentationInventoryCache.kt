/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Loads outside the lock: package broadcasts never wait for bitmap rendering or binder IO. */
internal class HomePresentationInventoryCache<T> {
  private val lock = Any()
  private var generation = 0L
  @Volatile private var snapshot: List<T>? = null

  fun peek(): List<T>? = snapshot

  fun invalidate() = synchronized(lock) {
    generation++
    snapshot = null
  }

  /** Caller runs on IO. An invalidated in-flight load cannot republish stale app identities. */
  fun load(loader: () -> List<T>): List<T> {
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
