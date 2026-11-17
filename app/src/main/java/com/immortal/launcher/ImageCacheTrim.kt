/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log

/**
 * Applies [MemoryTrimPolicy] to every process-wide decoded-image cache. Called from
 * [ImmortalApp.onTrimMemory]; each cache only drops references, so a bitmap still drawn by a
 * screen stays valid and is freed by the GC once that screen lets go of it.
 */
internal object ImageCacheTrim {
  private const val TAG = "ImmortalMemory"

  fun onTrimMemory(level: Int) {
    val fraction = MemoryTrimPolicy.retainedFraction(level)
    if (fraction >= MemoryTrimPolicy.KEEP_ALL) return
    trimEach(fraction)
    Log.i(TAG, "trim level $level: image caches kept at ${(fraction * 100).toInt()}%")
  }

  private fun trimEach(fraction: Float) {
    val trims = listOf<(Float) -> Unit>(
        MultiRoomArt::trim, Thumbnails::trim, StoreIcons::trim, ::approvedSourcesTrimPreviews)
    trims.forEach { trim ->
      runCatching { trim(fraction) }.onFailure { Log.w(TAG, "image cache trim failed", it) }
    }
  }
}
