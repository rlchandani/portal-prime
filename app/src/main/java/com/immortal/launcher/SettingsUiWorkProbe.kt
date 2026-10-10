/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SettingsUiWorkMeasurement { fun record(durationNs: Long) }
internal fun interface SettingsUiWorkSpan { fun finish() }

/** Optional observations must never control UI execution or retain UI references. */
internal class SettingsUiWorkProbe(
    private val currentMeasurement: (SettingsUiWorkStage) -> SettingsUiWorkMeasurement?,
    private val nanoTime: () -> Long,
) {
  fun begin(stage: SettingsUiWorkStage): SettingsUiWorkSpan? {
    if (stage.event) return null
    val measurement = runCatching { currentMeasurement(stage) }.getOrNull() ?: return null
    val began = runCatching(nanoTime).getOrNull() ?: return null
    val finished = AtomicBoolean(false)
    return SettingsUiWorkSpan {
      if (finished.compareAndSet(false, true)) {
        runCatching { measurement.record(nanoTime() - began) }
      }
    }
  }

  fun mark(stage: SettingsUiWorkStage) {
    if (stage.event) runCatching { currentMeasurement(stage)?.record(0L) }
  }
}
