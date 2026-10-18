/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Owns one asynchronous speech engine, including callbacks delivered before construction returns.
 * Terminal callbacks may race or reenter submission; cleanup waits for submission to leave the
 * engine, then runs once. Client callbacks run outside the state lock.
 */
internal class OneShotSpeechOwner<E : Any>(
    private val ready: (E) -> Unit,
    private val release: (E) -> Unit,
) {
  private val lock = Any()
  private var engine: E? = null
  private var attached = false
  private var initialized: Boolean? = null
  private var delivered = false
  private var delivering = false
  private var finished = false

  fun created(engine: E) {
    synchronized(lock) {
      check(!attached) { "A one-shot owner accepts only one engine" }
      attached = true
      this.engine = engine
    }
    dispatch()
  }

  fun initialized(success: Boolean) {
    synchronized(lock) {
      if (initialized != null) return
      initialized = success
      if (!success) finished = true
    }
    dispatch()
  }

  fun submitted(success: Boolean) {
    if (!success) finish()
  }

  fun finish() {
    synchronized(lock) { finished = true }
    dispatch()
  }

  private fun dispatch() {
    val action = synchronized(lock) {
      val current = engine ?: return
      if (delivering) return
      when {
        finished -> {
          engine = null
          current to false
        }
        initialized == true && !delivered -> {
          delivered = true
          delivering = true
          current to true
        }
        else -> return
      }
    }
    if (!action.second) {
      runCatching { release(action.first) }
      return
    }
    try {
      ready(action.first)
    } catch (_: Throwable) {
      finish()
    } finally {
      synchronized(lock) { delivering = false }
      dispatch()
    }
  }
}
