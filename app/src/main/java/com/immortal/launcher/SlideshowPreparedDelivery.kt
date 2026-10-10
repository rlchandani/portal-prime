/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * One owned, unpublished scene and one Main callback. Closing drains pixels even if a paused
 * callback queue never dispatches. Main admission/close must share the same dispatch thread.
 * Admission transfers ownership to the receiver; discard only sees never-admitted resources.
 */
internal class SlideshowPreparedDelivery<T : Any>(
    private val postMain: (() -> Unit) -> Boolean,
    private val discard: (T) -> Unit,
    private val admit: (T) -> Unit,
) : AutoCloseable {
  private val lock = Any()
  private var closed = false
  private var ready: T? = null
  private var posted: Any? = null

  fun publish(value: T) {
    var old: T? = null
    var rejected = false
    val token = synchronized(lock) {
      if (closed) { rejected = true; null }
      else {
        old = ready
        ready = value
        if (posted != null) null else Any().also { posted = it }
      }
    }
    old?.let(discard)
    if (rejected) { discard(value); return }
    if (token != null) {
      val accepted = runCatching { postMain { deliver(token) } }.getOrDefault(false)
      if (!accepted) {
        val dropped = synchronized(lock) {
          if (posted !== token) null
          else { posted = null; ready.also { ready = null } }
        }
        dropped?.let(discard)
      }
    }
  }

  private fun deliver(token: Any) {
    val value = synchronized(lock) {
      if (closed || posted !== token) return
      posted = null
      ready.also { ready = null }
    } ?: return
    admit(value)
  }

  override fun close() {
    val value = synchronized(lock) {
      closed = true
      posted = null
      ready.also { ready = null }
    }
    value?.let(discard)
  }
}
