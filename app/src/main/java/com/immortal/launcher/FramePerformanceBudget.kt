/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.roundToLong

internal object FramePerformanceBudget {
  fun nanos(attachedHz: Double?, windowManagerHz: Double?): Long {
    fun valid(hz: Double?) = hz?.takeIf { it.isFinite() && it in 5.0..240.0 }
    val hz = valid(attachedHz) ?: valid(windowManagerHz) ?: 60.0
    return (1_000_000_000.0 / hz).roundToLong()
  }
}
