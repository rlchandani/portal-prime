/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Retain one lazy native photo engine across layouts; pause hidden work and close once. */
internal class MusicPhotoRetention<T : Any>(
    private val create: () -> T,
    private val start: (T) -> Unit,
    private val setActive: (T, Boolean) -> Unit,
    private val stop: (T) -> Unit,
) {
  private var value: T? = null
  private var started = false
  private var visible = false
  private var closed = false

  fun frame(): T {
    check(!closed)
    return value ?: create().also { value = it }
  }

  fun start() {
    check(!closed)
    if (started) return
    val frame = frame()
    start(frame)
    started = true
    setActive(frame, visible)
  }

  fun setVisible(value: Boolean) {
    if (closed || visible == value) return
    visible = value
    if (started) this.value?.let { setActive(it, value) }
  }

  fun close() {
    if (closed) return
    closed = true
    value?.let(stop)
    value = null
  }
}
