/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Admission for one retained renderer. Lifecycle changes run on the main thread; worker threads
 * may check their token before fetching/posting. A hide invalidates work even if the host resumes
 * before its result arrives. Stopping permanently closes admission.
 */
internal class FaceRendererWorkState {
  @Volatile private var started = false
  @Volatile private var generation = 0L
  @Volatile private var activeHost = true
  @Volatile private var stopped = false

  val hostActive: Boolean
    get() = activeHost
  val isStopped: Boolean
    get() = stopped

  val isActive: Boolean
    get() = started && activeHost && !stopped

  fun start(): Boolean {
    if (started || stopped) return false
    generation++
    started = true
    return true
  }

  fun setHostActive(active: Boolean): Boolean {
    if (stopped || activeHost == active) return false
    generation++
    activeHost = active
    return true
  }

  fun stop(): Boolean {
    if (stopped) return false
    stopped = true
    generation++
    return true
  }

  fun token(): Long? = if (isActive) generation else null

  fun isCurrent(token: Long): Boolean = isActive && generation == token
}
