/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The measurement never receives search contents or the prepared collection. */
internal fun interface SettingsSearchPreparationMeasurement {
  fun record(durationNs: Long, entryCount: Int)
}

/** Preparation remains exactly once; optional diagnostics cannot change its result. */
internal class SettingsSearchPreparationProbe(
    private val currentMeasurement: () -> SettingsSearchPreparationMeasurement?,
    private val nanoTime: () -> Long,
) {
  fun <T> prepare(block: () -> List<T>): List<T> {
    val measurement = runCatching(currentMeasurement).getOrNull() ?: return block()
    val began = runCatching(nanoTime).getOrNull() ?: return block()
    val prepared = block()
    runCatching { measurement.record(nanoTime() - began, prepared.size) }
    return prepared
  }
}
