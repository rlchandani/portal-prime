/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** One running legacy job plus one latest queued frame, reusing slideshow cancellation. */
internal class FaceFocusWork : AutoCloseable {
  @Volatile var request = FaceFocusRequest()
    private set
  private var started = false
  private var enabled = true
  private var finished = false
  private var closed = false
  private var pending: ((FaceFocusRequest) -> Unit)? = null

  fun start(block: (FaceFocusRequest) -> Unit): Boolean = synchronized(lock) {
    if (started || closed) return false
    started = true
    pending = block
    if (enabled) submitLocked(block)
    true
  }

  /** Activity changes cancel promptly; SDK disposal and transport cleanup stay off the caller. */
  fun setActive(value: Boolean) = synchronized(lock) {
    if (closed || enabled == value) return
    enabled = value
    if (!value) {
      request.cancel()
      if (active === this) {
        active = null
        worker.submit { }
      }
    } else if (!finished) {
      request = FaceFocusRequest()
      pending?.let { submitLocked(it) }
    }
    Unit
  }

  private fun submitLocked(block: (FaceFocusRequest) -> Unit) {
    // A visible host may supersede another retained host without permanently closing it.
    active?.request?.cancel()
    active = this
    val captured = request
    worker.submit {
      try {
        if (captured.current()) block(captured)
      } finally {
        synchronized(lock) {
          if (request === captured && !closed && captured.current()) {
            finished = true
            pending = null // Normal completion or a current failure must not restart on wake.
          }
          captured.cancel()
        }
        captured.awaitCleanup() // No replacement transport while prior cleanup is stuck.
        synchronized(lock) {
          // A paused task may finalize after resume has installed another request on this handle.
          if (active === this && request === captured) active = null
        }
      }
    }
  }

  /** Called under the shared lock, without disturbing another owner's worker submission. */
  private fun retireLocked() {
    closed = true
    pending = null
    request.cancel()
  }

  override fun close() = synchronized(lock) {
    if (closed) return
    retireLocked()
    if (active === this) {
      active = null
      worker.submit { } // Interrupt the running job and discard its queued replacement.
    }
    Unit
  }

  private companion object {
    val lock = Any()
    val worker = SlideshowWork()
    var active: FaceFocusWork? = null
  }
}
