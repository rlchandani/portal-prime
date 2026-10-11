/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SettingsPaneWorkMeasurement { fun record(durationNs: Long) }
internal fun interface SettingsPaneWorkSpan { fun finish() }

/** Optional inclusive wall-time observations must never control execution or retain UI references. */
internal class SettingsPaneWorkProbe(
    private val currentMeasurement: (SettingsPaneWorkId, Boolean, Int, Int, Int, Int) -> SettingsPaneWorkMeasurement?,
    private val nanoTime: () -> Long,
) {
  fun begin(
    pane: SettingsPaneWorkId,
    active: Boolean,
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int,
  ): SettingsPaneWorkSpan? {
    val measurement = runCatching {
      currentMeasurement(pane, active, minWidth, maxWidth, minHeight, maxHeight)
    }.getOrNull() ?: return null
    val began = runCatching(nanoTime).getOrNull() ?: return null
    val finished = AtomicBoolean(false)
    return SettingsPaneWorkSpan {
      if (finished.compareAndSet(false, true)) {
        runCatching { measurement.record(nanoTime() - began) }
      }
    }
  }
}
