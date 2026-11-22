/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The Alerts screen's side cards (pure, JVM-tested): their copy and the preview chips' grid.
 * Widths are CC canvas units ([CcCompactScale]); the side column is [SIDE_WIDTH] on the Portal+ and
 * the Mini turned, the full content width (~881 units) on the Mini upright.
 *
 * Every title is short enough for one line in the narrowest card (no word left alone on a second
 * line), and the preview chips share one width per row instead of stacking at their own widths.
 */
internal object CcAlertsLayout {
  /** The side column beside the alerts (Portal+ and the Mini turned). */
  const val SIDE_WIDTH = 520.0
  /** Horizontal padding inside each side card, each side. */
  const val CARD_PADDING = 28.0
  /** Inside width of a side card. */
  const val SIDE_INNER = SIDE_WIDTH - 2 * CARD_PADDING

  const val PREVIEW_TITLE = "Preview a Home banner"
  const val BANNERS_TITLE = "Portal-wide banners"

  /** A banner policy row: its one-line title and what sits at its right edge. */
  data class Row(val title: String, val controlWidth: Double)

  /** CcToggle is 88 wide; the "Always" tag ~110. The row's gap to the control is 18. */
  const val ROW_GAP = 18.0
  val CRITICAL = Row("Critical — leak, smoke, CO", controlWidth = 110.0)
  val WARNINGS = Row("Warnings — doors, windows", controlWidth = 88.0)
  val INFO = Row("Info — laundry, dishwasher", controlWidth = 88.0)
  val rows = listOf(CRITICAL, WARNINGS, INFO)

  /** CcSectionLabel: 21 sp semibold uppercase + 1.6 sp tracking (generous: ~0.7 em + tracking). */
  val SECTION_LABEL = CompactRowFit.Spec(charDp = 21 * 0.7 + 1.6, chromeDp = 0.0)
  /** Row titles: 22 sp regular, generously 0.56 em a character. */
  val ROW_TITLE = CompactRowFit.Spec(charDp = 22 * 0.56, chromeDp = 0.0)

  /** Room a row's title has in a card [inner] wide. */
  fun titleRoom(row: Row, inner: Double = SIDE_INNER): Double = inner - ROW_GAP - row.controlWidth

  /** CcButton at the preview size: 34 padding each side, a 30 icon, a 12 gap; 23 sp semibold. */
  val CHIP = CompactRowFit.Spec(charDp = 23 * 0.6, chromeDp = 2 * 34.0 + 30.0 + 12.0)
  const val CHIP_GAP = 12.0

  /**
   * How many equal-width preview chips share a row in [available] units: the most columns that
   * divide [labels] into full rows (3 → 3 or 1, 4 → 4, 2 or 1) and still fit the longest label.
   */
  fun chipColumns(labels: List<String>, available: Double, fontScale: Float = 1f): Int {
    if (labels.isEmpty()) return 1
    val widest = labels.maxOf { CHIP.width(it, fontScale) }
    return (labels.size downTo 1).firstOrNull { n ->
      labels.size % n == 0 && widest * n + CHIP_GAP * (n - 1) <= available
    } ?: 1
  }
}
