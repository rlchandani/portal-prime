/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Rows of the Tools library's category chips on compact windows (pure, JVM-tested): in reading
 * order, wrapped onto further rows instead of scrolling sideways. The Mini upright fits the basic
 * set on one row; the advanced set's "Advanced" chip moves to a second row.
 */
internal object ToolsCategoryLayout {
  /** Gap between chips (both directions) and the inset around the chip block, dp. */
  const val GAP_DP = 12
  const val INSET_DP = 4

  /** Compact chip label padding each side (the Portal+ keeps 24 dp). */
  const val CHIP_PADDING_DP = 20

  /** 22 sp medium label (generously 13 dp a character) + [CHIP_PADDING_DP] each side. */
  val CHIP = CompactRowFit.Spec(charDp = 13.0, chromeDp = 2.0 * CHIP_PADDING_DP)

  /** Category indices per row for a library [widthDp] wide (the grid's content width). */
  fun rows(categories: List<String>, widthDp: Double, fontScale: Float = 1f): List<List<Int>> =
      CompactRowFit.pack(categories.map { CHIP.width(it, fontScale) }, GAP_DP.toDouble(), widthDp - 2 * INSET_DP)
}
