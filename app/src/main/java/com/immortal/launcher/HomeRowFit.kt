/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * How many equal-height rows fit in a widget body of [availableHeightDp], reserving a
 * "N more" footer (plus one gap) only when some rows are hidden. Never more than [cap].
 * Returns 0 for empty or non-finite input, so callers show only the footer.
 */
internal fun homeRowsVisibleCount(
    itemCount: Int,
    availableHeightDp: Float,
    rowHeightDp: Float,
    gapDp: Float,
    footerHeightDp: Float,
    cap: Int,
): Int {
  val inputs = listOf(availableHeightDp, rowHeightDp, gapDp, footerHeightDp)
  if (itemCount <= 0 || cap <= 0 || inputs.any { !it.isFinite() || it < 0f } || rowHeightDp == 0f) return 0
  for (count in minOf(itemCount, cap) downTo 1) {
    val rows = count * rowHeightDp + (count - 1) * gapDp
    val footer = if (itemCount > count) footerHeightDp + gapDp else 0f
    if (rows + footer <= availableHeightDp) return count
  }
  return 0
}
