/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The Apps tab grid. LARGE keeps its six columns; compact windows get as many columns of at
 * least [COMPACT_MIN_CELL_DP] as fit (4 on the upright Mini, 6 turned), so a label never has to
 * break inside a word. Pure (JVM tested).
 */
internal object AppsGridMetrics {
  const val LARGE_COLUMNS = 6
  const val COMPACT_MIN_CELL_DP = 160
  const val COMPACT_GAP_DP = 24
  const val COMPACT_MIN_COLUMNS = 3
  /** A word this long or shorter fits one line of a compact tile's label (20 sp, ≥ 136 dp). */
  const val COMPACT_WORD_CHARS = 12

  fun columns(layout: LayoutClass, contentWidthDp: Int): Int {
    if (!layout.compact) return LARGE_COLUMNS
    val fit = (contentWidthDp + COMPACT_GAP_DP) / (COMPACT_MIN_CELL_DP + COMPACT_GAP_DP)
    return fit.coerceIn(COMPACT_MIN_COLUMNS, LARGE_COLUMNS)
  }

  /**
   * Lines for a compact tile label: two (wrapping between words) when every word fits a line,
   * else one line with an ellipsis, so a long single word is never split across lines.
   */
  fun labelLines(label: String): Int =
      if (label.split(' ', '-', '_').all { it.length <= COMPACT_WORD_CHARS }) 2 else 1
}
