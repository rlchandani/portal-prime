/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.ceil

/**
 * Pure width planning for a row of labelled controls (tabs, chips, pills), so a screen can decide —
 * and a JVM test can prove — that a header never scrolls sideways or clips on the Mini.
 *
 * Widths are estimates in dp at font scale 1: a generous per-character width for the type size
 * plus the control's fixed parts (padding, icon, gap). Overestimating only moves a control to the
 * next row or picks the shorter variant; it never clips. [CallsHeaderLayout] uses the same model.
 */
internal object CompactRowFit {
  /** How one kind of control measures: [charDp] per label character, [chromeDp] for everything else. */
  data class Spec(val charDp: Double, val chromeDp: Double) {
    fun width(label: String, fontScale: Float = 1f): Double = width(label.length, fontScale)

    fun width(chars: Int, fontScale: Float = 1f): Double = ceil(chars * charDp * fontScale) + chromeDp
  }

  /** Total width of [widths] laid out in one row with [gapDp] between neighbours. */
  fun rowWidth(widths: List<Double>, gapDp: Double): Double =
      if (widths.isEmpty()) 0.0 else widths.sum() + gapDp * (widths.size - 1)

  /** Whether [widths] fit one row of [availableDp]. */
  fun fits(widths: List<Double>, gapDp: Double, availableDp: Double): Boolean = rowWidth(widths, gapDp) <= availableDp

  /**
   * Greedy reading-order packing of [widths] into rows no wider than [availableDp]; returns the
   * indices per row. An item wider than the row on its own still gets a row of its own (its label
   * then ellipsizes) — never a sideways scroll.
   */
  fun pack(widths: List<Double>, gapDp: Double, availableDp: Double): List<List<Int>> {
    val rows = mutableListOf<List<Int>>()
    var row = listOf<Int>()
    var used = 0.0
    widths.forEachIndexed { i, w ->
      val needed = if (row.isEmpty()) w else used + gapDp + w
      if (row.isEmpty() || needed <= availableDp) {
        row = row + i
        used = needed
      } else {
        rows += row
        row = listOf(i)
        used = w
      }
    }
    if (row.isNotEmpty()) rows += row
    return rows
  }
}
