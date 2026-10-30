/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** One running command and one latest pending snapshot; a blocked player never blocks its caller. */
internal class ConflatedVideoCommands<T>(
    private val post: (Runnable) -> Boolean,
    private val consume: (T) -> Unit,
) {
  private class Value<T>(val value: T)
  private val lock = Any()
  private var latest: Value<T>? = null
  private var queued = false
  private val drain = Runnable {
    val next = synchronized(lock) {
      queued = false
      latest.also { latest = null }
    }
    // Native release/data-source calls may block. Never hold the admission lock while consuming.
    if (next != null) consume(next.value)
  }

  fun offer(value: T): Boolean = synchronized(lock) {
    latest = Value(value)
    if (queued) true else {
      queued = true
      if (post(drain)) true else {
        queued = false
        latest = null
        false
      }
    }
  }
}
