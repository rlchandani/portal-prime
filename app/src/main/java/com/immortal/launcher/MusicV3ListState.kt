/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * How far past the viewport the music lists keep rows composed (dp). The default prefetch composes
 * one row ahead, but a Portal fling crosses about a row (112 dp) per frame, so rows were composed
 * (text layout, art lookup, click and focus nodes) inside the frame that first shows them. A window
 * of a few rows ahead is filled in idle time between frames instead; the window behind keeps rows
 * that just left alive, so reversing a fling re-shows them with no composition at all.
 */
internal object MusicV3ListWindow {
  const val AHEAD_DP = 360f
  const val BEHIND_DP = 240f
}

/** The [LazyListState] for the sheet's song lists, with the [MusicV3ListWindow] prefetch window. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberMusicV3ListState(): LazyListState {
  val window = remember { LazyLayoutCacheWindow(ahead = MusicV3ListWindow.AHEAD_DP.dp, behind = MusicV3ListWindow.BEHIND_DP.dp) }
  return rememberLazyListState(cacheWindow = window)
}
