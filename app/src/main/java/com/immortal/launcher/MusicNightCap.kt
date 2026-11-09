/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.roundToInt

/** "Quieter music during quiet hours", as reported to the phone: null when the setting is off. */
internal data class MusicNightCap(val active: Boolean, val maxLevel: Int)

/**
 * Pure math for the night volume cap. The setting's level is on a fixed [SCALE] (Settings shows
 * "6 of 18") and maps proportionally onto the device's own STREAM_MUSIC steps. Only music is
 * capped: announcements play on STREAM_ALARM ([AnnouncementVolume]) and are never touched.
 */
internal object MusicNightCapMath {
  const val SCALE = 18
  const val MIN_LEVEL = 1
  const val DEFAULT_LEVEL = 6

  fun state(enabled: Boolean, quietNow: Boolean, maxLevel: Int): MusicNightCap? =
      if (!enabled) null else MusicNightCap(active = quietNow, maxLevel = clampLevel(maxLevel))

  fun clampLevel(level: Int): Int = level.coerceIn(MIN_LEVEL, SCALE)

  /** The device step for [level] of [SCALE]; never 0 (music stays audible) unless the device has no steps. */
  fun capStep(level: Int, deviceMax: Int): Int {
    if (deviceMax <= 0) return 0
    return (clampLevel(level) * deviceMax.toDouble() / SCALE).roundToInt().coerceIn(1, deviceMax)
  }

  /** The step to apply for a [requested] one: capped while [cap] is active, unchanged otherwise. */
  fun allowed(requested: Int, deviceMax: Int, cap: MusicNightCap?): Int {
    if (cap == null || !cap.active) return requested
    return requested.coerceAtMost(capStep(cap.maxLevel, deviceMax))
  }
}
