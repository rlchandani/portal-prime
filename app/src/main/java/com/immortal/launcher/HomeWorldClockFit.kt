/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Admit complete rows, reserving an overflow line whenever saved cities remain. */
internal fun homeWorldClockVisibleCount(
    cityCount: Int,
    availableHeightDp: Float,
    rowHeightDp: Float,
    footerHeightDp: Float,
): Int {
  if (cityCount <= 0 || !availableHeightDp.isFinite() || availableHeightDp <= 0f ||
      !rowHeightDp.isFinite() || rowHeightDp <= 0f ||
      !footerHeightDp.isFinite() || footerHeightDp <= 0f) return 0
  for (count in minOf(cityCount, 4) downTo 1) {
    val rowsHeight = count * rowHeightDp + (count - 1) * 8f
    val overflowHeight = if (cityCount > count) footerHeightDp + 8f else 0f
    if (rowsHeight + overflowHeight <= availableHeightDp) return count
  }
  return 0
}
