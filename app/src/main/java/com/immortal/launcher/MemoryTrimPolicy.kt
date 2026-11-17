/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentCallbacks2

/**
 * How much decoded-image cache to keep for an onTrimMemory level, and when Music's player is
 * dropped early. Levels are not one severity scale: RUNNING_* arrive while we are visible,
 * UI_HIDDEN when our UI left the screen, BACKGROUND and above when the process is cached.
 */
internal object MemoryTrimPolicy {
  const val KEEP_ALL = 1f
  const val KEEP_HALF = .5f
  const val KEEP_QUARTER = .25f
  const val KEEP_NONE = 0f
  /** Music art kept once Music has been left and its player released (header and screensaver). */
  const val MUSIC_IDLE_ART_BYTES = 4 * 1024 * 1024

  /** Fraction of each image cache's budget kept after onTrimMemory([level]). */
  @Suppress("DEPRECATION")
  fun retainedFraction(level: Int): Float = when {
    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> KEEP_NONE
    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> KEEP_QUARTER
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> KEEP_NONE
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> KEEP_HALF
    else -> KEEP_ALL
  }

  /** The byte (or entry) budget [maxSize] scaled by [fraction], never negative. */
  fun budget(maxSize: Int, fraction: Float): Int = (maxSize * fraction.coerceIn(0f, 1f)).toInt()

  /**
   * Whether Music's player WebView is released now rather than after its grace period. A player
   * on screen goes only when the process is about to be killed; a detached one (Music closed, in
   * its grace period) goes as soon as memory runs low or our UI is hidden.
   */
  @Suppress("DEPRECATION")
  fun releasePlayer(level: Int, attached: Boolean): Boolean =
      if (attached) level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
      else level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
}
