/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SlideshowWorkMeasurement { fun record(durationNs: Long) }
internal fun interface SlideshowWorkSpan { fun finish() }

/**
 * Optional diagnostics must never control slideshow execution or retain rendering/source data.
 * A selected measurement owns capture admission and thread attribution; finish never reselects it.
 */
internal class SlideshowWorkProbe(
    private val currentMeasurement: (SlideshowWorkStage) -> SlideshowWorkMeasurement?,
    private val nanoTime: () -> Long,
) {
  fun begin(stage: SlideshowWorkStage): SlideshowWorkSpan? {
    if (stage.event) return null
    val measurement = runCatching { currentMeasurement(stage) }.getOrNull() ?: return null
    val began = runCatching(nanoTime).getOrNull() ?: return null
    val finished = AtomicBoolean(false)
    return SlideshowWorkSpan {
      if (finished.compareAndSet(false, true)) {
        // Clock/sink failures, including a finally-block observation, cannot mask real work failures.
        runCatching { measurement.record(nanoTime() - began) }
      }
    }
  }

  fun mark(stage: SlideshowWorkStage) {
    if (stage.event) runCatching { currentMeasurement(stage)?.record(0L) }
  }
}
