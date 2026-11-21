/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Portal (1920 × 1280) and Mini (800 × 1280 portrait) sizes for the linking screens. Pure, so the
 * Mini fit and the 64 dp touch floor are tested rather than eyeballed.
 */
internal object HomeLinkLayout {
  /** Where the code's dashes go: after the nameplate and in the secret, as [HomeLinkCode.display]. */
  val GROUP_STARTS: Set<Int> = setOf(HomeLinkCode.NAMEPLATE, HomeLinkCode.NAMEPLATE + 3)
  /** The Mini's usable width: 800 dp less two 32 dp margins. */
  val MINI_CONTENT_WIDTH: Dp = 736.dp
  private val DASH_WIDTH = 30.dp

  @Immutable
  data class Slot(val width: Dp, val height: Dp, val gap: Dp, val text: TextUnit)

  fun slot(compact: Boolean): Slot = if (compact) Slot(54.dp, 80.dp, 8.dp, 40.sp) else Slot(70.dp, 100.dp, 12.dp, 50.sp)

  /** Width of a full row of slots with its dashes. */
  fun slotsWidth(compact: Boolean): Dp {
    val s = slot(compact)
    val items = HomeLinkCode.LENGTH + GROUP_STARTS.size
    return s.width * HomeLinkCode.LENGTH + DASH_WIDTH * GROUP_STARTS.size + s.gap * (items - 1)
  }

  fun safetyColumns(compact: Boolean): Int = if (compact) 2 else HomeLinkSafety.PICTURE_COUNT

  /** Keypad key height: 100 dp on a Portal like the mock, never under 64 dp on a Mini. */
  fun keyHeight(compact: Boolean, letters: Boolean): Dp = when {
    compact && letters -> 76.dp
    compact -> 88.dp
    letters -> 88.dp
    else -> 100.dp
  }
}
