/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A cancelled queued command cannot run; an already-started command has an uncertain outcome. */
internal class FramePerformanceCommandGate {
  private var began = false
  private var cancelled = false
  @Synchronized fun begin(): Boolean {
    if (cancelled) return false
    began = true
    return true
  }
  @Synchronized fun cancelBeforeBegin(): Boolean {
    if (began) return false
    cancelled = true
    return true
  }
}

/** The HTTP client must inspect state rather than retry an action that may already have run. */
internal class FramePerformanceCommandPending : RuntimeException()
