/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * When the next photo's lookahead is prepared. The scene just published is about to dissolve in;
 * decoding the following photo and uploading it as a texture at the same moment put that upload on
 * the render thread in the middle of the dissolve (Family Portal+, 2026-11-21: 3-6 frames of
 * 64-108 ms at each change). Waiting until the dissolve has drawn keeps every visible thing the
 * same: the lookahead is only a cache, and it is still ready long before the next change.
 */
internal object SlideshowLookahead {
  /** Past the longest day dissolve (the split glance's 1.8 s; classic is 0.9 s). */
  const val SETTLE_MS = SlideshowStage.SPLIT_DISSOLVE_MS + 200L
  private const val STEP_MS = 100L

  /**
   * Waits [ms] in short steps while [current] holds. False when the request went stale or the
   * worker was interrupted (a newer request cancels it), so no lookahead is prepared for it.
   */
  fun awaitSettle(ms: Long, current: () -> Boolean, sleep: (Long) -> Unit = Thread::sleep): Boolean {
    var left = ms
    while (left > 0) {
      if (!current()) return false
      val step = minOf(STEP_MS, left)
      try {
        sleep(step)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
      left -= step
    }
    return current()
  }
}
