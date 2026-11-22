/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Width planning for the Music stage's pill rows (pure, JVM-tested), so narrow compact slots
 * (the Mini upright with the sheet open, the turned Mini's idle card) never clip a pill: secondary
 * actions turn into round icon buttons, or a header's actions move onto their own row.
 */
internal object MusicPillFit {
  /** Semibold pill label at `body × 0.9` sp: a generous 0.63 em per character. */
  private const val CHAR_EM = 0.63
  /** [MusicStagePill]: 28 dp padding each side. */
  private const val PILL_PADDING = 56.0
  /** A glyph and its 14 dp gap. */
  private fun glyph(dims: MusicStageDims) = (if (dims == MusicStageDims.COMPACT) 26.0 else 32.0) + 14.0

  /** Estimated width of a [MusicStagePill] labelled [label] (with a glyph or icon when [glyph]). */
  fun pill(label: String, dims: MusicStageDims, glyph: Boolean = false, fontScale: Float = 1f): Double =
      CompactRowFit.Spec(charDp = dims.body * 0.9 * CHAR_EM, chromeDp = PILL_PADDING + if (glyph) glyph(dims) else 0.0)
          .width(label, fontScale)

  /** How a Follow-me answer pair (Continue, Not now) shows. */
  enum class Answer { PILLS, CONTINUE_PILL, ICONS }

  /**
   * The fullest [Answer] whose controls fit [availableDp] with [gapDp] between them, keeping
   * [keepDp] for whatever shares the row (the strip's song text; 0 when the row is just the pair).
   */
  fun answer(availableDp: Double, dims: MusicStageDims, gapDp: Double, keepDp: Double = 0.0, fontScale: Float = 1f): Answer {
    val room = availableDp - keepDp - if (keepDp > 0) gapDp else 0.0
    val cont = pill(MusicV3StageCopy.CONTINUE, dims, glyph = true, fontScale = fontScale)
    val notNow = pill(MusicV3StageCopy.NOT_NOW, dims, fontScale = fontScale)
    val icon = dims.target.toDouble()
    return when {
      cont + gapDp + notNow <= room -> Answer.PILLS
      cont + gapDp + icon <= room -> Answer.CONTINUE_PILL
      else -> Answer.ICONS
    }
  }

  /** Whether a heading's [actions] (their widths) fit beside [fixedDp] of other parts and [titleMinDp] of title. */
  fun actionsBeside(availableDp: Double, fixedDp: Double, titleMinDp: Double, actions: List<Double>, gapDp: Double): Boolean =
      fixedDp + titleMinDp + actions.sumOf { it + gapDp } <= availableDp

  /** Compact sheet tabs: 22 dp padding each side, 6 dp apart; labels at `tabText` sp semibold. */
  private const val TAB_PADDING = 44.0
  const val TAB_GAP = 6.0

  /** Whether the sheet's tabs use their short labels to stay clear of the close button in [availableDp]. */
  fun shortTabs(availableDp: Double, dims: MusicStageDims, fontScale: Float = 1f): Boolean {
    val spec = CompactRowFit.Spec(charDp = dims.tabText * CHAR_EM, chromeDp = TAB_PADDING)
    return !CompactRowFit.fits(MusicSheetTab.entries.map { spec.width(it.label, fontScale) }, TAB_GAP, availableDp)
  }

  /** The sheet tabs' width with the labels [short] or not (for tests). */
  fun tabsWidth(dims: MusicStageDims, short: Boolean, fontScale: Float = 1f): Double {
    val spec = CompactRowFit.Spec(charDp = dims.tabText * CHAR_EM, chromeDp = TAB_PADDING)
    return CompactRowFit.rowWidth(MusicSheetTab.entries.map { spec.width(if (short) it.shortLabel else it.label, fontScale) }, TAB_GAP)
  }

  /** A [MusicStageHeading] title: bold at `heading` sp, a generous 0.62 em per character. */
  fun headingWidth(title: String, dims: MusicStageDims, fontScale: Float = 1f): Double =
      CompactRowFit.Spec(charDp = dims.heading * 0.62, chromeDp = 0.0).width(title, fontScale)
}
