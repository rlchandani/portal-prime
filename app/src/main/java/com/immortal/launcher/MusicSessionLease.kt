/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread ownership policy; remote polling extends one lease, never adds listeners. */
internal class MusicSessionLease(private val remoteLifetimeMs: Long) {
  private var uiOwners = 0
  private var remoteUntilMs = 0L

  init { require(remoteLifetimeMs > 0) }

  fun retainUi() { uiOwners++ }
  fun releaseUi() { if (uiOwners > 0) uiOwners-- }
  fun touchRemote(nowMs: Long) {
    remoteUntilMs = maxOf(remoteUntilMs, nowMs + remoteLifetimeMs)
  }
  fun shouldObserve(nowMs: Long) = uiOwners > 0 || nowMs < remoteUntilMs
  fun idleDelayMs(nowMs: Long): Long? =
      if (uiOwners > 0) null else (remoteUntilMs - nowMs).coerceAtLeast(0)
}
