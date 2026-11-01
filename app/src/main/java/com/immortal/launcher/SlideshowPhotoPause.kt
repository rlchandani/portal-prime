/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread admission of decoded photos. User pause defers only automatic scenes. */
internal class SlideshowPhotoPause<T : Any>(
    private val onHeldChanged: (T?) -> Unit,
    private val onDiscard: (T) -> Unit,
) : AutoCloseable {
  var pending = false
    private set
  var automaticPending = false
    private set
  private class Delivery<T>(val value: T, val present: (T) -> Unit)
  private var held: Delivery<T>? = null
  private var paused = false
  private var hostActive = true
  private var closed = false

  fun requested(automatic: Boolean) {
    check(!closed)
    cancel()
    pending = true
    automaticPending = automatic
  }

  /** Explicit navigation starts at the accepted scene, not an unseen automatic cursor. */
  fun beforeManualNavigation(cancelRequest: () -> Unit) {
    if (automaticPending) cancelRequest()
  }

  fun deliver(value: T, present: (T) -> Unit) {
    if (closed) { onDiscard(value); return }
    if (!hostActive || (paused && automaticPending)) {
      held?.let { onDiscard(it.value) }
      held = Delivery(value, present)
      onHeldChanged(value)
      return
    }
    pending = false
    automaticPending = false
    present(value)
  }

  /** True when this change presents a held photo and its callback establishes a fresh dwell. */
  fun setPaused(value: Boolean): Boolean {
    paused = value
    return presentHeld()
  }

  fun setHostActive(value: Boolean) {
    // An explicit Resume under a night/host cover must wait for its reveal too.
    hostActive = value
    presentHeld()
  }

  private fun presentHeld(): Boolean {
    if (closed || !hostActive || (paused && automaticPending)) return false
    val delivery = held ?: return false
    held = null
    pending = false
    automaticPending = false
    // The receiving stage must own its pixels before the paused owner lets go.
    try { delivery.present(delivery.value) } finally { onHeldChanged(null) }
    return true
  }

  fun cancel() {
    pending = false
    automaticPending = false
    val delivery = held ?: return
    held = null
    // Do not admit new decoder scratch until these retained pixels have been freed.
    try { onDiscard(delivery.value) } finally { onHeldChanged(null) }
  }

  override fun close() {
    closed = true
    cancel()
  }
}
