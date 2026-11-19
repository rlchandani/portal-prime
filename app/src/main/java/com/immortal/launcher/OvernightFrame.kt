/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What the photo frame shows when it opens, given the overnight window (pure, unit-tested).
 * Inside the window the screen goes dark unless the night clock is on; the night clock is the
 * clock-only frame, or with the "Night photo" colour, dim warm photos with a small clock.
 */
internal enum class OvernightFrame(val nightClock: Boolean, val clockOnly: Boolean) {
  DAY(nightClock = false, clockOnly = false),
  SLEEP(nightClock = false, clockOnly = false),
  NIGHT_CLOCK(nightClock = true, clockOnly = true),
  NIGHT_PHOTO(nightClock = true, clockOnly = false);

  companion object {
    fun of(inWindow: Boolean, nightClock: Boolean, tone: String): OvernightFrame = when {
      !inWindow -> DAY
      !nightClock -> SLEEP
      tone == ScreensaverConfig.NIGHT_TONE_PHOTO -> NIGHT_PHOTO
      else -> NIGHT_CLOCK
    }
  }
}
